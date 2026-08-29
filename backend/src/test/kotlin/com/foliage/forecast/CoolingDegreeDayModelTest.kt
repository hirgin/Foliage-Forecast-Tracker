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
        assertEquals("not started", score.factors.single { it.name == "Shorter days" }.effect)
    }

    @Test
    fun `the gate opens once days are short enough`() {
        val days = season(meanC = 11.0)
        val late = CoolingDegreeDayModel.score(CellInput(44.0, 200), days, LocalDate.of(2026, 10, 15))
        assertEquals("has started", late.factors.single { it.name == "Shorter days" }.effect)
        assertTrue(late.factors.single { it.name == "Cool weather" }.value > 0)
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
    fun `colour comes on more slowly than a straight line`() {
        // The curve is not linear from the moment the gate opens: a forest does
        // not start turning the day the trigger fires. Asserted against the
        // straight line to peak, which is the thing it must beat.
        //
        // This replaced an assertion that progression keeps accelerating. That
        // was true of the convex power curve used before, and is not true of a
        // saturating one -- the inflection now falls about a week after the
        // gate, which is the whole reason peak lasts.
        val days = season(meanC = 12.0)
        val early = LocalDate.of(2026, 9, 11)
        val p = CoolingDegreeDayModel.score(CellInput(44.0, 200), days, early)
        val cooling = p.factors.single { it.name == "Cool weather" }.value
        val straightLine = CoolingDegreeDayModel.PEAK_PROGRESSION * cooling / CoolingDegreeDayModel.S_PEAK

        assertTrue(cooling > 0, "nothing accumulated by $early, so this proves nothing")
        assertTrue(
            p.progression < straightLine,
            "expected a slow start: ${p.progression} against a straight line of $straightLine",
        )
    }

    @Test
    fun `one place's season runs for weeks`() {
        // First colour to past peak at a single place. Too short and the map
        // flicks from green to bare between visits; the old curve managed
        // barely three weeks with only days of it at peak.
        val days = season(meanC = 12.0)
        val stageOn = { d: LocalDate -> CoolingDegreeDayModel.score(CellInput(44.0, 200), days, d).stage }

        var first: LocalDate? = null
        var past: LocalDate? = null
        var d = seasonStart
        while (!d.isAfter(seasonEnd)) {
            if (first == null && stageOn(d) != FoliageStage.NO_CHANGE) first = d
            if (past == null && stageOn(d) == FoliageStage.PAST_PEAK) past = d
            d = d.plusDays(1)
        }
        assertTrue(first != null && past != null, "expected a full season, got $first..$past")
        val length = past!!.toEpochDay() - first!!.toEpochDay()
        assertTrue(length >= 18, "a place's season lasted only $length days")
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
            .factors.single { it.name == "Cool weather" }.value
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
    fun `says plainly when a figure came from a typical year`() {
        // Worded for a reader rather than a modeller: "normals" is the term of
        // art, "a typical year" is what it means. The panel still has to admit
        // it, whichever words it uses.
        val score = CoolingDegreeDayModel.score(
            CellInput(44.0, 200), season(12.0, kind = WeatherKind.CLIMATOLOGY), seasonEnd,
        )
        val detail = score.factors.single { it.name == "Cool weather" }.detail
        assertTrue("typical year" in detail, "should say where the figure came from: $detail")
    }

    @Test
    fun `explains itself without jargon`() {
        // The panel is read by someone standing in a forest, not someone
        // reading the source. These are the words that sent people to a
        // glossary.
        val jargon = listOf(
            "senescence", "photoperiod", "degree-day", "degree day",
            "diurnal", "climatolog", "normals", "forcing", "provenance",
        )
        val score = CoolingDegreeDayModel.score(
            CellInput(44.0, 200), season(12.0, kind = WeatherKind.CLIMATOLOGY), seasonEnd,
            normalPrecipMm = 200.0, precipFrom = seasonStart,
        )
        for (f in score.factors) {
            val text = "${f.name} ${f.effect} ${f.detail}".lowercase()
            for (word in jargon) {
                assertTrue(word !in text, "\"$word\" appears in: ${f.name} — ${f.detail}")
            }
        }
    }

    // --- how long peak lasts ---------------------------------------------

    private fun daysAtPeak(days: List<DayInput>): Int {
        var count = 0
        var d = seasonStart
        while (!d.isAfter(seasonEnd)) {
            if (CoolingDegreeDayModel.score(CellInput(44.0, 200), days, d).stage == FoliageStage.PEAK) count++
            d = d.plusDays(1)
        }
        return count
    }

    @Test
    fun `peak holds for about a week, not a couple of days`() {
        // A plain power curve is convex -- steepest exactly at peak -- so a
        // stand tore through the peak band in under three days. Real peak
        // colour holds for something closer to a week, which is what the
        // saturating shape buys.
        val held = daysAtPeak(season(meanC = 12.0))
        assertTrue(held >= 6, "peak lasted only $held days")
        assertTrue(held <= 14, "peak lasted $held days, which is too long to be peak")
    }

    @Test
    fun `a milder place holds peak longer than a cold one`() {
        // Leaf drop is driven by frost and wind, which arrive sooner in the
        // north, so a northern peak is the shorter one. This is the
        // "depending" in how long peak lasts.
        val cold = daysAtPeak(season(meanC = 10.5))
        val mild = daysAtPeak(season(meanC = 15.0))
        assertTrue(mild > cold, "mild held $mild days, cold held $cold")
    }

    @Test
    fun `the saturating curve does not move peak`() {
        // The whole point of deriving SCALE from S_PEAK: shape changes how
        // long stages last, never when peak arrives. If this drifts, every
        // calibrated date in ADR-0008 drifts with it.
        val atPeak = 100.0 * (1 - Math.exp(-Math.pow(
            CoolingDegreeDayModel.S_PEAK / CoolingDegreeDayModel.SCALE,
            CoolingDegreeDayModel.SHAPE,
        )))
        assertEquals(CoolingDegreeDayModel.PEAK_PROGRESSION, atPeak, 0.01)
    }

    @Test
    fun `progression approaches but never exceeds fully turned`() {
        // A saturating curve is the honest shape: a stand is never more than
        // completely turned, however cold it gets.
        val brutal = season(meanC = -5.0)
        val p = CoolingDegreeDayModel.score(CellInput(44.0, 200), brutal, seasonEnd).progression
        assertTrue(p <= 100.0, "progression $p exceeded 100")
        assertTrue(p > 95.0, "a whole freezing season should finish the canopy, got $p")
    }
}
