package com.foliage.ingest.terrain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class Usgs3depParserTest {

    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/fixtures/$name")) { "missing fixture $name" }
            .bufferedReader().readText()

    @Test
    fun `parses a real 3DEP getSamples response and rounds to whole metres`() {
        val e = Usgs3depParser.parse(fixture("3dep-getsamples.json"), pointCount = 3)
        assertEquals(listOf(492, 112, 487), e)
    }

    @Test
    fun `places results by locationId, not array position`() {
        // Same trap as the canopy service: off-raster points are omitted, so
        // relying on order attaches the wrong elevation to the wrong cell.
        val json = """
            {"samples":[
              {"locationId":2,"value":"300.0"},
              {"locationId":0,"value":"100.0"}
            ]}
        """.trimIndent()
        assertEquals(listOf(100, null, 300), Usgs3depParser.parse(json, 3))
    }

    @Test
    fun `rejects NoData sentinels rather than treating them as terrain`() {
        // 3DEP reports large negatives off-raster; -3.4e38 as an elevation
        // would wreck the lapse-rate correction for that cell.
        val json = """
            {"samples":[
              {"locationId":0,"value":"-340282346638528859811704183484516925440.0"},
              {"locationId":1,"value":"-9999.0"},
              {"locationId":2,"value":"1200.5"}
            ]}
        """.trimIndent()
        assertEquals(listOf(null, null, 1201), Usgs3depParser.parse(json, 3))
    }

    @Test
    fun `rejects implausibly high values`() {
        val json = """{"samples":[{"locationId":0,"value":"99999.0"}]}"""
        assertNull(Usgs3depParser.parse(json, 1)[0])
    }

    @Test
    fun `accepts genuine below-sea-level terrain`() {
        // Death Valley is -86 m; clamping at zero would be wrong.
        val json = """{"samples":[{"locationId":0,"value":"-86.0"}]}"""
        assertEquals(-86, Usgs3depParser.parse(json, 1)[0])
    }

    @Test
    fun `an empty or malformed response degrades to nulls`() {
        assertEquals(listOf(null, null), Usgs3depParser.parse("""{"samples":[]}""", 2))
        assertEquals(listOf(null), Usgs3depParser.parse("""{"error":{"code":500}}""", 1))
    }
}
