package com.foliage.forecast

import com.foliage.domain.WeatherKind
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The model cannot be tested for accuracy — there is no ground truth for when
 * foliage peaked. So these assert what *can* be known: bounded outputs,
 * monotonic response to each driver, and a calibration that puts peak where
 * published norms put it.
 */
class PhenologyModelTest {

    private val vermont = CellInput(latitude = 44.0, elevationM = 300)
    private val seasonStart = LocalDate.of(2026, 8, 15)

    /** A plausible Vermont autumn: cooling steadily, with a normal diurnal range. */
    private fun typicalSeason(
        warmthOffset: Double = 0.0,
        precipPerDay: Double = 3.0,
        end: LocalDate = LocalDate.of(2026, 11, 15),
    ): List<DayInput> {
        val days = mutableListOf<DayInput>()
        var d = seasonStart
        while (!d.isAfter(end)) {
            val elapsed = seasonStart.toEpochDay().let { d.toEpochDay() - it }
            val tmax = 24.0 - elapsed * 0.13 + warmthOffset
            days += DayInput(d, WeatherKind.OBSERVED, tmax, tmax - 11.0, precipPerDay)
            d = d.plusDays(1)
        }
        return days
    }

    private fun firstDayAt(stage: FoliageStage, days: List<DayInput>): LocalDate? =
        days.map { it.day }
            .firstOrNull { PhenologyModel.score(vermont, days, it).stage == stage }

    // --- calibration ------------------------------------------------------

    @Test
    fun `a typical Vermont season peaks in the first half of October`() {
        // The published norm for Vermont. FORCING_FULL is the constant that
        // sets this, so this test is what guards any change to it.
        val peak = firstDayAt(FoliageStage.PEAK, typicalSeason())
        assertTrue(peak != null, "the season never reached PEAK")
        assertTrue(
            peak!! >= LocalDate.of(2026, 9, 25) && peak <= LocalDate.of(2026, 10, 20),
            "expected peak in early-to-mid October, got $peak",
        )
    }

    @Test
    fun `the season progresses through its stages in order`() {
        val days = typicalSeason()
        val ordinals = days.map { PhenologyModel.score(vermont, days, it.day).stage.ordinal }
        assertEquals(ordinals.sorted(), ordinals, "stages must never go backwards")
        assertEquals(FoliageStage.NO_CHANGE, PhenologyModel.score(vermont, days, days.first().day).stage)
    }

    // --- bounds -----------------------------------------------------------

    @Test
    fun `progression and intensity stay within bounds under extremes`() {
        listOf(-30.0, 0.0, 30.0).forEach { offset ->
            val days = typicalSeason(warmthOffset = offset)
            days.map { it.day }.forEach { target ->
                val s = PhenologyModel.score(vermont, days, target)
                assertTrue(s.progression in 0.0..100.0, "progression out of bounds: " + s.progression)
                assertTrue(s.intensity in 0.0..100.0, "intensity out of bounds: " + s.intensity)
            }
        }
    }

    @Test
    fun `an empty series scores zero rather than throwing`() {
        val s = PhenologyModel.score(vermont, emptyList(), LocalDate.of(2026, 10, 1))
        assertEquals(0.0, s.progression)
        assertEquals(FoliageStage.NO_CHANGE, s.stage)
        assertEquals(0.0, s.confidence)
    }

    @Test
    fun `missing temperatures degrade rather than throw`() {
        val days = (0..60).map {
            DayInput(seasonStart.plusDays(it.toLong()), WeatherKind.OBSERVED, null, null, null)
        }
        val s = PhenologyModel.score(vermont, days, LocalDate.of(2026, 10, 1))
        assertTrue(s.progression > 0.0, "photoperiod alone should still drive progression")
    }

    // --- monotonicity in each driver --------------------------------------

    @Test
    fun `a colder season progresses faster than a warmer one`() {
        val target = LocalDate.of(2026, 10, 5)
        val cold = PhenologyModel.score(vermont, typicalSeason(warmthOffset = -5.0), target).progression
        val warm = PhenologyModel.score(vermont, typicalSeason(warmthOffset = 5.0), target).progression
        assertTrue(cold > warm, "cold should outpace warm: " + cold + " vs " + warm)
    }

    @Test
    fun `latitude acts through temperature, not photoperiod alone`() {
        // A deliberate negative result. Holding weather constant, the SOUTH
        // accumulates slightly more photoperiod forcing by early October,
        // because it crosses the 13 h threshold about six days earlier and the
        // north's faster decline has not yet caught up. Northern regions peak
        // earlier in reality because they are colder, not because of daylength.
        val days = typicalSeason()
        val target = LocalDate.of(2026, 10, 5)
        val north = PhenologyModel.score(CellInput(47.0, 300), days, target).progression
        val south = PhenologyModel.score(CellInput(38.0, 300), days, target).progression
        assertTrue(south >= north, "photoperiod alone should not favour the north this early")
        assertTrue(kotlin.math.abs(south - north) < 10.0, "the photoperiod-only difference should be small")
    }

    @Test
    fun `a colder northern cell peaks before a warmer southern one`() {
        // The realistic combination, and the claim the map actually makes.
        val target = LocalDate.of(2026, 10, 5)
        val north = PhenologyModel.score(
            CellInput(47.0, 300), typicalSeason(warmthOffset = -4.0), target,
        ).progression
        val south = PhenologyModel.score(
            CellInput(38.0, 300), typicalSeason(warmthOffset = 4.0), target,
        ).progression
        assertTrue(north > south, "colder north should outpace warmer south: " + north + " vs " + south)
    }

    @Test
    fun `drought accelerates progression and dulls intensity`() {
        val target = LocalDate.of(2026, 10, 5)
        val normal = 250.0
        val dry = PhenologyModel.score(vermont, typicalSeason(precipPerDay = 0.2), target, normalPrecipMm = normal)
        val wet = PhenologyModel.score(vermont, typicalSeason(precipPerDay = 5.0), target, normalPrecipMm = normal)

        assertTrue(dry.progression >= wet.progression, "drought should not slow progression")
        assertTrue(dry.intensity < wet.intensity, "drought should dull colour")
    }

    @Test
    fun `a hard freeze reduces intensity`() {
        val target = LocalDate.of(2026, 10, 20)
        val mild = typicalSeason()
        val frozen = mild.map {
            if (it.day > LocalDate.of(2026, 10, 10)) it.copy(tminC = -8.0) else it
        }
        assertTrue(
            PhenologyModel.score(vermont, frozen, target).intensity <
                PhenologyModel.score(vermont, mild, target).intensity,
            "a hard freeze should reduce intensity",
        )
    }

    @Test
    fun `a wider diurnal range gives more vivid colour`() {
        val target = LocalDate.of(2026, 10, 5)
        val narrow = typicalSeason().map { it.copy(tminC = it.tmaxC!! - 4.0) }
        val wide = typicalSeason().map { it.copy(tminC = it.tmaxC!! - 15.0) }
        assertTrue(
            PhenologyModel.score(vermont, wide, target).intensity >
                PhenologyModel.score(vermont, narrow, target).intensity,
        )
    }

    // --- stages and confidence -------------------------------------------

    @Test
    fun `stage buckets are monotonic in progression`() {
        val boundaries = (0..100).map { PhenologyModel.stageOf(it.toDouble()).ordinal }
        assertEquals(boundaries.sorted(), boundaries)
        assertEquals(FoliageStage.NO_CHANGE, PhenologyModel.stageOf(0.0))
        assertEquals(FoliageStage.PAST_PEAK, PhenologyModel.stageOf(100.0))
    }

    @Test
    fun `confidence reflects the provenance of the days behind a score`() {
        val d = LocalDate.of(2026, 10, 1)
        fun of(kind: WeatherKind) = listOf(DayInput(d, kind, 10.0, 2.0, 1.0))

        assertEquals(1.0, PhenologyModel.confidenceOf(of(WeatherKind.OBSERVED)))
        assertEquals(0.75, PhenologyModel.confidenceOf(of(WeatherKind.FORECAST)))
        assertEquals(0.4, PhenologyModel.confidenceOf(of(WeatherKind.CLIMATOLOGY)))
    }

    @Test
    fun `every score explains itself`() {
        val days = typicalSeason()
        val s = PhenologyModel.score(vermont, days, LocalDate.of(2026, 10, 5), normalPrecipMm = 200.0)
        assertEquals(5, s.factors.size)
        assertTrue(s.factors.all { it.detail.isNotBlank() }, "every factor needs a human-readable reason")
        assertTrue(s.factors.any { it.name == "Photoperiod" })
    }
}
