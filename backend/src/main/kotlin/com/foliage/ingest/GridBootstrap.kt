package com.foliage.ingest

import com.foliage.domain.Cell
import com.foliage.grid.ConusStates
import com.foliage.grid.H3Grid
import com.foliage.ingest.audit.IngestRunRecorder
import com.foliage.ingest.terrain.BoundarySource
import com.foliage.ingest.terrain.CanopySource
import com.foliage.ingest.terrain.CellSampling
import com.foliage.ingest.terrain.ElevationSource
import com.foliage.persistence.CellRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import kotlin.system.measureTimeMillis

/**
 * Builds the forecast grid for one state: tile, enrich with terrain, persist.
 *
 * Run once per state. It is idempotent -- re-running converges rather than
 * duplicating -- so a run interrupted halfway can simply be repeated.
 */
@Service
class GridBootstrap(
    private val grid: H3Grid,
    private val boundaries: BoundarySource,
    private val canopy: CanopySource,
    private val elevation: ElevationSource,
    private val cells: CellRepository,
    private val audit: IngestRunRecorder,
    @Value("\${foliage.grid.resolution}") private val resolution: Int,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Bootstraps a whole region, one state at a time.
     *
     * States already carrying cells are skipped unless [force] is set.
     *
     * That resumability used to be the difference between a feasible and an
     * infeasible run: point sampling put CONUS at roughly 71 hours of elevation
     * and 10 hours of canopy. Both now read bulk rasters instead and the whole
     * country is minutes, but the skip logic stays -- a run that dies partway
     * still should not redo the states it finished, and it is what makes
     * bootstrapping a region at a time safe.
     */
    fun bootstrapRegion(region: String, force: Boolean = false): RegionBootstrapResult {
        val states = ConusStates.resolve(region)
        val done = mutableListOf<GridBootstrapResult>()
        val skipped = mutableListOf<String>()
        val failed = mutableMapOf<String, String>()

        val elapsed = measureTimeMillis {
            for (state in states) {
                val existing = runCatching { cells.countByStateName(state) }.getOrDefault(0L)
                // Complete, not merely present. Terrain sources degrade rather
                // than abort, so a state can hold rows and still be missing
                // terrain for a quarter of them -- which is exactly how the
                // first CONUS load left Oregon and California. Skipping on row
                // count alone would make those holes permanent, because every
                // later run would see cells and move on.
                val gaps = runCatching { cells.countIncompleteByStateName(state) }.getOrDefault(0L)
                if (existing > 0 && gaps == 0L && !force) {
                    log.info("{} already has {} complete cells, skipping", state, existing)
                    skipped += state
                    continue
                }
                if (gaps > 0) {
                    log.info("{} has {} of {} cells missing terrain, re-sampling", state, gaps, existing)
                }
                try {
                    done += bootstrapState(state)
                } catch (e: Exception) {
                    // One bad state must not abandon the rest of a multi-hour
                    // run; it is recorded and can be retried on its own.
                    log.error("{} failed: {}", state, e.message)
                    failed[state] = e.message ?: e::class.simpleName.orEmpty()
                }
            }
        }

        return RegionBootstrapResult(
            region = region,
            statesRequested = states.size,
            statesBootstrapped = done.size,
            statesSkipped = skipped,
            statesFailed = failed,
            cellsAdded = done.sumOf { it.cellsTiled },
            elapsedMs = elapsed,
        )
    }

    /**
     * Re-derives elevation for a state that is already tiled, leaving canopy
     * and the tiling alone.
     *
     * Exists because the two attributes have very different costs and very
     * different reasons to change. Canopy is the slow half -- 13 MB tiles
     * rendered on demand, minutes per state -- and it is the half that almost
     * never needs redoing. Elevation reads a CDN and takes about a second per
     * state.
     *
     * When the bathymetry bug was found, re-running the full bootstrap for the
     * eleven affected states would have re-fetched every canopy tile to change
     * one column: roughly two hours against about two minutes. Correcting one
     * derived attribute should not cost a full re-sample.
     *
     * Idempotent like the bootstrap it complements: it reads the cells that
     * exist and upserts them back with a new elevation.
     */
    fun refreshElevation(stateFips: String): ElevationRefreshResult {
        val runId = audit.start(source = "terrarium", job = "elevation-refresh:$stateFips")
        var written = 0L
        try {
            val existing = cells.findByState(stateFips, 0)
            require(existing.isNotEmpty()) { "no cells loaded for state $stateFips" }

            // Same seven points per cell the canopy sampling uses, so a coastal
            // cell whose centroid sits offshore still has land to average.
            val points = existing.flatMap { CellSampling.points(grid, it.h3) }
            val perCell = points.size / existing.size

            var samples: List<Int?>
            val ms = measureTimeMillis { samples = elevation.elevation(points) }
            log.info("re-sampled elevation at {} points in {} ms", points.size, ms)

            val before = existing.count { (it.elevationM ?: 0) < 0 }
            val rows = existing.mapIndexed { i, cell ->
                cell.copy(
                    elevationM = CellSampling.landElevation(
                        samples.subList(i * perCell, minOf((i + 1) * perCell, samples.size)),
                        hasCanopy = (cell.canopyPct ?: 0) > 0,
                    ),
                )
            }
            val after = rows.count { (it.elevationM ?: 0) < 0 }

            written = cells.upsertAll(rows).toLong()
            audit.succeed(runId, written)

            return ElevationRefreshResult(
                stateFips = stateFips,
                cells = rows.size,
                rowsWritten = written,
                belowSeaLevelBefore = before,
                belowSeaLevelAfter = after,
                elapsedMs = ms,
            )
        } catch (e: Exception) {
            audit.fail(runId, written, e)
            throw e
        }
    }

    fun bootstrapState(stateName: String): GridBootstrapResult {
        val runId = audit.start(source = "tigerweb+nlcd-tiles+terrarium", job = "grid-bootstrap:$stateName")
        var written = 0L
        try {
            val boundary = boundaries.stateBoundary(stateName)
            log.info("{} (FIPS {}): {} polygon(s)", boundary.name, boundary.fips, boundary.polygons.size)

            // Distinct: adjacent polygons of a MultiPolygon can both claim a
            // cell whose centre sits near their shared edge.
            val tiled = boundary.polygons.flatMap { grid.tile(it, resolution) }.distinct()
            log.info("tiled {} into {} res {} cells", boundary.name, tiled.size, resolution)

            // One flat point list for the whole state, so batching is bounded
            // by the transport rather than by cell count.
            val samplePoints = tiled.flatMap { CellSampling.points(grid, it) }
            val perCell = samplePoints.size / tiled.size

            var canopyValues: List<Int?>
            val canopyMs = measureTimeMillis { canopyValues = canopy.sample(samplePoints) }
            log.info("sampled canopy at {} points ({} per cell) in {} ms", samplePoints.size, perCell, canopyMs)

            // Elevation is sampled at the same seven points as canopy, not at
            // the centroid alone. Terrain tiles include bathymetry, so a
            // coastal cell whose centre falls offshore read the seabed -- see
            // CellSampling.landElevation. Sampling more points costs almost
            // nothing now that this reads tiles rather than one request per
            // point: the extra samples fall inside tiles already fetched.
            var elevationSamples: List<Int?>
            val elevMs = measureTimeMillis { elevationSamples = elevation.elevation(samplePoints) }
            log.info("sampled elevation at {} points in {} ms", samplePoints.size, elevMs)

            val rows = tiled.mapIndexed { i, h3 ->
                val centroid = grid.centroid(h3)
                val canopyForCell = CellSampling.average(
                    canopyValues.subList(i * perCell, minOf((i + 1) * perCell, canopyValues.size)),
                )
                Cell(
                    h3 = h3,
                    resolution = resolution,
                    parentRes5 = grid.parent(h3, 5),
                    parentRes4 = grid.parent(h3, 4),
                    parentRes3 = grid.parent(h3, 3),
                    centroidLat = centroid.lat,
                    centroidLon = centroid.lon,
                    elevationM = CellSampling.landElevation(
                        elevationSamples.subList(i * perCell, minOf((i + 1) * perCell, elevationSamples.size)),
                        hasCanopy = (canopyForCell ?: 0) > 0,
                    ),
                    canopyPct = canopyForCell,
                    stateFips = boundary.fips,
                    stateName = boundary.name,
                )
            }

            written = cells.upsertAll(rows).toLong()
            audit.succeed(runId, written)

            return GridBootstrapResult(
                state = boundary.name,
                stateFips = boundary.fips,
                cellsTiled = tiled.size,
                rowsWritten = written,
                canopySampled = rows.count { it.canopyPct != null },
                elevationSampled = rows.count { it.elevationM != null },
                canopyHistogram = cells.canopyHistogram(boundary.fips),
            )
        } catch (e: Exception) {
            audit.fail(runId, written, e)
            throw e
        }
    }
}

data class RegionBootstrapResult(
    val region: String,
    val statesRequested: Int,
    val statesBootstrapped: Int,
    val statesSkipped: List<String>,
    val statesFailed: Map<String, String>,
    val cellsAdded: Int,
    val elapsedMs: Long,
)

data class GridBootstrapResult(
    val state: String,
    val stateFips: String,
    val cellsTiled: Int,
    val rowsWritten: Long,
    val canopySampled: Int,
    val elevationSampled: Int,
    val canopyHistogram: Map<String, Long>,
)

data class ElevationRefreshResult(
    val stateFips: String,
    val cells: Int,
    val rowsWritten: Long,
    /** Cells reading below sea level before and after; the point of the run. */
    val belowSeaLevelBefore: Int,
    val belowSeaLevelAfter: Int,
    val elapsedMs: Long,
)
