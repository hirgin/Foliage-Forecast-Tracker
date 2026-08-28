package com.foliage.ingest.terrain

import com.foliage.grid.LonLat
import java.time.Duration
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.web.client.RestClient

/**
 * End-to-end against AWS's live terrain tiles, through the real bean.
 *
 * **Opt-in.** Set `FOLIAGE_TERRAIN_LIVE_TEST=true`. It reaches the public
 * internet, so it skips rather than failing a normal build. A tile fixture was
 * rejected for the same reason as the GRIB2 one: a single PNG is ~85 KB and
 * the value is in the decoding, which [TerrariumTest] covers offline.
 *
 * What this adds over the offline tests is the part they cannot check -- that
 * the URL shape is right, that the response really is a decodable PNG, and
 * that a point on the ground returns the height a map says it should. See
 * docs/testing.md.
 */
class TerrainTileLiveTest {

    private val enabled = System.getenv("FOLIAGE_TERRAIN_LIVE_TEST")?.toBoolean() ?: false

    private val source = TerrainTileElevationSource(
        RestClient.builder()
            .requestFactory(
                SimpleClientHttpRequestFactory().apply {
                    setConnectTimeout(Duration.ofSeconds(20))
                    setReadTimeout(Duration.ofSeconds(60))
                },
            )
            .build(),
        baseUrl = "https://s3.amazonaws.com/elevation-tiles-prod/terrarium",
        threads = 8,
        zoom = Terrarium.ZOOM,
    )

    /**
     * Published heights.
     *
     * Tolerances are wide *at summits* because a ~300 m pixel averages the
     * ground around a peak rather than hitting its cairn. Mansfield reads about
     * 80 m low for exactly that reason. That is smoothing, not error, and it is
     * the tail rather than the norm: measured over 1,755 points across Vermont,
     * zoom 9 differs from zoom 11 by a median of 8 m and a p90 of 23 m, with
     * near-identical distributions. Eight metres is 0.05 C of lapse rate, which
     * no foliage date can detect. Settlements, which is what cells mostly are,
     * are held to a tight tolerance below.
     */
    private val landmarks = listOf(
        Triple("Mount Mansfield", LonLat(-72.8148, 44.5438), 1339 to 110),
        // Valley and lakeshore: no summit to average away, so these must be close.
        Triple("Stowe village", LonLat(-72.6874, 44.4654), 220 to 30),
        Triple("Burlington", LonLat(-73.2121, 44.4759), 60 to 30),
        Triple("Mount Whitney", LonLat(-118.2923, 36.5785), 4421 to 250),
        Triple("Death Valley", LonLat(-116.8256, 36.2468), -60 to 60),
    )

    @Test
    fun `reads published elevations from the live service`() {
        assumeTrue(enabled, "set FOLIAGE_TERRAIN_LIVE_TEST=true to run")

        val heights = source.elevation(landmarks.map { it.second })

        landmarks.forEachIndexed { i, (name, _, expected) ->
            val (published, tolerance) = expected
            val got = assertNotNull(heights[i], "$name returned no elevation")
            assertTrue(
                abs(got - published) <= tolerance,
                "$name decoded ${got}m, expected ~${published}m (tolerance ${tolerance}m)",
            )
        }
    }

    @Test
    fun `keeps Death Valley below sea level`() {
        assumeTrue(enabled, "set FOLIAGE_TERRAIN_LIVE_TEST=true to run")

        // Asserted separately because sign is the one error the tolerance above
        // could absorb, and a dropped offset would put the whole country 32 km up.
        val below = source.elevation(listOf(LonLat(-116.8256, 36.2468))).single()
        assertTrue(below != null && below < 0, "expected negative elevation, got $below")
    }

    @Test
    fun `orders results to match the input`() {
        assumeTrue(enabled, "set FOLIAGE_TERRAIN_LIVE_TEST=true to run")

        // The trap that bit both point services: results placed by position
        // when the service omits or reorders entries attach the wrong height to
        // the wrong cell. Here grouping by tile reorders fetches internally, so
        // the mapping back to input order is worth asserting directly.
        val forward = source.elevation(landmarks.map { it.second })
        val reversed = source.elevation(landmarks.map { it.second }.reversed())
        assertEquals(forward, reversed.reversed())
    }

    @Test
    fun `agrees with the point service it replaces`() {
        assumeTrue(enabled, "set FOLIAGE_TERRAIN_LIVE_TEST=true to run")

        // The fidelity question the swap turns on. Vermont's cells were loaded
        // with 3DEP elevations, and those feed the lapse-rate downscale that
        // sets how fast colour climbs a mountain. If Terrarium disagreed
        // systematically, re-running the bootstrap would quietly move every
        // forecast date. Held to a spread rather than a mean, because a bias
        // is what would actually hurt: a constant offset shifts the whole
        // state, while scatter at summits averages out across 649 cells.
        //
        // Deliberately small: 3DEP takes ~57 s per 50-point batch, which is
        // the reason this replacement exists at all.
        val spread = buildList {
            var lat = 43.0
            while (lat < 45.0) {
                var lon = -73.2
                while (lon < -71.6) { add(LonLat(lon, lat)); lon += 0.32 }
                lat += 0.22
            }
        }

        val tiles = source.elevation(spread)
        val points = Usgs3depElevationSource(
            RestClient.builder().requestFactory(
                SimpleClientHttpRequestFactory().apply {
                    setConnectTimeout(Duration.ofSeconds(20))
                    setReadTimeout(Duration.ofSeconds(180))
                },
            ).build(),
            url = "https://elevation.nationalmap.gov/arcgis/rest/services/3DEPElevation/ImageServer",
            batchSize = 50,
        ).elevation(spread)

        val pairs = tiles.zip(points).filter { (t, p) -> t != null && p != null }
        assumeTrue(pairs.size >= spread.size / 2, "3DEP returned too little to compare")

        val diffs = pairs.map { (t, p) -> t!! - p!! }
        val bias = diffs.average()
        val spreadAbs = diffs.map { abs(it) }.sorted()
        val median = spreadAbs[spreadAbs.size / 2]

        println("terrarium vs 3DEP over ${pairs.size} points: bias=%.1f m median=|%d| p90=|%d|"
            .format(bias, median, spreadAbs[(spreadAbs.size * 9) / 10]))

        assertTrue(abs(bias) < 25, "systematic offset of %.1f m against 3DEP".format(bias))
        assertTrue(median < 40, "typical disagreement with 3DEP is ${median} m")
    }

    @Test
    fun `shares one tile between neighbouring points`() {
        assumeTrue(enabled, "set FOLIAGE_TERRAIN_LIVE_TEST=true to run")

        // 400 points inside a single tile must still resolve. This is the
        // grouping that makes CONUS tractable; if it broke, correctness would
        // survive and only the runtime would collapse.
        val cluster = (0 until 400).map { LonLat(-72.80 + (it % 20) * 0.001, 44.54 + (it / 20) * 0.001) }
        val heights = source.elevation(cluster)

        assertEquals(400, heights.size)
        assertTrue(heights.all { it != null }, "some clustered points went unresolved")
        assertEquals(1, cluster.map { Terrarium.locate(it).tile }.distinct().size)
    }
}
