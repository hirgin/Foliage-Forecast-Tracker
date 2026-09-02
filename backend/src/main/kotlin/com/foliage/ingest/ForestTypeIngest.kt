package com.foliage.ingest

import com.foliage.grid.H3Grid
import com.foliage.ingest.terrain.CellSampling
import com.foliage.ingest.terrain.ForestTypeSource
import com.foliage.persistence.CellRepository
import java.time.Duration
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/** What one sampling pass managed. */
data class ForestTypeResult(
    val stateFips: String,
    val cellsSampled: Int,
    val cellsRemaining: Int,
    val classified: Int,
    val unclassified: Int,
    val stoppedOnTime: Boolean,
)

/**
 * Fills in each cell's forest type from the BIGMAP raster.
 *
 * Offline and resumable, like every other ingest here, and for the same
 * reason: 141,274 cells at seven sample points each is roughly a million
 * lookups, which is not one request and not one run. Cells are claimed by
 * "forest_type_group IS NULL" and written as they are read, so a pass that
 * dies partway leaves its work behind and the next one continues from there.
 *
 * **Nothing here is on the request path and nothing blocks scoring.** A cell
 * without a type scores at the maple-beech baseline, exactly as the whole map
 * did before this existed, so the species term improves the map gradually as
 * the grid fills rather than switching on all at once. That is deliberate: it
 * makes the term's effect attributable, because at any moment the difference
 * between a sampled and an unsampled cell is the term itself.
 */
@Service
class ForestTypeIngest(
    private val cells: CellRepository,
    private val grid: H3Grid,
    private val source: ForestTypeSource,
    private val recorder: com.foliage.ingest.audit.IngestRunRecorder,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Cells per pass. Sized so one batch's sample points fall in a manageable
     * number of raster tiles -- the tile fetch is the cost, not the cells.
     */
    private val BATCH = 500

    fun run(stateFips: String, maxCells: Int, budget: Duration): ForestTypeResult {
        val deadline = Instant.now().plus(budget)
        var sampled = 0
        var classified = 0
        var unclassified = 0
        var stoppedOnTime = false

        val run = recorder.start("usfs-bigmap/forest-type", stateFips)
        try {
            while (sampled < maxCells) {
                if (Instant.now().isAfter(deadline)) {
                    stoppedOnTime = true
                    break
                }
                val batch = cells.withoutForestType(stateFips, minOf(BATCH, maxCells - sampled))
                if (batch.isEmpty()) break

                // Every cell's sample points in one flat list, so the source
                // can group them by raster tile across the whole batch. Doing
                // this per cell would fetch the same tile once per cell.
                val perCell = batch.map { CellSampling.points(grid, it.h3) }
                val flat = perCell.flatten()
                val values = source.sample(flat)

                var at = 0
                val types = HashMap<Long, Int?>(batch.size)
                for ((i, cell) in batch.withIndex()) {
                    val n = perCell[i].size
                    val slice = values.subList(at, at + n)
                    at += n
                    val type = CellSampling.dominantType(slice)
                    types[cell.h3] = type
                    if (type == null) unclassified++ else classified++
                }

                cells.saveForestTypes(types)
                sampled += batch.size
                log.info(
                    "forest type {}: {} cells sampled, {} classified, {} with no forest",
                    stateFips, sampled, classified, unclassified,
                )
            }
            recorder.succeed(run, sampled.toLong())
        } catch (e: Exception) {
            recorder.fail(run, sampled.toLong(), e)
            throw e
        }

        return ForestTypeResult(
            stateFips = stateFips,
            cellsSampled = sampled,
            cellsRemaining = runCatching { cells.forestTypeRemaining(stateFips) }.getOrDefault(-1),
            classified = classified,
            unclassified = unclassified,
            stoppedOnTime = stoppedOnTime,
        )
    }
}
