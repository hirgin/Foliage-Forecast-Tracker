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

        val counts = jdbc.batchUpdate(sql, cells, JDBC_BATCH_SIZE) { ps: PreparedStatement, c: Cell ->
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

    /**
     * Cells worth forecasting: forest, plus the built-up parts of major metros.
     *
     * The canopy floor alone left every city centre off the map. Boston's own
     * hexagon is 2% canopy, so the place people most often ask about in New
     * England simply was not there. Street trees and park maples do turn, and
     * on the same schedule as the woods outside town, so a forecast is
     * meaningful even where the canopy is thin.
     *
     * Lowering the floor everywhere was the wrong fix: at 10% it pulls in the
     * western pinyon-juniper belt, which is evergreen and has no foliage
     * season at all. The exception is therefore keyed on population, not on a
     * looser canopy rule.
     *
     * Matched by res 5 parent rather than by the single hexagon holding a
     * city's centre point, so a metro arrives as a patch roughly 11 km across
     * instead of one lonely cell in a hole.
     */
    /**
     * Forests that can show autumn colour, as a SQL predicate.
     *
     * FIA numbers softwood groups 100-399 and hardwoods 400 and above, so this
     * is a range rather than a list -- which matters because the stored values
     * are individual forest *types*, not only group codes, and no list of
     * groups would catch 128 or 257.
     *
     * Three exceptions. Western larch (320-329) is a conifer that drops its
     * needles and goes bright gold, and western Montana's larch season is one
     * people travel for. NULL means "not surveyed", which must behave exactly
     * as it did before any of this existed.
     *
     * And 0 -- "surveyed, no forest type found" -- is kept, which is the
     * correction that matters. Excluding it emptied 26,310 hexagons that have
     * trees: every one passed the canopy floor, meaning NLCD measured tree
     * cover over it, and then failed a survey that reads seven 30 m points in
     * a 3 km cell. Scattered woodland loses that lottery routinely. The canopy
     * raster averages the same ground properly and is the better witness, so a
     * cell it says has trees keeps its place on the map and scores at the
     * baseline, exactly like one nobody has surveyed.
     */
    private val displaysColour =
        "(forest_type_group IS NULL OR forest_type_group = 0 " +
            "OR forest_type_group >= 400 OR forest_type_group BETWEEN 320 AND 329)"

    /**
     * @param foliageOnly drops forests that never change colour.
     *
     * Off for scoring and on for the map, and the difference is not an
     * inconsistency. Scoring an evergreen is cheap and harmless -- it comes out
     * NO_CHANGE, which is true. Drawing one is not, because the map aggregates:
     * at national zoom a ~22 km hexagon averages its children, and averaging a
     * spruce that never turns with a maple that has finished turning produces a
     * hexagon that is permanently half-way through autumn. That is how a
     * December map came to show Maine and Montana at "partial" -- not a scoring
     * error at all, but an average over cells that had no business being in it.
     *
     * Excluded cells still render, as the faded ground the legend already
     * describes. An evergreen forest is forest; it simply is not foliage.
     */
    fun findAll(
        minCanopyPct: Int,
        metroPopulation: Int = Int.MAX_VALUE,
        foliageOnly: Boolean = false,
    ): List<Cell> = jdbc.query(
        "$selectCell WHERE " + (if (foliageOnly) "$displaysColour AND " else "") +
            "(canopy_pct IS NULL OR canopy_pct >= ? " +
            "OR parent_res5 IN (SELECT DISTINCT c2.parent_res5 FROM place p " +
            "JOIN cell c2 ON c2.h3 = p.h3 WHERE p.population >= ?)) ORDER BY h3",
        cellMapper, minCanopyPct, metroPopulation,
    )

    /** Diagnostic: how many drawable cells a given predicate would remove. */
    fun countExcludedBy(predicate: String, minCanopyPct: Int, metroPopulation: Int): Int =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM cell WHERE ($predicate) AND (canopy_pct IS NULL OR canopy_pct >= ? " +
                "OR parent_res5 IN (SELECT DISTINCT c2.parent_res5 FROM place p " +
                "JOIN cell c2 ON c2.h3 = p.h3 WHERE p.population >= ?))",
            Int::class.java, minCanopyPct, metroPopulation,
        ) ?: 0

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
               state_fips, state_name, forest_type_group
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
            forestTypeGroup = rs.getInt("forest_type_group").takeUnless { rs.wasNull() },
        )
    }

    /**
     * Cells in a state that still have no forest type, centroid-first.
     *
     * Ordered by h3 so a run that stops partway and a run that resumes cover
     * the grid in the same sequence, which is what makes the job resumable in
     * the same sense every other ingest here is.
     */
    fun withoutForestType(stateFips: String, limit: Int): List<Cell> = jdbc.query(
        "$selectCell WHERE state_fips = ? AND forest_type_group IS NULL ORDER BY h3 LIMIT ?",
        cellMapper, stateFips, limit,
    )

    /**
     * How a state's sampled cells break down by forest type.
     *
     * Operational rather than decorative: the species term only does anything
     * for cells it actually classified, so "how much of this state is surveyed
     * as what" is the difference between a term that works and one that is
     * quietly inert over most of the map.
     */
    fun forestTypeBreakdown(stateFips: String): Map<Int, Int> = jdbc.query(
        """
        SELECT COALESCE(forest_type_group, -1) AS grp, COUNT(*) AS n
        FROM cell WHERE state_fips = ?
        GROUP BY COALESCE(forest_type_group, -1)
        ORDER BY n DESC
        """.trimIndent(),
        { rs, _ -> rs.getInt("grp") to rs.getInt("n") },
        stateFips,
    ).toMap()

    /** How many of a state's cells still need sampling. */
    fun forestTypeRemaining(stateFips: String): Int = jdbc.queryForObject(
        "SELECT COUNT(*) FROM cell WHERE state_fips = ? AND forest_type_group IS NULL",
        Int::class.java, stateFips,
    ) ?: 0

    /**
     * Stores sampled forest types, in one batched statement.
     *
     * Writes 0 rather than NULL for a cell with no forest to classify. The
     * distinction matters operationally: NULL means "not yet sampled" and the
     * job would pick it up again on every run forever, so a cell that genuinely
     * has no forest has to be able to say so. Both read as the maple-beech
     * baseline at scoring time.
     */
    fun saveForestTypes(types: Map<Long, Int?>) {
        if (types.isEmpty()) return
        val rows = types.entries.toList()
        jdbc.batchUpdate(
            "UPDATE cell SET forest_type_group = ? WHERE h3 = ?",
            rows,
            rows.size,
        ) { ps, entry ->
            ps.setInt(1, entry.value ?: 0)
            ps.setLong(2, entry.key)
        }
    }

    /** Full cell rows for a state, optionally masked to forest. */
    /**
     * One state's cells worth forecasting. Same rule as [findAll], and it has
     * to stay the same: scoring runs per state while the export runs
     * nationally, so a cell selected by one and not the other would be
     * published with no forecast behind it forever.
     */
    fun findByState(
        stateFips: String,
        minCanopyPct: Int,
        metroPopulation: Int = Int.MAX_VALUE,
        foliageOnly: Boolean = false,
    ): List<Cell> = jdbc.query(
        "$selectCell WHERE state_fips = ? AND " +
            (if (foliageOnly) "$displaysColour AND " else "") +
            "(canopy_pct IS NULL OR canopy_pct >= ? " +
            "OR parent_res5 IN (SELECT DISTINCT c2.parent_res5 FROM place p " +
            "JOIN cell c2 ON c2.h3 = p.h3 WHERE p.population >= ?)) ORDER BY h3",
        cellMapper, stateFips, minCanopyPct, metroPopulation,
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
    /**
     * The FIPS code a state name was tiled under, or null if it was not.
     *
     * The backfill works from state *names*, because that is the order
     * ConusStates expresses foliage priority in, but every ingest job keys on
     * FIPS. Looking it up from the grid avoids a second hardcoded mapping that
     * could drift from the one the bootstrap used.
     */
    fun stateFipsFor(stateName: String): String? = jdbc.query(
        "SELECT state_fips FROM cell WHERE state_name = ? LIMIT 1",
        { rs, _ -> rs.getString("state_fips") },
        stateName,
    ).firstOrNull()

    /**
     * Res 5 parents that actually carry cells worth forecasting, grouped under
     * their res 4 grandparent.
     *
     * Two savings over taking every parent in a state, both large.
     *
     * **Only parents with scoreable cells.** The plain parent list includes
     * ground that is neither forest nor city, so weather was being fetched for
     * 31,476 parents to serve 19,904 that are ever scored.
     *
     * **Grouped by res 4, because that is the resolution the source actually
     * has.** Open-Meteo's archive is ERA5 at 9-28 km; a res 5 cell is about 8
     * km, so fetching one reading per res 5 parent asks the same grid square
     * for the same numbers up to seven times. Res 4 is around 22 km, inside
     * the source's own resolution, and the elevation detail res 5 appeared to
     * add is recovered by the lapse-rate downscale anyway -- which is the
     * whole argument for scoring at res 6 in the first place. See ADR-0005.
     */
    fun scoreableParentsByRes4(
        stateFips: String?,
        minCanopyPct: Int,
        metroPopulation: Int,
    ): Map<Long, List<Long>> {
        val where = StringBuilder(
            "WHERE (canopy_pct IS NULL OR canopy_pct >= ? " +
                "OR parent_res5 IN (SELECT DISTINCT c2.parent_res5 FROM place p " +
                "JOIN cell c2 ON c2.h3 = p.h3 WHERE p.population >= ?))",
        )
        val args = mutableListOf<Any>(minCanopyPct, metroPopulation)
        if (stateFips != null) {
            where.append(" AND state_fips = ?")
            args += stateFips
        }
        val out = HashMap<Long, MutableSet<Long>>()
        jdbc.query(
            "SELECT DISTINCT parent_res4, parent_res5 FROM cell $where",
            { rs, _ -> rs.getLong("parent_res4") to rs.getLong("parent_res5") },
            *args.toTypedArray(),
        ).forEach { (res4, res5) -> out.getOrPut(res4) { LinkedHashSet() }.add(res5) }
        return out.mapValues { it.value.toList() }
    }

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

    /**
     * Distinct res 5 parents at or above a canopy floor.
     *
     * This is what sizes weather ingest, and it is not the cell count divided
     * by seven: forested cells are clustered, so a parent is often only
     * partly forested and still has to be fetched. Vermont's 649 cells sit
     * under 110 parents, a spread of 5.9 rather than 7.
     */
    fun countRes5Parents(minCanopyPct: Int): Long = jdbc.queryForObject(
        "SELECT COUNT(DISTINCT parent_res5) FROM cell WHERE canopy_pct >= ?",
        Long::class.java, minCanopyPct,
    ) ?: 0

    /** Every res 5 parent in the grid, forested or not. */
    fun countRes5ParentsAll(): Long = jdbc.queryForObject(
        "SELECT COUNT(DISTINCT parent_res5) FROM cell", Long::class.java,
    ) ?: 0

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
