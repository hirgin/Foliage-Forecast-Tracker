package com.foliage.ingest.weather

import com.foliage.domain.WeatherKind
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WeatherParserTest {

    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/fixtures/$name")) { "missing fixture $name" }
            .bufferedReader().readText()

    // --- response shape ---------------------------------------------------

    @Test
    fun `parses a multi-location response`() {
        val out = OpenMeteoDailyParser.parse(fixture("openmeteo-forecast-multi.json"), expectedLocations = 2)
        assertEquals(2, out.size)
        out.forEach { assertEquals(5, it.size, "expected 2 past days + 3 forecast days") }
        assertTrue(out[0][0].tmaxC != null && out[0][0].tminC != null)
    }

    @Test
    fun `parses a single-location response, which is an object rather than an array`() {
        // Open-Meteo changes shape based on how many coordinates were asked
        // for. Handling only the array form works right up until the final
        // batch of a run happens to contain one point.
        val out = OpenMeteoDailyParser.parse(fixture("openmeteo-forecast-single.json"), expectedLocations = 1)
        assertEquals(1, out.size)
        assertEquals(5, out[0].size)
    }

    @Test
    fun `days come back in ascending order and span the requested window`() {
        val days = OpenMeteoDailyParser.parse(fixture("openmeteo-forecast-multi.json"), 2)[0].map { it.day }
        assertEquals(days.sorted(), days, "days must be ordered; accumulators depend on it")
        assertEquals(5, days.distinct().size, "duplicate days would double-count in accumulation")
    }

    // --- degradation ------------------------------------------------------

    @Test
    fun `a location missing from the response yields an empty list, not a shifted one`() {
        val out = OpenMeteoDailyParser.parse(fixture("openmeteo-forecast-multi.json"), expectedLocations = 4)
        assertEquals(4, out.size)
        assertTrue(out[2].isEmpty() && out[3].isEmpty())
        assertTrue(out[0].isNotEmpty(), "present locations must keep their position")
    }

    @Test
    fun `null measurements become null, not zero`() {
        // Zero precipitation is a real, meaningful value. Conflating it with
        // "no data" would silently invent dry days.
        val json = """
            {"daily":{"time":["2026-09-01"],"temperature_2m_max":[null],
             "temperature_2m_min":[8.0],"precipitation_sum":[null],
             "shortwave_radiation_sum":[null]}}
        """.trimIndent()
        val rec = OpenMeteoDailyParser.parse(json, 1)[0][0]
        assertNull(rec.tmaxC)
        assertNull(rec.precipMm)
        assertEquals(8.0, rec.tminC)
    }

    @Test
    fun `a response with no daily block degrades to empty`() {
        assertEquals(listOf(emptyList()), OpenMeteoDailyParser.parse("""{"error":true}""", 1))
    }

    // --- provenance policy (ADR-0005) -------------------------------------

    @Test
    fun `yesterday is observed, tomorrow is forecast, and the far season is climatology`() {
        val today = LocalDate.of(2026, 8, 27)
        assertEquals(WeatherKind.OBSERVED, WeatherKindPolicy.classify(today.minusDays(1), today))
        assertEquals(WeatherKind.FORECAST, WeatherKindPolicy.classify(today, today))
        assertEquals(WeatherKind.FORECAST, WeatherKindPolicy.classify(today.plusDays(1), today))
        // Peak foliage in Vermont is early October -- well past the horizon.
        assertEquals(WeatherKind.CLIMATOLOGY, WeatherKindPolicy.classify(LocalDate.of(2026, 10, 5), today))
    }

    @Test
    fun `the forecast boundary is inclusive at 16 days and climatology at 17`() {
        val today = LocalDate.of(2026, 8, 27)
        assertEquals(WeatherKind.FORECAST, WeatherKindPolicy.classify(today.plusDays(16), today))
        assertEquals(WeatherKind.CLIMATOLOGY, WeatherKindPolicy.classify(today.plusDays(17), today))
    }
}
