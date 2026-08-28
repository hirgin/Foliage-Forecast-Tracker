package com.foliage.ingest.weather.hrrr

import com.foliage.grid.LonLat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.springframework.http.HttpHeaders
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.web.client.RestClient
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.io.path.deleteIfExists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * End-to-end against NOAA's live bucket: index, byte-range fetch, GRIB2
 * decode, and sampling at real coordinates.
 *
 * **Opt-in.** Set `FOLIAGE_HRRR_LIVE_TEST=true` to run it. It reaches the
 * public internet and depends on a product still being in the rolling window,
 * so it skips rather than failing a normal build. A GRIB2 fixture would be
 * 1.2 MB, which is more than this repository should carry for one test.
 *
 * The offline coverage that always runs lives in HrrrIndexParserTest and
 * HrrrRunTest. See docs/testing.md.
 */
class HrrrLiveIngestTest {

    private val enabled = System.getenv("FOLIAGE_HRRR_LIVE_TEST")?.toBoolean() ?: false

    private val client = HrrrClient(
        RestClient.builder()
            .requestFactory(
                SimpleClientHttpRequestFactory().apply {
                    setConnectTimeout(Duration.ofSeconds(20))
                    setReadTimeout(Duration.ofSeconds(120))
                },
            )
            .build(),
        "https://noaa-hrrr-bdp-pds.s3.amazonaws.com",
    )

    private val sampler = Grib2Sampler()

    /**
     * A synoptic analysis from yesterday: comfortably inside the rolling
     * window, and published long enough ago that lag cannot matter.
     */
    private fun recentRun(): HrrrRun {
        val yesterday = Instant.now().minus(Duration.ofDays(1)).atZone(ZoneOffset.UTC).toLocalDate()
        return HrrrRun(yesterday, cycleHour = 12, forecastHour = 0)
    }

    @Test
    fun `fetches one variable by byte range and samples it at real coordinates`() {
        assumeTrue(enabled, "set FOLIAGE_HRRR_LIVE_TEST=true to run")

        val run = recentRun()
        val records = client.index(run)
        assertNotNull(records, "no index published for ${run.key}")
        assertTrue(records.size > 100, "expected a full surface index, got ${records.size}")

        val tmp = HrrrIndexParser.find(records, "TMP", "2 m above ground")
        assertNotNull(tmp)

        val target = Files.createTempDirectory("hrrr-test").resolve("tmp2m.grib2")
        try {
            val file = client.fetchVariable(run, "TMP", "2 m above ground", target)
            assertNotNull(file, "byte-range fetch returned nothing")

            val size = Files.size(file)
            assertTrue(
                size in 500_000..5_000_000,
                "expected roughly one message (~1.2 MB), got $size bytes",
            )
            // Valid standalone GRIB2 begins with the ASCII marker "GRIB".
            assertEquals("GRIB", String(Files.readAllBytes(file).copyOfRange(0, 4)))

            val samples = sampler.sample(
                file,
                listOf(
                    LonLat(-72.70, 44.00),   // central Vermont
                    LonLat(-80.21, 25.79),   // Miami
                    LonLat(-60.00, 21.00),   // Atlantic, outside the domain
                ),
            )
            assertEquals(3, samples.size)

            val vermont = sampler.kelvinToCelsius(samples[0])
            val miami = sampler.kelvinToCelsius(samples[1])
            assertNotNull(vermont)
            assertNotNull(miami)

            // Loose physical bounds -- this asserts the decode is sane, not
            // that the weather is any particular value.
            assertTrue(vermont in -40.0..45.0, "implausible Vermont temperature: $vermont C")
            assertTrue(miami in -10.0..50.0, "implausible Miami temperature: $miami C")
            assertTrue(
                miami > vermont,
                "Miami ($miami C) should out-warm Vermont ($vermont C); " +
                    "if not, the grid indexing is probably transposed",
            )

            // Off-domain must be null, not a clamped edge cell quietly
            // reporting the nearest land.
            assertNull(samples[2], "a point outside CONUS must not return a value")
        } finally {
            target.deleteIfExists()
        }
    }

    @Test
    fun `a product that does not exist yields null rather than throwing`() {
        assumeTrue(enabled, "set FOLIAGE_HRRR_LIVE_TEST=true to run")

        // Far in the past: well outside the bucket's rolling window.
        val ancient = HrrrRun(java.time.LocalDate.of(2014, 1, 1), 12, 0)
        assertNull(client.index(ancient))
        assertNull(
            client.fetchVariable(
                ancient, "TMP", "2 m above ground",
                Files.createTempDirectory("hrrr-missing").resolve("x.grib2"),
            ),
        )
    }

    @Test
    fun `sampling refuses a relative path`() {
        // NetCDF-Java resolves data files relative to its own cache directory,
        // so a relative path fails deep inside the library with a confusing
        // FileNotFoundException. Fail here instead, where the reason is clear.
        val relative = java.nio.file.Path.of("some/relative.grib2")
        val error = kotlin.runCatching { sampler.sample(relative, listOf(LonLat(-72.7, 44.0))) }
            .exceptionOrNull()
        assertTrue(error is IllegalArgumentException, "expected a clear rejection, got $error")
    }
}
