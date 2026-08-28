package com.foliage.persistence

import com.foliage.domain.Cell
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
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
                              centroid_lat, centroid_lon, elevation_m, canopy_pct,
                              state_fips, state_name)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
                elevation_m = COALESCE(VALUES(elevation_m), elevation_m),
                canopy_pct  = COALESCE(VALUES(canopy_pct),  canopy_pct),
                state_fips  = VALUES(state_fips),
                state_name  = COALESCE(VALUES(state_name), state_name)
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
            c.stateName?.let { ps.setString(11, it) } ?: ps.setNull(11, Types.VARCHAR)
        }
        return counts.sumOf { it.size }
    }

    /**
     * Cells already loaded for a state, looked up by name rather than FIPS.
     *
     * The bootstrap knows state names because that is what the boundary
     * service queries on; it only learns the FIPS after fetching the outline,
     * which is the expensive part it is trying to skip.
     */
    fun countByStateName(stateName: String): Long = jdbc.queryForObject(
        "SELECT COUNT(*) FROM cell WHERE state_name = ?",
        Long::class.java, stateName,
    ) ?: 0

    /**
     * Cells in a state that are missing terrain.
     *
     * Presence of rows is not evidence a state finished. Terrain sources
     * degrade rather than abort -- a tile that times out after its retries
     * leaves its cells unsampled and the run continues -- so a state can be
     * written, counted, and still be full of holes. The CONUS load ended with
     * Oregon and California at ~23% of their canopy samples lost to a service
     * that had been under load for two hours, and both looked complete to a
     * check that only counted rows.
     *
     * This is what makes a re-run converge instead of skipping the damage.
     */
    fun countIncompleteByStateName(stateName: String): Long = jdbc.queryForObject(
        "SELECT COUNT(*) FROM cell WHERE state_name = ? AND (canopy_pct IS NULL OR elevation_m IS NULL)",
        Long::class.java, stateName,
    ) ?: 0

    /** Every cell in the grid, across all loaded states. */
    fun findAll(minCanopyPct: Int): List<Cell> = jdbc.query(
        "$selectCell WHERE canopy_pct IS NULL OR canopy_pct >= ? ORDER BY h3",
        cellMapper, minCanopyPct,
    )

    fun countAll(): Long =
        jdbc.queryForObject("SELECT COUNT(*) FROM cell", Long::class.java) ?: 0

    /** Distinct res 5 ancestors across the whole grid. */
    fun allRes5Parents(): List<Long> = jdbc.queryForList(
        "SELECT DISTINCT parent_res5 FROM cell ORDER BY parent_res5",
        Long::class.java,
    )

    fun countByState(stateFips: String): Long =
        jdbc.queryForObject("SELECT COUNT(*) FROM cell WHERE state_fips = ?", Long::class.java, stateFips) ?: 0

    /** Forested cells in a state, as raw H3 indexes for the map. */
    fun forestedCells(stateFips: String, minCanopyPct: Int): List<Long> =
        jdbc.queryForList(
            "SELECT h3 FROM cell WHERE state_fips = ? AND canopy_pct >= ? ORDER BY h3",
            Long::class.java, stateFips, minCanopyPct,
        )

    private val selectCell = """
        SELECT h3, resolution, parent_res5, parent_res4, parent_res3,
               centroid_lat, centroid_lon, elevation_m, canopy_pct,
               state_fips, state_name
        FROM cell
    """.trimIndent()

    private val cellMapper = RowMapper { rs, _ ->
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
            stateName = rs.getString("state_name"),
        )
    }

    /** Full cell rows for a state, optionally masked to forest. */
    fun findByState(stateFips: String, minCanopyPct: Int): List<Cell> = jdbc.query(
        "$selectCell WHERE state_fips = ? AND (canopy_pct IS NULL OR canopy_pct >= ?) ORDER BY h3",
        cellMapper, stateFips, minCanopyPct,
    )

    fun findByH3(h3: Long): Cell? =
        jdbc.query("$selectCell WHERE h3 = ?", cellMapper, h3).firstOrNull()

    /** Sibling cells sharing a res 5 parent, for computing its reference elevation. */
    fun findByParent(parentRes5: Long): List<Cell> =
        jdbc.query("$selectCell WHERE parent_res5 = ?", cellMapper, parentRes5)

    /**
     * Distinct res 5 ancestors of a state's cells.
     *
     * Weather is fetched at res 5 because that is what Open-Meteo is natively
     * accurate at; roughly seven res 6 cells share each one, so this cuts the
     * number of API calls by about 7x with no loss of real information.
     */
    fun distinctRes5Parents(stateFips: String): List<Long> = jdbc.queryForList(
        "SELECT DISTINCT parent_res5 FROM cell WHERE state_fips = ? ORDER BY parent_res5",
        Long::class.java, stateFips,
    )

    /**
     * Canopy distribution across the whole grid.
     *
     * The grid stores every tiled cell, not only forested ones, so the
     * threshold can be retuned without re-sampling. That makes "how many cells
     * are there" a poor guide to how much work a national forecast is: scoring
     * and exporting are only meaningful above the canopy floor, and the gap
     * between the two numbers is the size of the waste. See docs/data-sources.md.
     */
    fun canopyHistogram(): Map<String, Long> = jdbc.query(
        """
        SELECT CASE WHEN canopy_pct IS NULL THEN 'unsampled'
                    WHEN canopy_pct = 0 THEN '0'
                    WHEN canopy_pct < 25 THEN '1-24'
                    WHEN canopy_pct < 50 THEN '25-49'
                    WHEN canopy_pct < 75 THEN '50-74'
                    ELSE '75-100' END AS bucket,
               COUNT(*) AS n
        FROM cell GROUP BY bucket
        """.trimIndent(),
        { rs, _ -> rs.getString("bucket") to rs.getLong("n") },
    ).toMap()

    /** Cells at or above the forest threshold -- what a forecast actually covers. */
    fun countForested(minCanopyPct: Int): Long = jdbc.queryForObject(
        "SELECT COUNT(*) FROM cell WHERE canopy_pct >= ?",
        Long::class.java, minCanopyPct,
    ) ?: 0

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
