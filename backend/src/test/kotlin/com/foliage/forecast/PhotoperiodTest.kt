package com.foliage.forecast

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Photoperiod is the one part of this model with a right answer, so it is
 * tested against published day lengths rather than only for self-consistency.
 */
class PhotoperiodTest {

    private val vermontLat = 44.0

    @Test
    fun `equinoxes are close to twelve hours at any latitude`() {
        // Slightly over 12 h because of refraction and the sun's disc.
        listOf(0.0, 20.0, 44.0, 60.0).forEach { lat ->
            val h = Photoperiod.hours(lat, LocalDate.of(2026, 9, 22))
            assertTrue(h in 12.0..12.4, "equinox at ${lat}N was $h h")
        }
    }

    @Test
    fun `solstices match published day lengths for Vermont`() {
        // Montpelier, VT: ~15.4 h at midsummer, ~8.9 h at midwinter.
        val summer = Photoperiod.hours(vermontLat, LocalDate.of(2026, 6, 21))
        val winter = Photoperiod.hours(vermontLat, LocalDate.of(2026, 12, 21))
        assertTrue(Photoperiod.approxEquals(summer, 15.4, 0.2), "summer solstice was $summer h")
        assertTrue(Photoperiod.approxEquals(winter, 8.9, 0.2), "winter solstice was $winter h")
    }

    @Test
    fun `day length shortens monotonically through the foliage season`() {
        var previous = Double.MAX_VALUE
        var date = LocalDate.of(2026, 9, 1)
        while (!date.isAfter(LocalDate.of(2026, 11, 15))) {
            val h = Photoperiod.hours(vermontLat, date)
            assertTrue(h < previous, "day length rose on $date")
            previous = h
            date = date.plusDays(1)
        }
    }

    @Test
    fun `higher latitudes shorten faster in autumn, so they senesce earlier`() {
        val date = LocalDate.of(2026, 10, 1)
        val south = Photoperiod.hours(35.0, date)
        val north = Photoperiod.hours(47.0, date)
        assertTrue(north < south, "expected shorter October days further north")
    }

    @Test
    fun `senescence forcing is zero in summer and rises through autumn`() {
        assertEquals(0.0, Photoperiod.senescenceForcing(vermontLat, LocalDate.of(2026, 7, 1)))
        val sept = Photoperiod.senescenceForcing(vermontLat, LocalDate.of(2026, 9, 20))
        val oct = Photoperiod.senescenceForcing(vermontLat, LocalDate.of(2026, 10, 20))
        assertTrue(sept >= 0.0 && oct > sept, "forcing should accumulate: Sept $sept, Oct $oct")
    }

    @Test
    fun `days are shortening during the season`() {
        assertTrue(Photoperiod.dailyChangeMinutes(vermontLat, LocalDate.of(2026, 10, 1)) < 0)
    }

    @Test
    fun `the poles do not produce NaN`() {
        // acos of an out-of-range ratio would otherwise poison every downstream sum.
        assertEquals(24.0, Photoperiod.hours(80.0, LocalDate.of(2026, 6, 21)))
        assertEquals(0.0, Photoperiod.hours(80.0, LocalDate.of(2026, 12, 21)))
    }
}
