package com.foliage.ingest.audit

import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.support.GeneratedKeyHolder
import org.springframework.stereotype.Component
import java.sql.Statement

/**
 * Writes the `ingest_run` audit row for every pipeline job.
 *
 * Jobs are expected to be idempotent and resumable, so this records what a run
 * actually achieved rather than what it intended -- a failed run still leaves
 * its partial `rows_written` behind, which is what makes resuming meaningful.
 */
@Component
class IngestRunRecorder(private val jdbc: JdbcTemplate) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * When each state's forecast weather was last refreshed successfully.
     *
     * Keyed by the FIPS in the job name. Used to refresh the stalest states
     * first, so a capped nightly refresh still comes round to everything
     * instead of always redoing the same few.
     */
    fun lastForecastRefreshByState(): Map<String, java.time.Instant> = jdbc.query(
        """
        SELECT SUBSTRING(job, LENGTH('weather-forecast:') + 1) AS fips,
               MAX(finished_at) AS last_run
        FROM ingest_run
        WHERE job LIKE 'weather-forecast:%' AND status = 'succeeded'
        GROUP BY fips
        """.trimIndent(),
    ) { rs, _ ->
        rs.getString("fips") to (rs.getTimestamp("last_run")?.toInstant() ?: java.time.Instant.EPOCH)
    }.toMap()

    fun start(source: String, job: String): Long {
        val keys = GeneratedKeyHolder()
        jdbc.update({ conn ->
            conn.prepareStatement(
                "INSERT INTO ingest_run (source, job, status) VALUES (?, ?, 'running')",
                Statement.RETURN_GENERATED_KEYS,
            ).apply {
                setString(1, source)
                setString(2, job)
            }
        }, keys)
        val id = keys.key!!.toLong()
        log.info("ingest_run {} started: {}/{}", id, source, job)
        return id
    }

    fun succeed(id: Long, rowsWritten: Long) {
        jdbc.update(
            "UPDATE ingest_run SET status = 'succeeded', finished_at = NOW(6), rows_written = ? WHERE id = ?",
            rowsWritten, id,
        )
        log.info("ingest_run {} succeeded: {} rows", id, rowsWritten)
    }

    fun fail(id: Long, rowsWritten: Long, error: Throwable) {
        jdbc.update(
            "UPDATE ingest_run SET status = 'failed', finished_at = NOW(6), rows_written = ?, error = ? WHERE id = ?",
            rowsWritten, error.message?.take(2000), id,
        )
        log.error("ingest_run {} failed after {} rows", id, rowsWritten, error)
    }
}
