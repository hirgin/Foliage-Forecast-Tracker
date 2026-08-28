package com.foliage.ingest.terrain

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Parser tests driven by responses captured from the live services on
 * 2026-08-27. See docs/testing.md for why fixtures rather than Testcontainers.
 */
class TerrainParserTest {

    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/fixtures/$name")) { "missing fixture $name" }
            .bufferedReader().readText()

    // --- canopy ----------------------------------------------------------

    @Test
    fun `parses a real NLCD getSamples response`() {
        val canopy = NlcdSampleParser.parse(fixture("nlcd-getsamples.json"), pointCount = 4)
        // Four Vermont points, sampled at the raster's native 30 m.
        assertEquals(listOf(82, 56, 61, 85), canopy)
    }

    @Test
    fun `places samples by locationId, not by array position`() {
        // The service does not guarantee ordering; relying on position would
        // silently attach the wrong canopy value to the wrong hexagon.
        val json = """
            {"samples":[
              {"locationId":2,"value":"30"},
              {"locationId":0,"value":"90"},
              {"locationId":1,"value":"60"}
            ]}
        """.trimIndent()
        assertEquals(listOf(90, 60, 30), NlcdSampleParser.parse(json, pointCount = 3))
    }

    @Test
    fun `omitted points stay null rather than shifting later values`() {
        // Points outside the raster are dropped from the response entirely.
        val json = """{"samples":[{"locationId":0,"value":"70"},{"locationId":2,"value":"40"}]}"""
        assertEquals(listOf(70, null, 40), NlcdSampleParser.parse(json, pointCount = 3))
    }

    @Test
    fun `NoData and out-of-range values become null or clamp`() {
        val json = """
            {"samples":[
              {"locationId":0,"value":"NoData"},
              {"locationId":1,"value":"255"},
              {"locationId":2,"value":"-5"}
            ]}
        """.trimIndent()
        assertEquals(listOf(null, 100, 0), NlcdSampleParser.parse(json, pointCount = 3))
    }

    @Test
    fun `ignores locationIds outside the requested range`() {
        val json = """{"samples":[{"locationId":9,"value":"50"},{"locationId":0,"value":"50"}]}"""
        assertEquals(listOf(50, null), NlcdSampleParser.parse(json, pointCount = 2))
    }

    @Test
    fun `empty response yields all nulls, not an error`() {
        assertEquals(listOf(null, null), NlcdSampleParser.parse("""{"samples":[]}""", pointCount = 2))
    }

    // --- elevation -------------------------------------------------------

    @Test
    fun `parses a real Open-Meteo elevation response`() {
        val elev = OpenMeteoElevationParser.parse(fixture("openmeteo-elevation.json"), pointCount = 4)
        assertEquals(listOf(492, 127, 450, 118), elev)
    }

    @Test
    fun `rounds fractional elevations to the nearest metre`() {
        val json = """{"elevation":[100.4,100.5,-0.4]}"""
        assertEquals(listOf(100, 101, 0), OpenMeteoElevationParser.parse(json, pointCount = 3))
    }

    @Test
    fun `a short elevation array degrades to nulls instead of throwing`() {
        // One bad batch must not abort an entire grid bootstrap.
        val json = """{"elevation":[300.0]}"""
        assertEquals(listOf(300, null, null), OpenMeteoElevationParser.parse(json, pointCount = 3))
    }

    @Test
    fun `a missing elevation field yields all nulls`() {
        assertEquals(listOf(null, null), OpenMeteoElevationParser.parse("""{"error":true}""", pointCount = 2))
    }
}
