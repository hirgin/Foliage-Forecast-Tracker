package com.foliage.persistence

import com.foliage.forecast.FoliageStage
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.Date
import java.sql.PreparedStatement
import java.time.LocalDate

/** A stored score for one cell on one day. */
/** How late a state's cells peak, against how complete their weather is. */
data class StatePeakSpread(
    val state: String,
    val cells: Int,
    val neverPeaks: Int,
    val peaksLate: Int,
    val thinWeather: Int,
    val earliest: String?,
    val latest: String?,
)

/** One state's forecast coverage, for the cheap coverage check. */
data class StateCoverage(
    val state: String,
    val cells: Int,
    val withForecast: Int,
) {
    val missing: Int get() = cells - withForecast
    val missingPct: Double get() = if (cells == 0) 0.0 else 100.0 * missing / cells
}

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
     *
     * Writes go out in fixed-size batches rather than one batch of everything.
     * With `rewriteBatchedStatements=true` the driver folds a batch into a
     * single multi-row INSERT, so passing `rows.size` made the statement scale
     * with the state: Vermont's 47,000 rows and Maine's 170,000 were fine,
     * and New York's 284,392 exceeded what the server would accept and dropped
     * the connection mid-write with a bare EOF. Batching is what makes the
     * write fast (ADR-0003); leaving the batch unbounded is what broke it.
     * See [JDBC_BATCH_SIZE].
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

        val counts = jdbc.batchUpdate(sql, rows, JDBC_BATCH_SIZE) { ps: PreparedStatement, r: StoredForecast ->
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

    /**
     * How much of each state has reached peak, as counts.
     *
     * Used to decide which states need December weather at all. A state whose
     * forest has already turned by mid-November has no use for another month of
     * cooling: its cells are saturated, more weather changes nothing, and
     * fetching it spends a metered API and a metered database on a result that
     * is identical either way.
     *
     * Counts, not rows, for the reason coverageByState exists.
     */
    fun peakCoverageByState(minCanopyPct: Int): List<StateCoverage> = jdbc.query(
        """
        SELECT c.state_name AS state,
               COUNT(*) AS total,
               COUNT(p.h3) AS with_forecast
        FROM cell c
        LEFT JOIN (SELECT DISTINCT h3 FROM foliage_forecast
                   WHERE stage IN ('PEAK', 'PAST_PEAK')) p ON p.h3 = c.h3
        WHERE c.state_name IS NOT NULL
          AND (c.canopy_pct IS NULL OR c.canopy_pct >= ?)
        GROUP BY c.state_name
        """.trimIndent(),
        { rs, _ ->
            StateCoverage(
                state = rs.getString("state"),
                cells = rs.getInt("total"),
                withForecast = rs.getInt("with_forecast"),
            )
        },
        minCanopyPct,
    )

    /**
     * How much of each state carries a forecast **on one particular day**.
     *
     * Distinct from [coverageByState], which asks whether a cell has any
     * forecast at all, and misses the failure that matters at the end of a
     * season: a state whose weather is complete but whose forecast was scored
     * before the season was extended. Louisiana had every day of December
     * weather loaded and a forecast that stopped on 15 November, and no
     * whole-season count could see it.
     */
    fun coverageByStateOnDay(day: LocalDate, minCanopyPct: Int): List<StateCoverage> = jdbc.query(
        """
        SELECT c.state_name AS state,
               COUNT(*) AS total,
               COUNT(f.h3) AS with_forecast
        FROM cell c
        LEFT JOIN foliage_forecast f ON f.h3 = c.h3 AND f.day = ?
        WHERE c.state_name IS NOT NULL
          AND (c.canopy_pct IS NULL OR c.canopy_pct >= ?)
        GROUP BY c.state_name
        ORDER BY (COUNT(*) - COUNT(f.h3)) DESC
        """.trimIndent(),
        { rs, _ ->
            StateCoverage(
                state = rs.getString("state"),
                cells = rs.getInt("total"),
                withForecast = rs.getInt("with_forecast"),
            )
        },
        day, minCanopyPct,
    )

    /**
     * How much of each state carries a forecast, as counts.
     *
     * Added after finding out what the lazy version costs. Checking coverage by
     * downloading `/forecast?date=...` and counting the cells it contains means
     * pulling twelve megabytes and every row behind it, and doing that half a
     * dozen times in an evening -- once per "is it fixed yet" -- is a
     * meaningful share of a month's compute allowance on a metered tier. It
     * contributed to exhausting one.
     *
     * This answers the same question in a single grouped count: one small
     * result, no rows crossing the network. The lesson is not subtle -- a check
     * you run repeatedly should cost less than the work it is checking.
     */
    fun coverageByState(minCanopyPct: Int, metroPopulation: Int): List<StateCoverage> = jdbc.query(
        """
        SELECT c.state_name AS state,
               COUNT(*) AS total,
               COUNT(f.h3) AS with_forecast
        FROM cell c
        LEFT JOIN (SELECT DISTINCT h3 FROM foliage_forecast) f ON f.h3 = c.h3
        WHERE c.state_name IS NOT NULL
          AND (c.canopy_pct IS NULL OR c.canopy_pct >= ?
               OR c.parent_res5 IN (SELECT DISTINCT c2.parent_res5 FROM place p
                                    JOIN cell c2 ON c2.h3 = p.h3 WHERE p.population >= ?))
        GROUP BY c.state_name
        ORDER BY (COUNT(*) - COUNT(f.h3)) DESC
        """.trimIndent(),
        { rs, _ ->
            StateCoverage(
                state = rs.getString("state"),
                cells = rs.getInt("total"),
                withForecast = rs.getInt("with_forecast"),
            )
        },
        minCanopyPct, metroPopulation,
    )

    /**
     * How many cells hold a score on one day.
     *
     * A count rather than [byDay] because the export uses it to find where the
     * data stops, and pulling every row for a hundred days to measure their
     * length would cost more than the export itself.
     */
    fun countByDay(day: LocalDate): Int = jdbc.queryForObject(
        "SELECT COUNT(*) FROM foliage_forecast WHERE day = ?",
        Int::class.java,
        Date.valueOf(day),
    ) ?: 0

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

    /**
     * The same, for one batch of cells.
     *
     * [allTimelines] reads the entire forecast table in a single statement,
     * which was fine at 4.6M rows and is not at 15M: lowering the canopy floor
     * to 5% and running the season into December put it past what the hosted
     * tier will serve at once, and the export died on
     * "query has been cancelled due to exceeding the allowed memory limit".
     * Not a slow query -- a refused one, so the whole deploy failed.
     *
     * The export already groups cells into res 3 shards to write them, so
     * asking per shard costs one round trip per file it was going to write
     * anyway, and no single statement is ever large enough to be refused.
     */
    fun timelinesFor(h3s: Collection<Long>): Map<Long, List<StoredForecast>> {
        if (h3s.isEmpty()) return emptyMap()
        val placeholders = h3s.joinToString(",") { "?" }
        return jdbc.query(
            """
            SELECT h3, day, progression, intensity, stage, confidence
            FROM foliage_forecast WHERE h3 IN ($placeholders) ORDER BY h3, day
            """.trimIndent(),
            { rs, _ -> map(rs) },
            *h3s.toTypedArray(),
        ).groupBy { it.h3 }
    }

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

    /**
     * The day each cell first reaches peak — the question the site exists to
     * answer.
     *
     * [stateFips] null covers the whole grid. Scoping matters for the summary a
     * run reports back: without it, every state returned peak statistics for
     * the entire table, so a national grid had Maine and Connecticut both
     * reporting a median of 10 October and an identical range. That reads as a
     * model with no latitude gradient when the model is fine and the query was
     * simply unscoped.
     */
    /**
     * When each state's cells peak, and how complete the weather behind them is.
     *
     * Built to chase scattered hexagons that peak long after everything around
     * them. The suspicion was that a cell whose res 5 parent holds only part of
     * the season accumulates less cooling than its neighbours and therefore
     * peaks late or never -- a data gap wearing the costume of a forecast. This
     * puts the two side by side so the question can be answered rather than
     * argued about.
     */
    fun peakSpreadByState(minCanopyPct: Int, seasonDays: Int): List<StatePeakSpread> = jdbc.query(
        """
        SELECT c.state_name AS state,
               COUNT(*) AS cells,
               SUM(CASE WHEN p.peak IS NULL THEN 1 ELSE 0 END) AS never_peaks,
               SUM(CASE WHEN p.peak >= ? THEN 1 ELSE 0 END) AS peaks_late,
               SUM(CASE WHEN n.days < ? THEN 1 ELSE 0 END) AS thin_weather,
               MIN(p.peak) AS earliest,
               MAX(p.peak) AS latest
        FROM cell c
        LEFT JOIN (SELECT h3, MIN(day) peak FROM foliage_forecast
                   WHERE stage = 'PEAK' GROUP BY h3) p ON p.h3 = c.h3
        LEFT JOIN (SELECT h3, COUNT(DISTINCT month_day) days FROM weather_normal
                   GROUP BY h3) n ON n.h3 = c.parent_res5
        WHERE c.state_name IS NOT NULL
          AND (c.canopy_pct IS NULL OR c.canopy_pct >= ?)
        GROUP BY c.state_name
        ORDER BY never_peaks + peaks_late DESC
        """.trimIndent(),
        { rs, _ ->
            StatePeakSpread(
                state = rs.getString("state"),
                cells = rs.getInt("cells"),
                neverPeaks = rs.getInt("never_peaks"),
                peaksLate = rs.getInt("peaks_late"),
                thinWeather = rs.getInt("thin_weather"),
                earliest = rs.getDate("earliest")?.toLocalDate()?.toString(),
                latest = rs.getDate("latest")?.toLocalDate()?.toString(),
            )
        },
        java.sql.Date.valueOf(LocalDate.of(LocalDate.now().year, 11, 25)),
        seasonDays,
        minCanopyPct,
    )

    fun peakDayByCell(stateFips: String? = null): Map<Long, LocalDate> {
        val mapper = { rs: java.sql.ResultSet, _: Int ->
            rs.getLong("h3") to rs.getDate("peak").toLocalDate()
        }

        if (stateFips == null) {
            return jdbc.query(
                """
                SELECT h3, MIN(day) peak FROM foliage_forecast
                WHERE stage = 'PEAK' GROUP BY h3
                """.trimIndent(),
                mapper,
            ).toMap()
        }

        // Joined rather than filtered by a stored state column, because the
        // forecast table deliberately holds no geography -- cell does.
        return jdbc.query(
            """
            SELECT f.h3, MIN(f.day) peak
            FROM foliage_forecast f
            JOIN cell c ON c.h3 = f.h3
            WHERE f.stage = 'PEAK' AND c.state_fips = ?
            GROUP BY f.h3
            """.trimIndent(),
            mapper,
            stateFips,
        ).toMap()
    }

    /**
     * Drops a state's scores.
     *
     * For a state scored against incomplete weather. Climatology is not
     * optional decoration: the forecast horizon reaches about 12 September and
     * climatology supplies the rest of the season (ADR-0005), so a state with
     * observations but no normals has no weather for most of autumn. It scores
     * without error and never reaches peak, which renders as "no change" all
     * October -- a confident, wrong answer rather than a visible gap.
     *
     * Deleting is better than publishing that: an absent cell draws grey and
     * reads as "not computed yet", which is the truth.
     */
    fun deleteByState(stateFips: String): Int = jdbc.update(
        """
        DELETE f FROM foliage_forecast f
        JOIN cell c ON c.h3 = f.h3
        WHERE c.state_fips = ?
        """.trimIndent(),
        stateFips,
    )

    private fun map(rs: java.sql.ResultSet) = StoredForecast(
        h3 = rs.getLong("h3"),
        day = rs.getDate("day").toLocalDate(),
        progression = rs.getDouble("progression"),
        intensity = rs.getDouble("intensity"),
        stage = FoliageStage.valueOf(rs.getString("stage")),
        confidence = rs.getDouble("confidence"),
    )
}
