package com.foliage.persistence

import com.foliage.forecast.FoliageStage
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.Date
import java.sql.PreparedStatement
import java.time.LocalDate

/** A stored score for one cell on one day. */
data class StoredForecast(
    val h3: Long,
    val day: LocalDate,
    val progression: Double,
    val intensity: Double,
    val stage: FoliageStage,
    val confidence: Double,
)

@Repository
class ForecastRepository(private val jdbc: JdbcTemplate) {

    /**
     * Recomputing a season replaces it wholesale: the model is deterministic
     * given its inputs, so a rerun with the same weather converges, and a
     * rerun after new observations arrive should overwrite.
     */
    fun upsertAll(rows: List<StoredForecast>, modelVersion: String): Int {
        if (rows.isEmpty()) return 0

        val sql = """
            INSERT INTO foliage_forecast
                (h3, day, progression, intensity, stage, confidence, model_version)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
                progression   = VALUES(progression),
                intensity     = VALUES(intensity),
                stage         = VALUES(stage),
                confidence    = VALUES(confidence),
                model_version = VALUES(model_version),
                computed_at   = NOW(6)
        """.trimIndent()

        val counts = jdbc.batchUpdate(sql, rows, rows.size) { ps: PreparedStatement, r: StoredForecast ->
            ps.setLong(1, r.h3)
            ps.setDate(2, Date.valueOf(r.day))
            ps.setDouble(3, r.progression)
            ps.setDouble(4, r.intensity)
            ps.setString(5, r.stage.name)
            ps.setDouble(6, r.confidence)
            ps.setString(7, modelVersion)
        }
        return counts.sumOf { it.size }
    }

    /** Every cell's score on one day — the map's primary query. */
    fun byDay(day: LocalDate): List<StoredForecast> = jdbc.query(
        """
        SELECT h3, day, progression, intensity, stage, confidence
        FROM foliage_forecast WHERE day = ? ORDER BY h3
        """.trimIndent(),
        { rs, _ -> map(rs) },
        Date.valueOf(day),
    )

    /** One cell's whole season — the detail panel's curve. */
    fun timeline(h3: Long): List<StoredForecast> = jdbc.query(
        """
        SELECT h3, day, progression, intensity, stage, confidence
        FROM foliage_forecast WHERE h3 = ? ORDER BY day
        """.trimIndent(),
        { rs, _ -> map(rs) },
        h3,
    )

    /**
     * Every cell's whole season in one query, grouped by cell.
     *
     * The static export needs all 649 timelines; fetching them one at a time
     * is 649 round trips to a hosted database, which dominated the first
     * export run.
     */
    fun allTimelines(): Map<Long, List<StoredForecast>> = jdbc.query(
        """
        SELECT h3, day, progression, intensity, stage, confidence
        FROM foliage_forecast ORDER BY h3, day
        """.trimIndent(),
        { rs, _ -> map(rs) },
    ).groupBy { it.h3 }

    /** Season bounds and row count, for /meta. */
    fun coverage(): Coverage? = jdbc.query(
        "SELECT MIN(day) lo, MAX(day) hi, COUNT(*) n, COUNT(DISTINCT h3) cells FROM foliage_forecast",
        { rs, _ ->
            val lo = rs.getDate("lo") ?: return@query null
            Coverage(
                from = lo.toLocalDate().toString(),
                to = rs.getDate("hi").toLocalDate().toString(),
                rows = rs.getLong("n"),
                cells = rs.getLong("cells"),
            )
        },
    ).firstOrNull()

    /** Stage distribution on a day, for sanity-checking a run. */
    fun stageHistogram(day: LocalDate): Map<String, Long> = jdbc.query(
        "SELECT stage, COUNT(*) n FROM foliage_forecast WHERE day = ? GROUP BY stage",
        { rs, _ -> rs.getString("stage") to rs.getLong("n") },
        Date.valueOf(day),
    ).toMap()

    /** The day each cell first reaches peak — the question the site exists to answer. */
    fun peakDayByCell(): Map<Long, LocalDate> = jdbc.query(
        """
        SELECT h3, MIN(day) peak FROM foliage_forecast
        WHERE stage = 'PEAK' GROUP BY h3
        """.trimIndent(),
        { rs, _ -> rs.getLong("h3") to rs.getDate("peak").toLocalDate() },
    ).toMap()

    private fun map(rs: java.sql.ResultSet) = StoredForecast(
        h3 = rs.getLong("h3"),
        day = rs.getDate("day").toLocalDate(),
        progression = rs.getDouble("progression"),
        intensity = rs.getDouble("intensity"),
        stage = FoliageStage.valueOf(rs.getString("stage")),
        confidence = rs.getDouble("confidence"),
    )
}
