package com.foliage.forecast

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.foliage.domain.WeatherKind
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The model against the peak windows other forecasters publish.
 *
 * This project has twice fitted constants against figures somebody remembered
 * -- ADR-0008 carries a Vermont target of 5-12 October that no published
 * source agrees with -- and twice had to unpick it. The targets now live in
 * docs/published-peak-windows.md and the weather behind them in
 * fixtures/reference-weather.json, so the fit is a test rather than a number
 * in a comment that nothing checks.
 *
 * **This asserts when peak *ends*, which is the half nobody was measuring.**
 * Entry dates have been inside their windows through every version of this
 * model, including versions that turned New England brown in the first week of
 * October. A calibration that only ever looked at entry could not see that,
 * and did not.
 */
class PublishedWindowsTest {

    private data class Day(val day: String, val mean: Double)
    private data class Town(
        val name: String,
        val lat: Double,
        val lon: Double,
        val window: List<String>,
        val days: List<Day>,
    )

    private val towns: List<Town> = jacksonObjectMapper().readValue(
        javaClass.getResourceAsStream("/fixtures/reference-weather.json")!!,
    )

    /**
     * The northern places, which are what a complaint about the map going
     * brown early is about. The deep south is a different defect with a
     * different fix; see docs/published-peak-windows.md.
     */
    private val north = setOf(
        "Fort Kent ME", "Stowe VT", "Concord NH", "Bangor ME",
        "Marquette MI", "Duluth MN", "Wausau WI",
    )

    /**
     * The fixture carries daily means; the model reads extremes. A fixed 10 C
     * spread reproduces the mean exactly, which is the only quantity the
     * cooling term uses.
     */
    private fun inputs(town: Town): List<DayInput> = town.days.map {
        DayInput(LocalDate.parse(it.day), WeatherKind.CLIMATOLOGY, it.mean + 5.0, it.mean - 5.0, 3.0)
    }

    /** The day a town stops being drawn as PEAK and starts being drawn as past it. */
    private fun leavesPeak(town: Town): LocalDate? {
        val days = inputs(town)
        val cell = CellInput(town.lat, null, null)
        val first = days.first().day
        var reachedPeak = false
        var d = first
        while (!d.isAfter(days.last().day)) {
            val stage = CoolingDegreeDayModel.score(cell, days, d, seasonFirstDay = first).stage
            if (stage == FoliageStage.PEAK) reachedPeak = true
            if (reachedPeak && stage == FoliageStage.PAST_PEAK) return d
            d = d.plusDays(1)
        }
        return null
    }

    @Test
    fun `northern peak does not end before the published windows close`() {
        // Signed, and the sign is the whole point: negative means the map has
        // called a region finished while every published forecast still calls
        // it peak. At the old boundary of 90 this averaged -3 days and Fort
        // Kent was -8, going brown on 2 October against a window running to
        // the 10th.
        val errors = towns.filter { it.name in north }.map { town ->
            val end = LocalDate.parse(town.window[1])
            val left = leavesPeak(town)
            assertTrue(left != null, "${town.name} never reaches peak at all")
            town.name to java.time.temporal.ChronoUnit.DAYS.between(end, left).toInt()
        }

        val mean = errors.sumOf { it.second }.toDouble() / errors.size
        assertTrue(
            mean > -1.5 && mean < 1.5,
            "northern peak should end about when the published windows do, " +
                "but the mean error is $mean days: $errors",
        )

        // No single place badly early either -- a mean of zero could hide one
        // town finishing a week before its window and another a week after.
        val worst = errors.minBy { it.second }
        assertTrue(
            worst.second >= -5,
            "${worst.first} leaves peak ${-worst.second} days before its window closes",
        )
    }

    @Test
    fun `peak is entered inside the published window`() {
        // The property the model already had, kept so that a future fix to the
        // exit date cannot quietly buy it by moving entry.
        val missed = towns.mapNotNull { town ->
            val days = inputs(town)
            val cell = CellInput(town.lat, null, null)
            val first = days.first().day
            var d = first
            var entered: LocalDate? = null
            while (entered == null && !d.isAfter(days.last().day)) {
                if (CoolingDegreeDayModel.score(cell, days, d, seasonFirstDay = first)
                        .progression >= 75.0
                ) {
                    entered = d
                }
                d = d.plusDays(1)
            }
            val lo = LocalDate.parse(town.window[0])
            val hi = LocalDate.parse(town.window[1])
            if (entered == null || entered < lo || entered > hi) town.name to entered else null
        }

        // The Gulf coast is knowingly late under the shipped constants and is
        // the subject of the floor refit; everywhere else must land in window.
        val allowed = setOf("Jackson MS", "Baton Rouge LA", "Ocala FL")
        assertEquals(
            emptyList(),
            missed.filterNot { it.first in allowed },
            "these peaked outside their published window",
        )
    }

    @Test
    fun `the peak band boundary is where the fit put it`() {
        // Pinned directly, because the two tests above are aggregates and a
        // small drift here would show up in them only as a slow slide.
        assertEquals(FoliageStage.PEAK, PhenologyModel.stageOf(90.0))
        assertEquals(FoliageStage.PEAK, PhenologyModel.stageOf(93.9))
        assertEquals(FoliageStage.PAST_PEAK, PhenologyModel.stageOf(94.0))
    }
}
