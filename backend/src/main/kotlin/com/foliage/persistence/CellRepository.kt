package com.foliage.persistence

import com.foliage.domain.Cell
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.PreparedStatement
import java.sql.Types

/**
 * Bulk writes for the grid.
 *
 * MySQL has no `COPY`, so the fast path is JDBC batching with
 * `rewriteBatchedStatements=true` on the connection, which collapses a batch
 * into multi-row INSERTs. See ADR-0004.
 */
@Repository
class CellRepository(private val jdbc: JdbcTemplate) {

    /**
     * Idempotent by design: re-running a bootstrap must converge rather than
     * fail or duplicate. Terrain columns are only overwritten by non-null
     * values, so a partial re-run cannot erase data an earlier run collected.
     */
    fun upsertAll(cells: List<Cell>): Int {
        if (cells.isEmpty()) return 0

        val sql = """
            INSERT INTO cell (h3, resolution, parent_res5, parent_res4, parent_res3,
                              centroid_lat, centroid_lon, elevation_m, canopy_pct, state_fips)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
                elevation_m = COALESCE(VALUES(elevation_m), elevation_m),
                canopy_pct  = COALESCE(VALUES(canopy_pct),  canopy_pct),
                state_fips  = VALUES(state_fips)
        """.trimIndent()

        val counts = jdbc.batchUpdate(sql, cells, cells.size) { ps: PreparedStatement, c: Cell ->
            ps.setLong(1, c.h3)
            ps.setInt(2, c.resolution)
            ps.setLong(3, c.parentRes5)
            ps.setLong(4, c.parentRes4)
            ps.setLong(5, c.parentRes3)
            ps.setDouble(6, c.centroidLat)
            ps.setDouble(7, c.centroidLon)
            c.elevationM?.let { ps.setInt(8, it) } ?: ps.setNull(8, Types.SMALLINT)
            c.canopyPct?.let { ps.setInt(9, it) } ?: ps.setNull(9, Types.TINYINT)
            ps.setString(10, c.stateFips)
        }
        return counts.sumOf { it.size }
    }

    fun countByState(stateFips: String): Long =
        jdbc.queryForObject("SELECT COUNT(*) FROM cell WHERE state_fips = ?", Long::class.java, stateFips) ?: 0

    /** Forested cells in a state, as raw H3 indexes for the map. */
    fun forestedCells(stateFips: String, minCanopyPct: Int): List<Long> =
        jdbc.queryForList(
            "SELECT h3 FROM cell WHERE state_fips = ? AND canopy_pct >= ? ORDER BY h3",
            Long::class.java, stateFips, minCanopyPct,
        )

    /** Full cell rows for a state, optionally masked to forest. */
    fun findByState(stateFips: String, minCanopyPct: Int): List<Cell> = jdbc.query(
        """
        SELECT h3, resolution, parent_res5, parent_res4, parent_res3,
               centroid_lat, centroid_lon, elevation_m, canopy_pct, state_fips
        FROM cell
        WHERE state_fips = ? AND (canopy_pct IS NULL OR canopy_pct >= ?)
        ORDER BY h3
        """.trimIndent(),
        { rs, _ ->
            Cell(
                h3 = rs.getLong("h3"),
                resolution = rs.getInt("resolution"),
                parentRes5 = rs.getLong("parent_res5"),
                parentRes4 = rs.getLong("parent_res4"),
                parentRes3 = rs.getLong("parent_res3"),
                centroidLat = rs.getDouble("centroid_lat"),
                centroidLon = rs.getDouble("centroid_lon"),
                elevationM = rs.getInt("elevation_m").takeUnless { rs.wasNull() },
                canopyPct = rs.getInt("canopy_pct").takeUnless { rs.wasNull() },
                stateFips = rs.getString("state_fips"),
            )
        },
        stateFips, minCanopyPct,
    )

    fun canopyHistogram(stateFips: String): Map<String, Long> = jdbc.query(
        """
        SELECT CASE WHEN canopy_pct IS NULL THEN 'unsampled'
                    WHEN canopy_pct = 0 THEN '0'
                    WHEN canopy_pct < 25 THEN '1-24'
                    WHEN canopy_pct < 50 THEN '25-49'
                    WHEN canopy_pct < 75 THEN '50-74'
                    ELSE '75-100' END AS bucket,
               COUNT(*) AS n
        FROM cell WHERE state_fips = ? GROUP BY bucket
        """.trimIndent(),
        { rs, _ -> rs.getString("bucket") to rs.getLong("n") },
        stateFips,
    ).toMap()
}
