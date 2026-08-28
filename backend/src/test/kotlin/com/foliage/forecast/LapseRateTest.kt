package com.foliage.forecast

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LapseRateTest {

    @Test
    fun `climbing 1000 metres costs 6 point 5 degrees`() {
        assertEquals(3.5, LapseRate.adjust(10.0, 1000, 0.0)!!, 1e-9)
    }

    @Test
    fun `descending below the reference warms`() {
        assertEquals(16.5, LapseRate.adjust(10.0, 0, 1000.0)!!, 1e-9)
    }

    @Test
    fun `no adjustment when the cell sits at the reference elevation`() {
        assertEquals(10.0, LapseRate.adjust(10.0, 500, 500.0)!!, 1e-9)
    }

    @Test
    fun `a Vermont valley-to-ridge span is several degrees`() {
        // Champlain Valley ~50 m against Mount Mansfield ~1200 m: the kind of
        // spread a single res 5 weather cell can hide.
        val valley = LapseRate.adjust(12.0, 50, 600.0)!!
        val ridge = LapseRate.adjust(12.0, 1200, 600.0)!!
        assertTrue(abs(valley - ridge) > 7.0, "expected >7C across the span, got ${valley - ridge}")
        assertTrue(ridge < valley, "the ridge must be colder")
    }

    @Test
    fun `unknown inputs pass through rather than guessing`() {
        assertNull(LapseRate.adjust(null, 500, 100.0))
        assertEquals(10.0, LapseRate.adjust(10.0, null, 100.0))
        assertEquals(10.0, LapseRate.adjust(10.0, 500, null))
    }
}
