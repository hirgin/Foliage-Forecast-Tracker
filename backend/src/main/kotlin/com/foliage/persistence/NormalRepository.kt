package com.foliage.persistence

import com.foliage.domain.WeatherNormal
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.PreparedStatement
import java.sql.Types
import java.time.MonthDay

/**
 * Climatological normals, keyed by calendar day.
 *
 * Never touched by daily ingest — see the amendment to ADR-0005. This is the
 * baseline the drought term measures against, so it must survive the arrival
 * of real observations for the same dates.
 */
@Repository
class NormalRepository(private val jdbc: JdbcTemplate) {

    /** MM-DD, matching the CHAR(5) column. */
    private fun key(md: MonthDay): String = "%02d-%02d".format(md.monthValue, md.dayOfMonth)

    fun upsertAll(normals: List<WeatherNormal>): Int {
        if (normals.isEmpty()) return 0

        val sql = """
            INSERT INTO weather_normal
                (h3, month_day, resolution, tmax_c, tmin_c, precip_mm,
                 chill_units, frost_frequency, years_averaged)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
                tmax_c          = VALUES(tmax_c),
                tmin_c          = VALUES(tmin_c),
                precip_mm       = VALUES(precip_mm),
                chill_units     = VALUES(chill_units),
                frost_frequency = VALUES(frost_frequency),
                resolution      = VALUES(resolution),
                years_averaged  = VALUES(years_averaged),
                computed_at     = NOW(6)
        """.trimIndent()

        val counts = jdbc.batchUpdate(sql, normals, JDBC_BATCH_SIZE) { ps: PreparedStatement, n: WeatherNormal ->
            ps.setLong(1, n.h3)
            ps.setString(2, key(n.monthDay))
            ps.setInt(3, n.resolution)
            setDouble(ps, 4, n.tmaxC)
            setDouble(ps, 5, n.tminC)
            setDouble(ps, 6, n.precipMm)
            setDouble(ps, 7, n.chillUnits)
            setDouble(ps, 8, n.frostFrequency)
            ps.setInt(9, n.yearsAveraged)
        }
        return counts.sumOf { it.size }
    }

    private fun setDouble(ps: PreparedStatement, i: Int, v: Double?) =
        v?.let { ps.setDouble(i, it) } ?: ps.setNull(i, Types.DECIMAL)

    /**
     * Normal precipitation accumulated from the season start through a
     * calendar day, per cell. This is the denominator of the drought anomaly.
     */
    fun cumulativePrecipByCell(fromMonthDay: MonthDay, toMonthDay: MonthDay): Map<Long, Double> = jdbc.query(
        """
        SELECT h3, SUM(COALESCE(precip_mm, 0)) total
        FROM weather_normal
        WHERE month_day BETWEEN ? AND ?
        GROUP BY h3
        """.trimIndent(),
        { rs, _ -> rs.getLong("h3") to rs.getDouble("total") },
        key(fromMonthDay), key(toMonthDay),
    ).toMap()

    /** Per-cell normal precipitation by calendar day, for accumulating anomalies. */
    fun precipNormalsByCell(): Map<Long, Map<MonthDay, Double>> {
        val out = HashMap<Long, MutableMap<MonthDay, Double>>()
        jdbc.query("SELECT h3, month_day, precip_mm FROM weather_normal") { rs ->
            val md = rs.getString("month_day").split("-")
            val precip = rs.getObject("precip_mm")?.let { rs.getDouble("precip_mm") } ?: 0.0
            out.getOrPut(rs.getLong("h3")) { HashMap() }[MonthDay.of(md[0].toInt(), md[1].toInt())] = precip
        }
        return out
    }

    /** Per-cell mean chilling units by calendar day, for climatological days. */
    fun chillUnitsByCell(): Map<Long, Map<MonthDay, Double>> {
        val out = HashMap<Long, MutableMap<MonthDay, Double>>()
        jdbc.query("SELECT h3, month_day, chill_units FROM weather_normal WHERE chill_units IS NOT NULL") { rs ->
            val md = rs.getString("month_day").split("-")
            out.getOrPut(rs.getLong("h3")) { HashMap() }[MonthDay.of(md[0].toInt(), md[1].toInt())] =
                rs.getDouble("chill_units")
        }
        return out
    }

    /**
     * Res 5 parents that already have normals.
     *
     * The backfill needs to know what is already done, and row counts do not
     * tell it: a state whose climatology died partway through the daily
     * allowance has rows and is still incomplete. Compared against a state's
     * parents, this is what makes the nightly run resume rather than restart.
     */
    /**
     * Parents whose normals cover the whole season.
     *
     * Day-aware, and it has to be. This used to return every parent holding
     * *any* normals, which is the same answer right up until the season
     * changes -- and then it is silently wrong in the worst way: extending the
     * season to cover the Gulf states left all 28,687 parents looking complete,
     * so the backfill would have skipped every one of them and the new days
     * would simply never have been fetched. Every southern cell would have
     * scored against a month of missing weather and quietly stopped
     * progressing on 15 November, which looks exactly like a real forecast.
     *
     * Counting days instead means changing the season is enough on its own:
     * everything becomes incomplete, and the backfill refills it.
     */
    fun cellsWithNormals(seasonDays: Int): Set<Long> = jdbc.queryForList(
        "SELECT h3 FROM weather_normal WHERE chill_units IS NOT NULL " +
            "GROUP BY h3 HAVING COUNT(DISTINCT month_day) >= ?",
        Long::class.java,
        seasonDays,
    ).toSet()

    /**
     * How many normals a state holds at each `years_averaged`.
     *
     * A state loaded across a change in method carries both, which means its
     * cells disagree about how many years they average and how coarse the
     * source reading was. That is a seam, not a gradient.
     */
    fun yearsAveragedByState(stateFips: String): Map<Int, Long> = jdbc.query(
        "SELECT n.years_averaged AS y, COUNT(*) AS n FROM weather_normal n " +
            "JOIN cell c ON c.parent_res5 = n.h3 WHERE c.state_fips = ? GROUP BY n.years_averaged",
        { rs, _ -> rs.getInt("y") to rs.getLong("n") },
        stateFips,
    ).toMap()

    /** Drops a state's normals so they can be rebuilt by one consistent method. */
    fun deleteByState(stateFips: String): Int = jdbc.update(
        "DELETE n FROM weather_normal n " +
            "JOIN (SELECT DISTINCT parent_res5 FROM cell WHERE state_fips = ?) c " +
            "ON c.parent_res5 = n.h3",
        stateFips,
    )

    fun count(): Long =
        jdbc.queryForObject("SELECT COUNT(*) FROM weather_normal", Long::class.java) ?: 0
}
