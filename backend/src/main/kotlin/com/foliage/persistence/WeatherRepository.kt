package com.foliage.persistence

import com.foliage.domain.WeatherDay
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.Date
import java.sql.PreparedStatement
import java.sql.Types

/**
 * Bulk writes for daily weather.
 *
 * The upsert enforces the provenance ordering from ADR-0005: a row may be
 * replaced by an equal or *better* kind, never a worse one. Climatology may
 * become forecast and forecast may become observed; nothing goes backwards.
 * Without this, a climatology rebuild run after the daily refresh would
 * quietly overwrite real observations with long-run averages.
 */
@Repository
class WeatherRepository(private val jdbc: JdbcTemplate) {

    /** Ordering must match [com.foliage.domain.WeatherKind] precedence. */
    private val rank = "FIELD(%s, 'CLIMATOLOGY', 'FORECAST', 'OBSERVED')"

    fun upsertAll(rows: List<WeatherDay>): Int {
        if (rows.isEmpty()) return 0

        val newRank = rank.format("VALUES(kind)")
        val oldRank = rank.format("kind")

        // Provenance wins first; resolution breaks the tie.
        //
        // Two sources now write here. Open-Meteo lands at H3 resolution 5 and
        // HRRR at resolution 6, and where they overlap the finer one should
        // win -- but only for the same provenance. A resolution 6 CLIMATOLOGY
        // estimate must never displace a resolution 5 OBSERVED reading.
        // See ADR-0005 and ADR-0006.
        val better = "($newRank > $oldRank OR ($newRank = $oldRank AND VALUES(resolution) >= resolution))"

        // COALESCE on every measurement, because a winning source may not
        // carry every field. HRRR's surface analysis has no precipitation
        // accumulation, so without this an HRRR row would blank the
        // precipitation Open-Meteo had already supplied -- silently turning
        // wet days dry and disabling the drought term for those dates.
        val sql = """
            INSERT INTO weather_daily
                (h3, day, resolution, kind, tmax_c, tmin_c, precip_mm, radiation_mj)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
                tmax_c       = IF($better, COALESCE(VALUES(tmax_c),       tmax_c),       tmax_c),
                tmin_c       = IF($better, COALESCE(VALUES(tmin_c),       tmin_c),       tmin_c),
                precip_mm    = IF($better, COALESCE(VALUES(precip_mm),    precip_mm),    precip_mm),
                radiation_mj = IF($better, COALESCE(VALUES(radiation_mj), radiation_mj), radiation_mj),
                resolution   = IF($better, VALUES(resolution),   resolution),
                fetched_at   = IF($better, NOW(6),               fetched_at),
                kind         = IF($better, VALUES(kind),         kind)
        """.trimIndent()

        val counts = jdbc.batchUpdate(sql, rows, rows.size) { ps: PreparedStatement, r: WeatherDay ->
            ps.setLong(1, r.h3)
            ps.setDate(2, Date.valueOf(r.day))
            ps.setInt(3, r.resolution)
            ps.setString(4, r.kind.name)
            setDouble(ps, 5, r.tmaxC)
            setDouble(ps, 6, r.tminC)
            setDouble(ps, 7, r.precipMm)
            setDouble(ps, 8, r.radiationMj)
        }
        return counts.sumOf { it.size }
    }

    private fun setDouble(ps: PreparedStatement, i: Int, v: Double?) =
        v?.let { ps.setDouble(i, it) } ?: ps.setNull(i, Types.DECIMAL)

    /** Every stored day for the given cells, grouped by cell and ordered. */
    fun seriesByCell(h3s: List<Long>): Map<Long, List<WeatherDay>> {
        if (h3s.isEmpty()) return emptyMap()
        val placeholders = h3s.joinToString(",") { "?" }
        val rows = jdbc.query(
            """
            SELECT h3, day, resolution, kind, tmax_c, tmin_c, precip_mm, radiation_mj
            FROM weather_daily
            WHERE h3 IN ($placeholders)
            ORDER BY h3, day
            """.trimIndent(),
            { rs, _ ->
                WeatherDay(
                    h3 = rs.getLong("h3"),
                    day = rs.getDate("day").toLocalDate(),
                    resolution = rs.getInt("resolution"),
                    kind = com.foliage.domain.WeatherKind.valueOf(rs.getString("kind")),
                    tmaxC = rs.getObject("tmax_c")?.let { rs.getDouble("tmax_c") },
                    tminC = rs.getObject("tmin_c")?.let { rs.getDouble("tmin_c") },
                    precipMm = rs.getObject("precip_mm")?.let { rs.getDouble("precip_mm") },
                    radiationMj = rs.getObject("radiation_mj")?.let { rs.getDouble("radiation_mj") },
                )
            },
            *h3s.toTypedArray(),
        )
        return rows.groupBy { it.h3 }
    }

    fun countByKind(): Map<String, Long> = jdbc.query(
        "SELECT kind, COUNT(*) n FROM weather_daily GROUP BY kind",
        { rs, _ -> rs.getString("kind") to rs.getLong("n") },
    ).toMap()

    fun coverage(): Coverage? = jdbc.query(
        """
        SELECT MIN(day) lo, MAX(day) hi, COUNT(*) n, COUNT(DISTINCT h3) cells
        FROM weather_daily
        """.trimIndent(),
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
}

data class Coverage(val from: String, val to: String, val rows: Long, val cells: Long)
