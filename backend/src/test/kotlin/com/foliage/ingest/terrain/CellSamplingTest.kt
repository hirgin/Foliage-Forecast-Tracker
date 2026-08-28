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

    // --- elevation over water -------------------------------------------

    @Test
    fun `prefers land when a coastal cell straddles the shore`() {
        // The bug this exists for: terrain tiles carry bathymetry, so a cell
        // whose centroid falls offshore read the seabed. Massachusetts had 92
        // forested cells, some at 78% canopy, sitting at up to -74 m.
        val straddling = listOf(-60, -55, 12, 18, 24, -48, 15)
        assertEquals(17, CellSampling.landElevation(straddling))
    }

    @Test
    fun `ignores gaps`() {
        assertEquals(20, CellSampling.landElevation(listOf(null, 10, null, 30)))
    }

    @Test
    fun `keeps genuinely below-sea-level ground`() {
        // Death Valley and the Salton Sea really are below sea level. Clamping
        // at zero would invent ground there, so a cell with no land sample at
        // all keeps its negative value.
        assertEquals(-70, CellSampling.landElevation(listOf(-72, -68, -70)))
    }

    @Test
    fun `is null only when every sample is missing`() {
        assertNull(CellSampling.landElevation(listOf(null, null)))
        assertNull(CellSampling.landElevation(emptyList()))
    }

    @Test
    fun `treats sea level itself as land`() {
        // A shoreline sample of exactly 0 is ground, not water; excluding it
        // would drop the only usable sample on a flat coastal cell.
        assertEquals(0, CellSampling.landElevation(listOf(-40, 0, -30)))
    }

    @Test
    fun `leaves inland cells untouched`() {
        // Vermont has no coast, which is exactly why the original equivalence
        // test missed this. Inland cells must be unaffected by the fix.
        assertEquals(371, CellSampling.landElevation(listOf(340, 355, 371, 388, 401)))
    }
}
