package com.foliage.ingest.terrain

import com.foliage.grid.LonLat
import com.foliage.ingest.RetryPolicy
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient

/**
 * FIA forest type group at a set of points.
 *
 * An interface for the same reason [CanopySource] is one: the useful property
 * of this project's ingest is that a source can be replaced without anything
 * downstream noticing. The values are FIA group codes, not a measurement --
 * see [com.foliage.forecast.ForestTypeGroup].
 */
interface ForestTypeSource {

    /**
     * FIA group code for each point, **in input order**. `null` where the
     * service returned nothing at all; `0` where it returned no-data, which is
     * a different statement and is preserved as-is for [CellSampling] to judge.
     */
    fun sample(points: List<LonLat>): List<Int?>
}

/**
 * Forest type read from the USFS BIGMAP CONUS raster, tile by tile.
 *
 * Deliberately a near-copy of [NlcdTileCanopySource]. Both read a 30 m
 * single-band raster from the same ArcGIS host with the same exportImage
 * call, so this reuses [RasterGrid] unchanged and inherits the two traps that
 * source documents the hard way: **tiff, never PNG**, because PNG comes back
 * as rendered RGBA with a colour ramp applied and its values are not codes at
 * all; and **native resolution**, because at coarser pixel sizes the service
 * answers from a pyramid overview. Both were confirmed here before this was
 * written -- point queries without a pixel size returned 0 almost everywhere,
 * including places that are unambiguously forest.
 *
 * The one real departure is what a value *means*. Canopy is a percentage and
 * averages; a group code is an identifier and must not be averaged. Nothing in
 * this class aggregates, and [CellSampling.dominantType] takes the mode.
 *
 * Validated against the places whose species residuals are recorded in
 * docs/model.md, using a dataset the model has never seen: Ely reads 900
 * aspen/birch, Stowe 800 maple/beech/birch, Litchfield 500 oak/hickory --
 * exactly the forests those errors implied.
 */
@Component
class BigMapForestTypeSource(
    private val restClient: RestClient,
    @Value("\${foliage.terrain.forest-type-url}") private val url: String,
    @Value("\${foliage.terrain.canopy-tile-threads}") private val threads: Int,
) : ForestTypeSource {

    private val log = LoggerFactory.getLogger(javaClass)
    private val retry = RetryPolicy(maxAttempts = 3, initialBackoffMs = 5_000)

    override fun sample(points: List<LonLat>): List<Int?> {
        if (points.isEmpty()) return emptyList()

        val located = points.map { RasterGrid.locate(it) }
        val byTile = located.indices.groupBy { located[it].tile }

        log.info("forest type: {} points across {} tiles", points.size, byTile.size)
        val out = arrayOfNulls<Int>(points.size)
        fetchAndSample(byTile, located, out)

        val missed = out.count { it == null }
        if (missed > 0) log.warn("forest type: {} of {} points unresolved", missed, points.size)
        return out.toList()
    }

    /**
     * Sampling happens inside the fetch, as it does for canopy: a decoded tile
     * is finished with the moment its points are read, so peak memory stays
     * bounded by `threads x tile size` however much ground a call covers.
     */
    private fun fetchAndSample(
        byTile: Map<RasterTile, List<Int>>,
        located: List<RasterPixel>,
        out: Array<Int?>,
    ) {
        val progress = AtomicInteger()
        val pool = Executors.newFixedThreadPool(threads.coerceIn(1, 8))
        try {
            byTile.map { (tile, positions) ->
                pool.submit {
                    runCatching {
                        retry.execute("forest tile ${tile.west},${tile.south}", { true }) { export(tile) }
                    }.onSuccess { image ->
                        if (image != null) {
                            for (i in positions) {
                                val at = located[i]
                                // No clamping. A code is an identifier, and
                                // coercing an unexpected one into a range
                                // would invent a forest type rather than
                                // reveal that the raster returned something
                                // this code does not know about.
                                out[i] = image.raster.getSample(at.px, at.py, 0)
                            }
                        }
                    }.onFailure {
                        // Degrade rather than abort, as every terrain source
                        // does: those cells stay unsampled, score at the
                        // maple-beech baseline, and a later run fills them.
                        log.warn("forest tile {},{} failed: {}", tile.west, tile.south, it.message)
                    }
                    val n = progress.incrementAndGet()
                    if (n % 25 == 0 || n == byTile.size) log.info("forest type: {}/{} tiles", n, byTile.size)
                }
            }.forEach { it.get() }
        } finally {
            pool.shutdown()
            pool.awaitTermination(60, TimeUnit.SECONDS)
        }
    }

    private fun export(tile: RasterTile): BufferedImage? {
        val uri = "$url/exportImage?bbox=${tile.bbox()}&bboxSR=4326&imageSR=4326" +
            "&size=${RasterGrid.PIXELS},${RasterGrid.PIXELS}" +
            "&format=tiff&interpolation=RSP_NearestNeighbor&f=image"

        val bytes = restClient.get().uri(uri).retrieve().body(ByteArray::class.java) ?: return null
        return ImageIO.read(ByteArrayInputStream(bytes))
    }
}
