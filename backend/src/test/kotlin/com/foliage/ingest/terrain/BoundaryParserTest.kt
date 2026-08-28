package com.foliage.ingest.terrain

import com.foliage.grid.H3Grid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BoundaryParserTest {

    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/fixtures/$name")) { "missing fixture $name" }
            .bufferedReader().readText()

    @Test
    fun `parses the real Vermont boundary from TIGERweb`() {
        val vt = TigerWebBoundaryParser.parse(fixture("tigerweb-vermont.json"))

        assertEquals("Vermont", vt.name)
        assertEquals("50", vt.fips)
        assertEquals(1, vt.polygons.size)
        assertTrue(vt.polygons[0].holes.isEmpty())

        val ring = vt.polygons[0].outer
        assertTrue(ring.size > 50, "boundary should retain real shape, got ${ring.size} vertices")
        // Coordinates must land as (lon, lat) -- swapping them is the classic
        // GeoJSON bug and would silently put Vermont in the Indian Ocean.
        assertTrue(ring.all { it.lat in 42.0..46.0 }, "latitudes outside Vermont")
        assertTrue(ring.all { it.lon in -74.0..-71.0 }, "longitudes outside Vermont")
    }

    @Test
    fun `tiling the real boundary yields a plausible Vermont grid`() {
        // The integration that matters: boundary parsing feeding H3 tiling.
        // Vermont is 24,900 km2 and a res 6 cell averages 36 km2, so expect
        // roughly 690 cells.
        val vt = TigerWebBoundaryParser.parse(fixture("tigerweb-vermont.json"))
        val cells = vt.polygons.flatMap { H3Grid().tile(it, 6) }.toSet()

        assertTrue(
            cells.size in 500..900,
            "expected ~690 res 6 cells covering Vermont, got ${cells.size}",
        )
    }

    @Test
    fun `handles MultiPolygon states`() {
        // Islands and lake boundaries split many states into multiple parts.
        val json = """
            {"features":[{"properties":{"NAME":"Testland","STATE":"99"},
             "geometry":{"type":"MultiPolygon","coordinates":[
               [[[-72.0,44.0],[-71.0,44.0],[-71.0,45.0],[-72.0,44.0]]],
               [[[-70.0,43.0],[-69.0,43.0],[-69.0,44.0],[-70.0,43.0]]]
             ]}}]}
        """.trimIndent()
        val b = TigerWebBoundaryParser.parse(json)
        assertEquals(2, b.polygons.size)
        assertEquals(4, b.polygons[0].outer.size)
    }

    @Test
    fun `rings after the first become holes`() {
        val json = """
            {"features":[{"properties":{"NAME":"Holey","STATE":"98"},
             "geometry":{"type":"Polygon","coordinates":[
               [[-72.0,44.0],[-71.0,44.0],[-71.0,45.0],[-72.0,45.0],[-72.0,44.0]],
               [[-71.8,44.2],[-71.6,44.2],[-71.6,44.4],[-71.8,44.2]]
             ]}}]}
        """.trimIndent()
        val b = TigerWebBoundaryParser.parse(json)
        assertEquals(1, b.polygons.size)
        assertEquals(1, b.polygons[0].holes.size)
        assertEquals(4, b.polygons[0].holes[0].size)
    }

    @Test
    fun `an empty result is an error, not an empty state`() {
        // Silently returning a zero-area state would produce an empty grid
        // and look like a masking problem much later.
        assertFailsWith<IllegalStateException> {
            TigerWebBoundaryParser.parse("""{"features":[]}""")
        }
    }

    @Test
    fun `an unsupported geometry type is rejected loudly`() {
        val json = """
            {"features":[{"properties":{"NAME":"X","STATE":"01"},
             "geometry":{"type":"LineString","coordinates":[[-72.0,44.0],[-71.0,44.0]]}}]}
        """.trimIndent()
        assertFailsWith<IllegalStateException> { TigerWebBoundaryParser.parse(json) }
    }
}
