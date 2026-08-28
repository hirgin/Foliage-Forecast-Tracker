package com.foliage.ingest.terrain

import com.foliage.grid.LonLat
import com.foliage.ingest.RetryPolicy
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient

/**
 * Elevation from AWS's public Terrarium terrain tiles.
 *
 * **Why this replaces point sampling.** 3DEP answers one HTTP request per
 * batch of 50 points. Measured at CONUS scale that is 4,473 sequential
 * requests at 57 s each -- roughly 71 hours, and the documented blocker on
 * loading the country. A tile covers ground rather than points, so cells are
 * grouped by the tile they fall in and each tile is fetched once no matter how
 * many cells it serves. CONUS is under 4,000 tiles at zoom 9, fetched in
 * parallel, which turns three days into a couple of minutes.
 *
 * The trade is resolution: ~300 m per pixel against 3DEP's 1 m. That costs
 * nothing real. One centroid sample per 36 km2 hexagon cannot exploit
 * metre-scale detail, and 3DEP already agreed with a 90 m DEM to within a few
 * metres across every Vermont cell -- the same finding recorded on
 * [Usgs3depElevationSource], now acted on.
 *
 * Verified against published summits before adoption:
 *
 *     Mount Mansfield  1334 m   (actual 1339)
 *     Burlington         60 m   (actual   60)
 *     Mount Whitney    4401 m   (actual 4421)
 *     Death Valley      -81 m   (below sea level, not clamped)
 *
 * The service is keyless and public, so nothing here needs a credential.
 */
@Component
@Primary
class TerrainTileElevationSource(
    private val restClient: RestClient,
    @Value("\${foliage.terrain.terrain-tile-url}") private val baseUrl: String,
    @Value("\${foliage.terrain.terrain-tile-threads}") private val threads: Int,
    @Value("\${foliage.terrain.terrain-tile-zoom}") private val zoom: Int = Terrarium.ZOOM,
) : ElevationSource {

    private val log = LoggerFactory.getLogger(javaClass)
    private val retry = RetryPolicy(maxAttempts = 3, initialBackoffMs = 2_000)

    override fun elevation(points: List<LonLat>): List<Int?> {
        if (points.isEmpty()) return emptyList()

        val located = points.map { Terrarium.locate(it, zoom) }
        // Which input positions each tile is responsible for, so a tile can be
        // read and released in one pass.
        val byTile = located.indices.groupBy { located[it].tile }

        log.info("elevation: {} points across {} tiles at zoom {}", points.size, byTile.size, zoom)
        val out = arrayOfNulls<Int>(points.size)
        fetchAndSample(byTile, located, out)

        val missed = out.count { it == null }
        if (missed > 0) log.warn("elevation: {} of {} points unresolved", missed, points.size)
        return out.toList()
    }

    /**
     * Fetches each tile, reads the points that fall in it, and releases it.
     *
     * Sampling happens inside the fetch rather than after it so that peak
     * memory is bounded by `threads x tile` regardless of how much ground the
     * call covers -- CONUS is thousands of tiles, and holding them all would
     * be gigabytes of heap for data finished with as soon as it is read.
     *
     * Unlike the point services this is a plain CDN read with no per-account
     * metering, so modest parallelism is the intended usage rather than abuse.
     * The pool is created and shut down per call so an idle bootstrap holds no
     * threads.
     */
    private fun fetchAndSample(
        byTile: Map<TileRef, List<Int>>,
        located: List<TilePixel>,
        out: Array<Int?>,
    ) {
        val progress = AtomicInteger()
        val pool = Executors.newFixedThreadPool(threads.coerceIn(1, 16))
        try {
            byTile.map { (tile, positions) ->
                pool.submit {
                    runCatching {
                        retry.execute("tile ${tile.z}/${tile.x}/${tile.y}", { true }) { decodeTile(tile) }
                    }.onSuccess { image ->
                        if (image != null) {
                            // Distinct output slots, so no two threads ever
                            // write the same index and no lock is needed.
                            for (i in positions) {
                                val at = located[i]
                                out[i] = Terrarium.decode(image.getRGB(at.px, at.py))
                                    ?.let { Math.round(it).toInt() }
                            }
                        }
                    }.onFailure {
                        // Degrade rather than abort: those cells stay unsampled
                        // and a later run fills them, matching how every other
                        // terrain source behaves.
                        log.warn("tile {}/{}/{} failed: {}", tile.z, tile.x, tile.y, it.message)
                    }
                    val n = progress.incrementAndGet()
                    if (n % 200 == 0 || n == byTile.size) log.info("elevation: {}/{} tiles", n, byTile.size)
                }
            }.forEach { it.get() }
        } finally {
            pool.shutdown()
            pool.awaitTermination(30, TimeUnit.SECONDS)
        }
    }

    private fun decodeTile(tile: TileRef): BufferedImage? {
        val bytes = restClient.get()
            .uri(Terrarium.url(baseUrl, tile))
            .retrieve()
            .body(ByteArray::class.java) ?: return null
        return ImageIO.read(ByteArrayInputStream(bytes))
    }
}
