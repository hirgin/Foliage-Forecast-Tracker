package com.foliage.forecast

import com.foliage.domain.WeatherKind
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The species term as the model actually applies it.
 *
 * [ForestTypeGroupTest] covers the codes and multipliers in isolation; this
 * covers what they do to a score, against a cooling season rather than a
 * constant temperature -- a flat autumn accumulates cooling at a constant rate
 * and compresses the season into a fortnight, which is a property of the
 * fixture rather than of the model. See docs/model.md.
 */
class SpeciesTimingTest {

    private val lat = 44.5

    /** A real-shaped autumn: 20 C declining to 2 C over the season. */
    private fun season(): List<DayInput> {
        val start = LocalDate.of(2026, 9, 1)
        return (0 until 100).map { i ->
            val mean = 20.0 - 18.0 * (i / 99.0)
            DayInput(
                day = start.plusDays(i.toLong()),
                tmaxC = mean + 5.0,
                tminC = mean - 5.0,
                precipMm = 2.0,
                kind = WeatherKind.OBSERVED,
            )
        }
    }

    private fun peakDay(forestTypeGroup: Int?): LocalDate? {
        val days = season()
        val cell = CellInput(latitude = lat, elevationM = 300, forestTypeGroup = forestTypeGroup)
        return days.map { it.day }.firstOrNull { target ->
            CoolingDegreeDayModel.score(cell, days, target).progression >=
                CoolingDegreeDayModel.PEAK_PROGRESSION
        }
    }

    @Test
    fun `an unsampled cell scores identically to before the term existed`() {
        // The rollout guarantee. Sampling 141k cells takes many runs, and if
        // unsampled cells drifted, nobody could tell the species term's effect
        // from the sampling job's progress.
        val days = season()
        val target = LocalDate.of(2026, 10, 15)
        val unsampled = CoolingDegreeDayModel.score(
            CellInput(lat, 300, forestTypeGroup = null), days, target,
        )
        val maple = CoolingDegreeDayModel.score(
            CellInput(lat, 300, forestTypeGroup = 800), days, target,
        )
        assertEquals(maple.progression, unsampled.progression, 1e-12)
    }

    @Test
    fun `aspen peaks before maple, and oak after`() {
        val aspen = peakDay(900)
        val maple = peakDay(800)
        val oak = peakDay(500)
        assertTrue(aspen != null && maple != null, "both should reach peak in a full autumn")
        assertTrue(aspen!! < maple!!, "aspen-birch turns first")
        // Oak may not reach peak inside the fixture at all, which is itself
        // correct behaviour -- it turns late enough that a 100-day season can
        // end first.
        if (oak != null) assertTrue(oak > maple, "oak turns last")
    }

    @Test
    fun `the aspen shift is days, not hours or months`() {
        // Guards the magnitude, not just the sign. The measured residuals were
        // 7 to 12 days across three places; a term that moved aspen by one day
        // would not fix Minnesota, and one that moved it by six weeks would be
        // a different bug.
        val aspen = peakDay(900)!!
        val maple = peakDay(800)!!
        val shift = java.time.temporal.ChronoUnit.DAYS.between(aspen, maple)
        assertTrue(shift in 4..20, "aspen moved $shift days, expected roughly a week or two")
    }

    @Test
    fun `progress is reported against this forest's peak, not a maple's`() {
        // Otherwise an aspen stand is told it is 61% of the way to full colour
        // on the very day it peaks.
        val days = season()
        val aspenPeak = peakDay(900)!!
        val score = CoolingDegreeDayModel.score(
            CellInput(lat, 300, forestTypeGroup = 900), days, aspenPeak,
        )
        val cool = score.factors.first { it.name == "Cool weather" }
        val pct = Regex("""(\d+)%""").find(cool.detail)?.groupValues?.get(1)?.toInt()
        assertTrue(pct != null && pct >= 70, "should read as near full colour, was $pct%")
    }

    @Test
    fun `the explanation names the forest`() {
        val days = season()
        val target = LocalDate.of(2026, 10, 15)
        val aspen = CoolingDegreeDayModel.score(CellInput(lat, 300, 900), days, target)
            .factors.first { it.name == "Forest type" }
        assertTrue(aspen.detail.contains("aspen-birch"), "was: ${aspen.detail}")
        assertEquals("turns early", aspen.effect)

        val unsampled = CoolingDegreeDayModel.score(CellInput(lat, 300, null), days, target)
            .factors.first { it.name == "Forest type" }
        assertEquals("not surveyed", unsampled.effect)
        assertTrue(unsampled.detail.contains("not been surveyed"), "was: ${unsampled.detail}")

        // Surveyed and found empty is a different claim from never surveyed.
        // Both score at the baseline, but only one is a gap in the data, and
        // telling someone in a Minneapolis suburb that nobody has looked --
        // when somebody looked and found parkland -- is simply false.
        val noForest = CoolingDegreeDayModel.score(CellInput(lat, 300, 0), days, target)
            .factors.first { it.name == "Forest type" }
        assertEquals("little forest", noForest.effect)
        assertTrue(!noForest.detail.contains("not been surveyed"), "was: ${noForest.detail}")
    }
}
