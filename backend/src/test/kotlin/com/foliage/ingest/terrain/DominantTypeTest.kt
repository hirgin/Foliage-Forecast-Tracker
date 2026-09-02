package com.foliage.ingest.terrain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Turning seven raster samples into one forest type for a cell.
 *
 * The aggregation is a mode rather than a mean, and these tests exist mostly
 * to keep it that way: FIA codes are identifiers, so averaging them produces a
 * number that looks like a forest and is not one.
 */
class DominantTypeTest {

    @Test
    fun `the cell takes the forest most of it is made of`() {
        assertEquals(800, CellSampling.dominantType(listOf(800, 800, 800, 900, 500)))
    }

    @Test
    fun `codes are never averaged`() {
        // 800 is maple-beech, 900 is aspen-birch, and 850 is nothing at all.
        // The single most important property here: whatever comes out must be
        // a code that went in.
        val samples = listOf(800, 900)
        val result = CellSampling.dominantType(samples)!!
        assertEquals(true, result in samples, "result must be an observed code, not a blend")
    }

    @Test
    fun `lakes do not outvote trees`() {
        // Every probe of this raster returned zeros, because a 30 m pixel in
        // Minnesota lake country is quite often a lake. Counting no-data would
        // let water decide what forest a cell is -- or erase it entirely.
        assertEquals(900, CellSampling.dominantType(listOf(0, 0, 0, 0, 900, 900, 120)))
    }

    @Test
    fun `non-stocked ground is not a kind of forest`() {
        // 999 says there is no forest here. That is a different claim from
        // "this is a forest of type 999", and counting it as a vote would let
        // clearings outvote the trees around them.
        assertEquals(500, CellSampling.dominantType(listOf(999, 999, 999, 500, 500)))
    }

    @Test
    fun `a cell with nothing to classify is null, not a guess`() {
        // Null flows through to the maple-beech baseline, so such a cell
        // scores exactly as it did before the species term existed.
        assertNull(CellSampling.dominantType(listOf(0, 0, 0)))
        assertNull(CellSampling.dominantType(listOf(999, 0, null)))
        assertNull(CellSampling.dominantType(emptyList()))
        assertNull(CellSampling.dominantType(listOf(null, null)))
    }

    @Test
    fun `a tie resolves the same way every time`() {
        // A cell split evenly between two forests is a real thing, not an
        // error. What matters is that it classifies identically on every run,
        // or the same cell would read differently on successive samplings and
        // no reading could be reproduced.
        val a = CellSampling.dominantType(listOf(800, 800, 500, 500))
        val b = CellSampling.dominantType(listOf(500, 500, 800, 800))
        assertEquals(a, b, "input order must not decide the answer")
        assertEquals(500, a)
    }

    @Test
    fun `values that are not forest codes are rejected outright`() {
        // The raster returns 63693 over Canada and the Great Lakes. It is not
        // a forest type, not FIA's no-data, and too large for the column that
        // stores it -- which is the only reason it was noticed at all. A bogus
        // value inside the column's range would have been stored as a
        // plausible forest and quietly re-timed those cells.
        assertEquals(800, CellSampling.dominantType(listOf(63693, 63693, 63693, 800)))
        assertNull(CellSampling.dominantType(listOf(63693, 63693)))
        assertEquals(false, CellSampling.isForestCode(63693))
        assertEquals(false, CellSampling.isForestCode(0))
        assertEquals(false, CellSampling.isForestCode(999))
        assertEquals(true, CellSampling.isForestCode(800))
    }

    @Test
    fun `codes this project does not map are still stored`() {
        // 950 is other western hardwoods: a real forest with no measured
        // multiplier yet. Dropping it would lose the survey work; storing it
        // means it scores at the baseline until someone measures it.
        assertEquals(950, CellSampling.dominantType(listOf(950, 950, 800)))
    }

    @Test
    fun `missing samples are ignored rather than counted`() {
        assertEquals(900, CellSampling.dominantType(listOf(null, 900, null, 900, 800)))
    }
}
