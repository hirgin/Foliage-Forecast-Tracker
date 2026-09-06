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
    fun `northern peak ends after the consensus peak, not before it`() {
        // Anchored on the consensus dates, not on the published windows'
        // closing dates. Those closes are the softest number in this file --
        // the windows are 9 to 17 days wide and describe a whole region's
        // spread, not one place's season -- and pinning the exit to them was
        // pinning the end of a season that started at the wrong time.
        //
        // The defect is going brown while a place is still at peak. So: never
        // before its consensus peak date, and holding colour for a plausible
        // week or two after it rather than a month.
        val errors = towns.filter { it.name in north }.map { town ->
            val left = leavesPeak(town)
            assertTrue(left != null, "${town.name} never reaches peak at all")
            town.name to java.time.temporal.ChronoUnit.DAYS
                .between(consensus.getValue(town.name), left).toInt()
        }

        val early = errors.filter { it.second < 0 }
        assertEquals(
            emptyList(),
            early,
            "these go brown before their consensus peak date",
        )

        val mean = errors.sumOf { it.second }.toDouble() / errors.size
        assertTrue(
            mean < 20.0,
            "the north holds peak $mean days past its consensus peak: $errors",
        )
    }

    /**
     * The consensus peak date of five published forecasts, per place. See
     * docs/published-peak-windows.md.
     *
     * A single date rather than a window, and that is the entire point. The
     * published windows are 9 to 17 days wide, and this model spent its whole
     * life landing at their *early edge* -- inside every one of them, scoring
     * as a good fit, and a week early on the map. A target that wide cannot
     * express the error a person sees.
     */
    private val consensus = mapOf(
        "Fort Kent ME" to LocalDate.of(2026, 10, 2),
        "Duluth MN" to LocalDate.of(2026, 10, 7),
        "Stowe VT" to LocalDate.of(2026, 10, 9),
        "Marquette MI" to LocalDate.of(2026, 10, 9),
        "Wausau WI" to LocalDate.of(2026, 10, 9),
        "Concord NH" to LocalDate.of(2026, 10, 10),
        "Bangor ME" to LocalDate.of(2026, 10, 12),
        "Elkins WV" to LocalDate.of(2026, 10, 15),
        "Columbus OH" to LocalDate.of(2026, 10, 20),
        "Lexington KY" to LocalDate.of(2026, 10, 22),
        "Asheville NC" to LocalDate.of(2026, 10, 22),
        "Roanoke VA" to LocalDate.of(2026, 10, 25),
        "Knoxville TN" to LocalDate.of(2026, 10, 28),
        "Atlanta GA" to LocalDate.of(2026, 11, 3),
        "Birmingham AL" to LocalDate.of(2026, 11, 5),
        "Jackson MS" to LocalDate.of(2026, 11, 5),
        "Baton Rouge LA" to LocalDate.of(2026, 11, 20),
        "Ocala FL" to LocalDate.of(2026, 11, 25),
    )

    private fun entersPeak(town: Town): LocalDate? {
        val days = inputs(town)
        val cell = CellInput(town.lat, null, null)
        val first = days.first().day
        var d = first
        while (!d.isAfter(days.last().day)) {
            if (CoolingDegreeDayModel.score(cell, days, d, seasonFirstDay = first)
                    .progression >= 75.0
            ) {
                return d
            }
            d = d.plusDays(1)
        }
        return null
    }

    @Test
    fun `the north does not peak early`() {
        // The bias, not the scatter. Individual towns can sit either side --
        // species and elevation are not in this fixture -- but the north
        // drifting *systematically* early is the defect that shipped: a mean
        // of -5 days, and -9 at both Stowe and Bangor, while every one of them
        // sat inside its published window.
        val errors = towns.filter { it.name in north }.map { town ->
            val p = entersPeak(town)
            assertTrue(p != null, "${town.name} never peaks")
            town.name to java.time.temporal.ChronoUnit.DAYS
                .between(consensus.getValue(town.name), p).toInt()
        }

        val bias = errors.sumOf { it.second }.toDouble() / errors.size
        assertTrue(
            bias > -3.0,
            "the north is peaking $bias days early against the five-forecast " +
                "consensus: $errors",
        )
    }

    @Test
    fun `peak dates track the consensus across the country`() {
        val errors = towns.mapNotNull { town ->
            entersPeak(town)?.let {
                town.name to java.time.temporal.ChronoUnit.DAYS
                    .between(consensus.getValue(town.name), it).toInt()
            }
        }
        assertEquals(towns.size, errors.size, "every reference town must peak")

        val mae = errors.sumOf { kotlin.math.abs(it.second) }.toDouble() / errors.size
        // The deep south is knowingly late and is the floor's problem, not
        // this one; 6 days national keeps that visible without pinning it.
        assertTrue(mae < 6.0, "mean absolute error is $mae days: $errors")
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
