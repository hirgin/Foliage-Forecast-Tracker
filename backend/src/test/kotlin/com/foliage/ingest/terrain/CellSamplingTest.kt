package com.foliage.ingest.terrain

import com.foliage.grid.H3Grid
import com.foliage.grid.LonLat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CellSamplingTest {

    private val grid = H3Grid()

    /** The res 6 cell over central Vermont. */
    private val cell = grid.cellAt(LonLat(-72.70, 44.00), 6)

    @Test
    fun `samples the centroid plus one point per vertex`() {
        val pts = CellSampling.points(grid, cell)
        // H3 cells are hexagons: 6 vertices, so 7 samples.
        assertEquals(7, pts.size)
        assertEquals(grid.centroid(cell), pts.first())
    }

    @Test
    fun `every sample point lies strictly inside the cell boundary`() {
        // Points on the boundary are shared with neighbours and would blur the
        // edges of forest regions.
        val c = grid.centroid(cell)
        val boundary = grid.boundary(cell)
        val maxRadius = boundary.maxOf { distSqDeg(it, c) }

        CellSampling.points(grid, cell).forEach { p ->
            assertTrue(distSqDeg(p, c) < maxRadius, "sample $p is not strictly inside the cell")
        }
    }

    @Test
    fun `averages only the samples that came back`() {
        assertEquals(60, CellSampling.average(listOf(80, null, 40)))
        assertEquals(50, CellSampling.average(listOf(50)))
    }

    @Test
    fun `a cell with no samples at all is null, not zero`() {
        // Zero would mean "no trees here"; null means "off the raster".
        // Conflating them would paint ocean and border cells as bare forest.
        assertNull(CellSampling.average(listOf(null, null)))
    }

    @Test
    fun `rounds to the nearest whole percent`() {
        assertEquals(67, CellSampling.average(listOf(67, 67, 66)))
    }

    /** Squared planar distance in degrees -- adequate for comparing radii within one small cell. */
    private fun distSqDeg(a: LonLat, b: LonLat): Double {
        val dx = a.lon - b.lon
        val dy = a.lat - b.lat
        return dx * dx + dy * dy
    }
}
