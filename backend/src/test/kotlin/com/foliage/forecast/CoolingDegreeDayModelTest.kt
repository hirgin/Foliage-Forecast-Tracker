package com.foliage.forecast

import com.foliage.domain.WeatherKind
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What can be known about the cooling model without ground truth: bounded
 * output, monotonic response to each driver, and — the reason it exists — that
 * a colder place turns earlier than a warmer one by a realistic margin.
 *
 * The model it replaces passed monotonicity too. It failed on *magnitude*, so
 * these assert sizes and not just directions.
 */
class CoolingDegreeDayModelTest {

    private val seasonStart = LocalDate.of(2026, 9, 1)
    private val seasonEnd = LocalDate.of(2026, 11, 15)

    /** A season at a steady mean temperature, which is what sets the pace. */
    private fun season(
        meanC: Double,
        diurnalC: Double = 10.0,
        kind: WeatherKind = WeatherKind.CLIMATOLOGY,
        precipPerDay: Double = 3.0,
    ): List<DayInput> {
        val days = mutableListOf<DayInput>()
        var d = seasonStart
        while (!d.isAfter(seasonEnd)) {
            days += DayInput(d, kind, meanC + diurnalC / 2, meanC - diurnalC / 2, precipPerDay)
            d = d.plusDays(1)
        }
        return days
    }

    private fun peakDay(days: List<DayInput>, latitude: Double = 44.0): LocalDate? {
        var d = seasonStart
        while (!d.isAfter(seasonEnd)) {
            val s = CoolingDegreeDayModel.score(CellInput(latitude, 200), days, d)
            if (s.stage == FoliageStage.PEAK) return d
            d = d.plusDays(1)
        }
        return null
    }

    // --- the reason this model exists ------------------------------------

    @Test
    fun `a colder place peaks earlier, by weeks not days`() {
        // The failure that motivated the redesign. Maine and Rhode Island
        // differ by about 5 C of autumn mean, and peak about three weeks apart.
        // The photoperiod model put them five days apart at best.
        val cold = peakDay(season(meanC = 10.5))
        val mild = peakDay(season(meanC = 15.9))

        assertTrue(cold != null && mild != null, "both should reach peak in a season")
        val gap = mild!!.toEpochDay() - cold!!.toEpochDay()
        assertTrue(gap >= 14, "expected a realistic separation, got $gap days")
    }

    @Test
    fun `peak arrives later the warmer it gets, at every step`() {
        val peaks = listOf(9.0, 11.0, 13.0, 15.0).map { peakDay(season(it)) }
        assertTrue(peaks.all { it != null }, "every one of these should peak: $peaks")
        peaks.zipWithNext().forEach { (earlier, later) ->
            assertTrue(later!! > earlier!!, "$later should follow $earlier")
        }
    }

    @Test
    fun `a place too warm to cool never reaches peak`() {
        // Above the base temperature nothing accumulates, which is what stops
        // the model claiming a foliage season in a subtropical autumn.
        assertEquals(null, peakDay(season(meanC = 21.0)))
    }

    // --- the photoperiod gate --------------------------------------------

    @Test
    fun `cold before the days shorten banks no progress`() {
        // The gate is the whole reason photoperiod is still here. A cold
        // August must not start the season early.
        val august = LocalDate.of(2026, 8, 1)
        val days = (0..20).map {
            DayInput(august.plusDays(it.toLong()), WeatherKind.OBSERVED, 8.0, 2.0, 3.0)
        }
        val score = CoolingDegreeDayModel.score(CellInput(44.0, 200), days, august.plusDays(20))
        assertEquals(0.0, score.progression, 1e-9)
        assertEquals("not yet", score.factors.single { it.name == "Photoperiod" }.effect)
    }

    @Test
    fun `the gate opens once days are short enough`() {
        val days = season(meanC = 11.0)
        val late = CoolingDegreeDayModel.score(CellInput(44.0, 200), days, LocalDate.of(2026, 10, 15))
        assertEquals("has triggered", late.factors.single { it.name == "Photoperiod" }.effect)
        assertTrue(late.factors.single { it.name == "Cooling" }.value > 0)
    }

    // --- bounds and shape -------------------------------------------------

    @Test
    fun `progression stays within bounds all season`() {
        for (mean in listOf(2.0, 8.0, 14.0, 19.0, 25.0)) {
            var d = seasonStart
            while (!d.isAfter(seasonEnd)) {
                val p = CoolingDegreeDayModel.score(CellInput(44.0, 200), season(mean), d).progression
                assertTrue(p in 0.0..100.0, "progression $p out of bounds at $d, mean $mean")
                d = d.plusDays(7)
            }
        }
    }

    @Test
    fun `progression never goes backwards`() {
        val days = season(meanC = 11.0)
        var previous = -1.0
        var d = seasonStart
        while (!d.isAfter(seasonEnd)) {
            val p = CoolingDegreeDayModel.score(CellInput(44.0, 200), days, d).progression
            assertTrue(p >= previous - 1e-9, "progression fell from $previous to $p at $d")
            previous = p
            d = d.plusDays(1)
        }
    }

    @Test
    fun `colour comes on slowly at first, then accelerates`() {
        // What GAMMA above 1 buys: a linear ramp would turn a forest at a
        // constant rate from the moment the gate opened, which is not what a
        // forest does. Asserted as convexity rather than as a particular stage
        // on a particular date, since that is the actual property.
        val days = season(meanC = 12.0)
        val at = { d: LocalDate -> CoolingDegreeDayModel.score(CellInput(44.0, 200), days, d).progression }

        val early = at(LocalDate.of(2026, 9, 20)) - at(LocalDate.of(2026, 9, 10))
        val later = at(LocalDate.of(2026, 9, 30)) - at(LocalDate.of(2026, 9, 20))
        assertTrue(later > early, "expected acceleration: gained $early then $later")
    }

    @Test
    fun `peak lands at the calibrated accumulation`() {
        // S_PEAK is the one fitted parameter, and it has to keep meaning what
        // it was calibrated to: the accumulation at which a stand is at peak.
        val days = season(meanC = 11.0)
        val peak = peakDay(days)
        assertTrue(peak != null)
        val cooling = CoolingDegreeDayModel
            .score(CellInput(44.0, 200), days, peak!!)
            .factors.single { it.name == "Cooling" }.value
        assertTrue(
            cooling in (CoolingDegreeDayModel.S_PEAK * 0.85)..(CoolingDegreeDayModel.S_PEAK * 1.15),
            "peak reached at $cooling, expected near ${CoolingDegreeDayModel.S_PEAK}",
        )
    }

    // --- terms carried over unchanged -------------------------------------

    @Test
    fun `drought brings peak forward and dulls it`() {
        // Measured mid-season. At the season's end both are fully turned and
        // clamped at 100, so the comparison would be vacuous.
        val midSeason = LocalDate.of(2026, 10, 1)
        val normal = 4.0 * 30
        val lush = CoolingDegreeDayModel.score(
            CellInput(44.0, 200), season(meanC = 12.0, precipPerDay = 4.0), midSeason, normal, seasonStart,
        )
        val parched = CoolingDegreeDayModel.score(
            CellInput(44.0, 200), season(meanC = 12.0, precipPerDay = 0.4), midSeason, normal, seasonStart,
        )
        assertTrue(
            parched.progression > lush.progression,
            "drought should accelerate: ${parched.progression} vs ${lush.progression}",
        )
        assertTrue(parched.intensity < lush.intensity, "drought should dull")
    }

    @Test
    fun `a wide diurnal range makes for a more vivid display`() {
        val flat = CoolingDegreeDayModel.score(CellInput(44.0, 200), season(12.0, diurnalC = 4.0), seasonEnd)
        val wide = CoolingDegreeDayModel.score(CellInput(44.0, 200), season(12.0, diurnalC = 14.0), seasonEnd)
        assertTrue(wide.intensity > flat.intensity)
    }

    @Test
    fun `a hard freeze strips rather than colours`() {
        val freezing = season(meanC = 12.0).map {
            if (it.day.month.value == 10) it.copy(tminC = -8.0, tmaxC = 4.0) else it
        }
        val score = CoolingDegreeDayModel.score(CellInput(44.0, 200), freezing, seasonEnd)
        assertEquals("damaging", score.factors.single { it.name == "Frost" }.effect)
        assertTrue(score.intensity < 50.0, "a hard freeze should cost intensity")
    }

    @Test
    fun `confidence still follows provenance`() {
        val observed = CoolingDegreeDayModel.score(
            CellInput(44.0, 200), season(12.0, kind = WeatherKind.OBSERVED), seasonEnd,
        )
        val guessed = CoolingDegreeDayModel.score(
            CellInput(44.0, 200), season(12.0, kind = WeatherKind.CLIMATOLOGY), seasonEnd,
        )
        assertTrue(observed.confidence > guessed.confidence)
    }

    @Test
    fun `says plainly when a figure came from normals`() {
        val score = CoolingDegreeDayModel.score(
            CellInput(44.0, 200), season(12.0, kind = WeatherKind.CLIMATOLOGY), seasonEnd,
        )
        assertTrue("normals" in score.factors.single { it.name == "Cooling" }.detail)
    }
}
