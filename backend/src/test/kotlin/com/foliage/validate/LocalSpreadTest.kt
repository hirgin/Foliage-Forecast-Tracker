package com.foliage.validate

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The measurement has to be able to tell a textured map from a flat one, which
 * is the only thing it is for. A grid of cells standing in for hexagons is
 * enough to say so -- no H3, no database.
 */
class LocalSpreadTest {

    private val day0 = LocalDate.of(2026, 9, 1)

    /** A square grid indexed as row * 100 + column, with a square disk. */
    private fun disk(h3: Long, k: Int): List<Long> {
        val row = h3 / 100
        val col = h3 % 100
        val out = mutableListOf<Long>()
        for (r in (row - k)..(row + k)) {
            for (c in (col - k)..(col + k)) {
                if (r in 0..19 && c in 0..19) out += r * 100 + c
            }
        }
        return out
    }

    /** Peaks laid out so that [gradientDays] separates the top row from the bottom. */
    private fun grid(gradientDays: Double): Map<Long, LocalDate> =
        (0..19).flatMap { r ->
            (0..19).map { c -> (r * 100L + c) to day0.plusDays((r * gradientDays).toLong()) }
        }.toMap()

    @Test
    fun `flattening the map shows up as a smaller local spread`() {
        // The same country, the same north-to-south ordering, the same rank
        // correlation -- and half the local variation. This is precisely the
        // regression that three fitted model versions shipped, and that peak
        // dates and state medians both scored as an improvement.
        val textured = LocalSpread.of(grid(2.0), ::disk, k = 4)
        val flattened = LocalSpread.of(grid(1.0), ::disk, k = 4)

        assertEquals(16, textured.medianSpreadDays, "9 rows of disk at 2 days a row")
        assertEquals(8, flattened.medianSpreadDays)
        assertTrue(
            flattened.medianSpreadDays < textured.medianSpreadDays,
            "a flatter map must measure flatter, or this diagnostic is useless",
        )
    }

    @Test
    fun `a cell whose neighbourhood is mostly missing is not measured`() {
        // An island of scored cells smaller than a disk -- a patch of forest
        // in farmland, a state's share of a mountain range. Every one of them
        // sees only the island, so every spread would be the island's own,
        // narrower than the interior for reasons of geometry rather than of
        // climate. Measured anyway, enough of these drag a state's median down
        // and read as a flattened map.
        val island = grid(2.0).filterKeys { it / 100 < 3 && it % 100 < 3 }

        val measured = LocalSpread.of(island, ::disk, k = 4)

        assertEquals(9, measured.cellsWithPeak, "the island is still reported")
        assertEquals(0, measured.neighbourhoods, "but none of it is measured for spread")
        assertEquals(-1, measured.medianSpreadDays)

        // The interior of a map large enough to have one is measured.
        val whole = LocalSpread.of(grid(2.0), ::disk, k = 4)
        assertTrue(whole.neighbourhoods > 0)
    }

    @Test
    fun `a neighbour that never peaks is skipped rather than counted as late`() {
        // Half the map has no peak date at all. The other half is unchanged,
        // and must measure unchanged: an unscored hexagon is a gap in the
        // data, not a hexagon that turns in December.
        val whole = grid(2.0)
        val holed = whole.filterKeys { it % 100 < 10 }

        val a = LocalSpread.of(whole, ::disk, k = 4)
        val b = LocalSpread.of(holed, ::disk, k = 4)

        assertEquals(a.medianSpreadDays, b.medianSpreadDays)
        assertEquals(a.earliestPeak, b.earliestPeak)
    }

    @Test
    fun `an empty map reports nothing rather than throwing`() {
        // Asked about a state that has never been scored. Every diagnostic
        // here degrades instead of failing; see docs/testing.md.
        val empty = LocalSpread.of(emptyMap(), ::disk, k = 4)

        assertEquals(0, empty.cellsWithPeak)
        assertEquals(0, empty.neighbourhoods)
        assertEquals(null, empty.medianPeak)
        assertEquals(-1, empty.medianSpreadDays)
    }
}
