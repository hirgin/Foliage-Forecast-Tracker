package com.foliage.grid

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class H3GridTest {

    private val grid = H3Grid()
    private val res = 6

    /** Vermont's bounding box, from the Census TIGERweb boundary. */
    private val vermontBbox = SimplePolygon(
        outer = listOf(
            LonLat(-73.438, 42.727),
            LonLat(-71.465, 42.727),
            LonLat(-71.465, 45.017),
            LonLat(-73.438, 45.017),
            LonLat(-73.438, 42.727),
        ),
    )

    @Test
    fun `tiles a Vermont-sized box into a plausible number of res 6 cells`() {
        val cells = grid.tile(vermontBbox, res)

        // The box spans roughly 254 km x 158 km ~= 40,000 km2. A res 6 cell
        // averages 36 km2, so expect on the order of 1,100 cells. Bounds are
        // deliberately loose -- this asserts the right order of magnitude,
        // not an exact tiling, which varies with how cells straddle the edge.
        assertTrue(
            cells.size in 800..1600,
            "expected ~1100 res 6 cells for the Vermont bbox, got ${cells.size}",
        )
        assertEquals(cells.size, cells.toSet().size, "tiling produced duplicate cells")
    }

    @Test
    fun `every tiled cell is at the requested resolution`() {
        grid.tile(vermontBbox, res).forEach {
            assertEquals(res, grid.resolution(it), "cell ${grid.toAddress(it)} is wrong resolution")
        }
    }

    @Test
    fun `ancestors are consistent and each res 5 parent holds at most 7 children`() {
        val cells = grid.tile(vermontBbox, res)

        cells.forEach { cell ->
            val p5 = grid.parent(cell, 5)
            assertEquals(5, grid.resolution(p5))
            // Ancestry must be transitive: the res 4 parent of a cell is also
            // the res 4 parent of its res 5 parent. Zoom aggregation relies on
            // this, since it groups by a denormalised ancestor column.
            assertEquals(grid.parent(cell, 4), grid.parent(p5, 4))
            assertEquals(grid.parent(cell, 3), grid.parent(grid.parent(cell, 4), 3))
        }

        val childrenPerParent = cells.groupingBy { grid.parent(it, 5) }.eachCount()
        assertTrue(
            childrenPerParent.values.all { it <= 7 },
            "an H3 res 5 cell has at most 7 res 6 children; got ${childrenPerParent.values.max()}",
        )
    }

    @Test
    fun `centroid of a tiled cell falls inside the box`() {
        grid.tile(vermontBbox, res).forEach {
            val c = grid.centroid(it)
            assertTrue(c.lat in 42.0..45.7 && c.lon in -74.1..-70.8, "centroid $c escaped the box")
        }
    }
}
