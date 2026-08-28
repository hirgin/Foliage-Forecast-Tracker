package com.foliage.ingest.weather.hrrr

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HourlyAggregatorTest {

    private val day = LocalDate.of(2026, 10, 8)

    /** A plausible autumn day: cold before dawn, peaking mid-afternoon. */
    private fun diurnal(min: Double = 4.0, max: Double = 16.0): List<Double?> =
        (0..23).map { h ->
            val phase = Math.sin((h - 9) / 24.0 * 2 * Math.PI)
            min + (max - min) * (phase + 1) / 2
        }

    @Test
    fun `takes the maximum and minimum across the day`() {
        val r = HourlyAggregator.daily(day, diurnal())!!
        assertEquals(4.0, r.tminC!!, 0.01)
        assertEquals(16.0, r.tmaxC!!, 0.01)
        assertEquals(day, r.day)
    }

    @Test
    fun `ignores missing hours`() {
        val hours = diurnal().toMutableList()
        hours[3] = null
        hours[14] = null
        val r = HourlyAggregator.daily(day, hours)
        assertTrue(r != null, "22 present hours should still produce a day")
    }

    @Test
    fun `drops a day assembled from too few hours`() {
        // Three morning readings would report a maximum that is simply wrong,
        // not merely imprecise. Better to leave the day to the layer beneath.
        val sparse = List(24) { h -> if (h < 3) 5.0 else null }
        assertNull(HourlyAggregator.daily(day, sparse))
    }

    @Test
    fun `the threshold is inclusive`() {
        val exactly = List(24) { h -> if (h < HourlyAggregator.MIN_HOURS) 5.0 else null }
        val oneShort = List(24) { h -> if (h < HourlyAggregator.MIN_HOURS - 1) 5.0 else null }
        assertTrue(HourlyAggregator.daily(day, exactly) != null)
        assertNull(HourlyAggregator.daily(day, oneShort))
    }

    @Test
    fun `an entirely missing day is null, not a zero-degree day`() {
        assertNull(HourlyAggregator.daily(day, List(24) { null }))
        assertNull(HourlyAggregator.daily(day, emptyList()))
    }

    @Test
    fun `precipitation and radiation pass through untouched`() {
        // HRRR's surface analysis carries no precipitation accumulation, so
        // these come from whichever source supplied them. Inventing a zero
        // here would silently fabricate a dry day.
        val r = HourlyAggregator.daily(day, diurnal(), precipMm = 12.5, radiationMj = 9.0)!!
        assertEquals(12.5, r.precipMm)
        assertEquals(9.0, r.radiationMj)

        val without = HourlyAggregator.daily(day, diurnal())!!
        assertNull(without.precipMm)
        assertNull(without.radiationMj)
    }

    @Test
    fun `handles a day that never rises above freezing`() {
        val r = HourlyAggregator.daily(day, List(24) { -8.0 })!!
        assertEquals(-8.0, r.tmaxC)
        assertEquals(-8.0, r.tminC)
    }

    // --- transpose --------------------------------------------------------

    @Test
    fun `transposes hour-major samples into point-major series`() {
        // Two hours, three points.
        val byHour = listOf(
            listOf(1.0, 2.0, 3.0),
            listOf(4.0, 5.0, 6.0),
        )
        assertEquals(
            listOf(listOf(1.0, 4.0), listOf(2.0, 5.0), listOf(3.0, 6.0)),
            HourlyAggregator.transpose(byHour, pointCount = 3),
        )
    }

    @Test
    fun `transpose preserves nulls in position`() {
        val byHour = listOf(listOf(1.0, null), listOf(null, 4.0))
        assertEquals(
            listOf(listOf(1.0, null), listOf(null, 4.0)),
            HourlyAggregator.transpose(byHour, pointCount = 2),
        )
    }

    @Test
    fun `transpose rejects a ragged hour`() {
        // A short row would silently shift every later point's samples onto the
        // wrong cell -- the same class of bug as the canopy locationId case.
        val ragged = listOf(listOf(1.0, 2.0), listOf(3.0))
        assertFailsWith<IllegalArgumentException> { HourlyAggregator.transpose(ragged, 2) }
    }

    @Test
    fun `transpose of no hours yields an empty series per point`() {
        assertEquals(listOf(emptyList(), emptyList()), HourlyAggregator.transpose(emptyList(), 2))
    }
}
