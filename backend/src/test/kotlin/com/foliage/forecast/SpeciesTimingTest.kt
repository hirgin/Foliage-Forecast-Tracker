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
            PeakDateModel.score(cell, days, target).progression >=
                PeakDateModel.PEAK_ENTRY
        }
    }

    @Test
    fun `an unsampled cell scores identically to before the term existed`() {
        // The rollout guarantee. Sampling 141k cells takes many runs, and if
        // unsampled cells drifted, nobody could tell the species term's effect
        // from the sampling job's progress.
        val days = season()
        val target = LocalDate.of(2026, 10, 15)
        val unsampled = PeakDateModel.score(
            CellInput(lat, 300, forestTypeGroup = null), days, target,
        )
        val maple = PeakDateModel.score(
            CellInput(lat, 300, forestTypeGroup = 800), days, target,
        )
        assertEquals(maple.progression, unsampled.progression, 1e-12)
    }

    @Test
    fun `aspen peaks before maple`() {
        val aspen = peakDay(900)
        val maple = peakDay(800)
        assertTrue(aspen != null && maple != null, "both should reach peak in a full autumn")
        assertTrue(aspen!! < maple!!, "aspen-birch turns first")
        // Oak no longer carries a multiplier: refitting it alongside the
        // photoperiod floor put it at the baseline, because what it had been
        // standing in for was latitude. See ForestTypeGroupTest.
        assertEquals(maple, peakDay(500))
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
        //
        // Under the cooling model this needed a separate per-species threshold
        // to report against, and got it wrong for a long time. Here the curve
        // is drawn around each cell's own date, so a stand is at exactly the
        // peak entry value on its own peak day whatever it is made of -- the
        // property is structural rather than something to keep in step.
        val days = season()
        val aspenPeak = peakDay(900)!!
        val maplePeak = peakDay(800)!!
        val aspen = PeakDateModel.score(CellInput(lat, 300, 900), days, aspenPeak)
        val maple = PeakDateModel.score(CellInput(lat, 300, 800), days, maplePeak)

        assertTrue(
            aspen.progression >= PeakDateModel.PEAK_ENTRY,
            "aspen should read as peak on its own peak day, was ${aspen.progression}",
        )
        assertEquals(
            maple.progression, aspen.progression, 0.5,
            "every forest should read the same on its own peak day",
        )
        // And the days themselves differ, which is the point of the term.
        assertTrue(aspenPeak < maplePeak, "aspen $aspenPeak should precede maple $maplePeak")
    }

    @Test
    fun `the explanation names the forest`() {
        val days = season()
        val target = LocalDate.of(2026, 10, 15)
        val aspen = PeakDateModel.score(CellInput(lat, 300, 900), days, target)
            .factors.first { it.name == "Forest type" }
        assertTrue(aspen.detail.contains("aspen-birch"), "was: ${aspen.detail}")
        assertEquals("turns early", aspen.effect)

        val unsampled = PeakDateModel.score(CellInput(lat, 300, null), days, target)
            .factors.first { it.name == "Forest type" }
        assertEquals("not surveyed", unsampled.effect)
        assertTrue(unsampled.detail.contains("not been surveyed"), "was: ${unsampled.detail}")

        // Surveyed and found empty is a different claim from never surveyed.
        // Both score at the baseline, but only one is a gap in the data, and
        // telling someone in a Minneapolis suburb that nobody has looked --
        // when somebody looked and found parkland -- is simply false.
        val noForest = PeakDateModel.score(CellInput(lat, 300, 0), days, target)
            .factors.first { it.name == "Forest type" }
        assertEquals("little forest", noForest.effect)
        assertTrue(!noForest.detail.contains("not been surveyed"), "was: ${noForest.detail}")
    }
}
