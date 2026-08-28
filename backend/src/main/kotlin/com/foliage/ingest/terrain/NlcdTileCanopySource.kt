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
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient

/**
 * Canopy cover read as raster tiles rather than sampled point by point.
 *
 * The interface it implements predicted this swap: point sampling is ideal at
 * state scale but will not scale to a full CONUS grid. It does not -- 1.56
 * million sample points is 6,264 batched requests and about ten hours.
 * exportImage returns pixels for a bounding box, so cells are grouped by the
 * degree tile they fall in and each tile is fetched once regardless of how
 * many cells it serves.
 *
 * **This is a drop-in, not a new answer.** Compared against the point service
 * at the same coordinates it returns the same values (0/0, 45/45, 18/18,
 * 76/78 -- the last differing by one pixel at a boundary), because it reads the
 * same raster at its native 30 m. See [RasterGrid.PIXELS] for why coarser was
 * rejected.
 *
 * Two format traps, both found by comparing against the point service:
 *  - PNG comes back as four rendered RGBA bands with a colour ramp applied.
 *    Only tiff is the raw single-band data; PNG values are not canopy at all.
 *  - The export must be requested at native resolution. At 108 m per pixel
 *    nearest-neighbour picks one arbitrary 30 m cell out of thirteen, and
 *    Mount Mansfield read 0% against the 45% reported by the point service.
 */
@Component
@Primary
class NlcdTileCanopySource(
    private val restClient: RestClient,
    @Value("\${foliage.terrain.canopy-url}") private val url: String,
    @Value("\${foliage.terrain.canopy-tile-threads}") private val threads: Int,
) : CanopySource {

    private val log = LoggerFactory.getLogger(javaClass)
    private val retry = RetryPolicy(maxAttempts = 3, initialBackoffMs = 5_000)

    override fun sample(points: List<LonLat>): List<Int?> {
        if (points.isEmpty()) return emptyList()

        val located = points.map { RasterGrid.locate(it) }
        // Which input positions each tile is responsible for. Built once so a
        // tile can be sampled and released without a second pass over points.
        val byTile = located.indices.groupBy { located[it].tile }

        log.info("canopy: {} points across {} tiles", points.size, byTile.size)
        val out = arrayOfNulls<Int>(points.size)
        fetchAndSample(byTile, located, out)

        val missed = out.count { it == null }
        if (missed > 0) log.warn("canopy: {} of {} points unresolved", missed, points.size)
        return out.toList()
    }

    /**
     * Fetches each tile, reads the points that fall in it, and releases it.
     *
     * **Sampling has to happen here rather than after the fetches.** A tile
     * decodes to ~13 MB, and a large state spans dozens of them; holding them
     * all until the end would be hundreds of megabytes of heap for data that is
     * finished with the moment its points are read. Peak memory is instead
     * bounded by `threads x 13 MB` no matter how much ground the call covers,
     * which is what lets one call bootstrap Texas as safely as Vermont.
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
                        retry.execute("canopy tile ${tile.west},${tile.south}", { true }) { export(tile) }
                    }.onSuccess { image ->
                        if (image != null) {
                            // Distinct output slots, so no two threads ever
                            // write the same index and no lock is needed.
                            for (i in positions) {
                                val at = located[i]
                                out[i] = image.raster.getSample(at.px, at.py, 0).coerceIn(0, 100)
                            }
                        }
                    }.onFailure {
                        // Degrade rather than abort, matching every other
                        // terrain source: those cells stay unsampled and a
                        // later run fills them.
                        log.warn("canopy tile {},{} failed: {}", tile.west, tile.south, it.message)
                    }
                    val n = progress.incrementAndGet()
                    if (n % 25 == 0 || n == byTile.size) log.info("canopy: {}/{} tiles", n, byTile.size)
                }
            }.forEach { it.get() }
        } finally {
            pool.shutdown()
            pool.awaitTermination(60, TimeUnit.SECONDS)
        }
    }

    private fun export(tile: RasterTile): BufferedImage? {
        // Built by hand rather than with UriComponentsBuilder: the bbox commas
        // must survive unencoded, and the parameter order is what the service
        // caches on.
        val uri = "$url/exportImage?bbox=${tile.bbox()}&bboxSR=4326&imageSR=4326" +
            "&size=${RasterGrid.PIXELS},${RasterGrid.PIXELS}" +
            "&format=tiff&interpolation=RSP_NearestNeighbor&f=image"

        val bytes = restClient.get().uri(uri).retrieve().body(ByteArray::class.java) ?: return null
        return ImageIO.read(ByteArrayInputStream(bytes))
    }
}
