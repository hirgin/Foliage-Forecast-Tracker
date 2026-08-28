package com.foliage.ingest.terrain

import com.foliage.grid.LonLat
import java.time.Duration
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.web.client.RestClient

/**
 * The tile canopy source against the point service it replaces.
 *
 * **Opt-in.** Set `FOLIAGE_TERRAIN_LIVE_TEST=true`; it reaches the public
 * internet and skips otherwise. A fixture was rejected because a single tile
 * is 13 MB.
 *
 * This is the test that matters for the swap. [RasterGridTest] proves the
 * arithmetic offline, but the claim being made is stronger than "it returns
 * numbers": it is that reading the raster as tiles gives *the same answers* as
 * sampling it point by point, so replacing one with the other does not
 * silently redraw the forest mask. So both are run against the same
 * coordinates and compared directly, rather than the tile source being checked
 * against hardcoded values it could drift from together with the service.
 */
class CanopyTileLiveTest {

    private val enabled = System.getenv("FOLIAGE_TERRAIN_LIVE_TEST")?.toBoolean() ?: false
    private val url =
        "https://imagery.geoplatform.gov/iipp/rest/services/Vegetation/USFS_EDW_NLCD_TCC_CONUS/ImageServer"

    private fun client() = RestClient.builder()
        .requestFactory(
            SimpleClientHttpRequestFactory().apply {
                setConnectTimeout(Duration.ofSeconds(20))
                setReadTimeout(Duration.ofSeconds(300))
            },
        )
        .build()

    private val tiles by lazy { NlcdTileCanopySource(client(), url, threads = 2) }
    private val points by lazy { NlcdCanopySource(client(), url, batchSize = 250) }

    /** Forest, town, lakeshore and ridge inside one degree tile. */
    private val vermont = listOf(
        LonLat(-72.6874, 44.4654),
        LonLat(-72.8148, 44.5438),
        LonLat(-72.5000, 44.2000),
        LonLat(-72.1000, 44.8000),
        LonLat(-72.9500, 44.9000),
        LonLat(-72.2500, 44.3500),
    )

    @Test
    fun `agrees with the point service`() {
        assumeTrue(enabled, "set FOLIAGE_TERRAIN_LIVE_TEST=true to run")

        val fromTiles = tiles.sample(vermont)
        val fromPoints = points.sample(vermont)

        vermont.indices.forEach { i ->
            val t = fromTiles[i]
            val p = fromPoints[i]
            assertTrue(t != null, "tile source returned nothing at ${vermont[i]}")
            assertTrue(p != null, "point source returned nothing at ${vermont[i]}")
            // Not exact equality: the two paths round a coordinate to a pixel
            // independently, so a point near a 30 m boundary can legitimately
            // land one pixel apart. A misalignment would show up as a large,
            // systematic gap, not a few percent.
            assertTrue(
                abs(t!! - p!!) <= 10,
                "canopy disagrees at ${vermont[i]}: tiles=$t points=$p",
            )
        }
    }

    @Test
    fun `makes the same forest-mask decision`() {
        assumeTrue(enabled, "set FOLIAGE_TERRAIN_LIVE_TEST=true to run")

        // What the canopy number is actually *for*. Values may differ by a few
        // percent harmlessly; which side of the threshold they fall on is what
        // changes the map.
        val threshold = 20
        val fromTiles = tiles.sample(vermont).map { (it ?: 0) >= threshold }
        val fromPoints = points.sample(vermont).map { (it ?: 0) >= threshold }
        assertEquals(fromPoints, fromTiles)
    }

    @Test
    fun `returns values in canopy range`() {
        assumeTrue(enabled, "set FOLIAGE_TERRAIN_LIVE_TEST=true to run")

        // Guards the PNG trap: a rendered RGBA export decodes to numbers in
        // 0..255 that look plausible until you notice canopy cannot exceed 100.
        tiles.sample(vermont).forEach { v ->
            assertTrue(v == null || v in 0..100, "canopy out of range: $v")
        }
    }

    @Test
    fun `orders results to match the input`() {
        assumeTrue(enabled, "set FOLIAGE_TERRAIN_LIVE_TEST=true to run")

        // Grouping by tile reorders the fetches internally. This is the same
        // trap the point services carry -- results placed by position when the
        // service omits entries -- so the mapping back is asserted directly.
        val forward = tiles.sample(vermont)
        val reversed = tiles.sample(vermont.reversed())
        assertEquals(forward, reversed.reversed())
    }

    @Test
    fun `spans a tile boundary without shifting`() {
        assumeTrue(enabled, "set FOLIAGE_TERRAIN_LIVE_TEST=true to run")

        // Two points a few hundred metres apart either side of the -72 meridian
        // land in different tiles. If the pixel mapping were off by a tile edge
        // this pair would disagree with the point service while points well
        // inside a tile still looked fine.
        val straddling = listOf(LonLat(-72.004, 44.5), LonLat(-71.996, 44.5))
        assertEquals(2, straddling.map { RasterGrid.tileOf(it) }.distinct().size)

        val fromTiles = tiles.sample(straddling)
        val fromPoints = points.sample(straddling)
        straddling.indices.forEach { i ->
            assertTrue(
                abs((fromTiles[i] ?: 0) - (fromPoints[i] ?: 0)) <= 10,
                "canopy disagrees across the tile edge at ${straddling[i]}: " +
                    "tiles=${fromTiles[i]} points=${fromPoints[i]}",
            )
        }
    }
}
