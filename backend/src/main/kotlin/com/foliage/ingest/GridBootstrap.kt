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
                if (existing > 0 && !force) {
                    log.info("{} already has {} cells, skipping", state, existing)
                    skipped += state
                    continue
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

            var elevations: List<Int?>
            val elevMs = measureTimeMillis { elevations = elevation.elevation(tiled.map { grid.centroid(it) }) }
            log.info("sampled elevation at {} centroids in {} ms", tiled.size, elevMs)

            val rows = tiled.mapIndexed { i, h3 ->
                val centroid = grid.centroid(h3)
                Cell(
                    h3 = h3,
                    resolution = resolution,
                    parentRes5 = grid.parent(h3, 5),
                    parentRes4 = grid.parent(h3, 4),
                    parentRes3 = grid.parent(h3, 3),
                    centroidLat = centroid.lat,
                    centroidLon = centroid.lon,
                    elevationM = elevations.getOrNull(i),
                    canopyPct = CellSampling.average(
                        canopyValues.subList(i * perCell, minOf((i + 1) * perCell, canopyValues.size)),
                    ),
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
