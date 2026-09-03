package com.foliage.forecast

import com.foliage.domain.WeatherKind
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Which forests can show autumn colour at all.
 *
 * The map was colouring forests the model itself describes as incapable of it.
 * Prescott, Arizona was given a peak on 8 November while its own explanation
 * read "mostly evergreens, which do not put on an autumn display", and Ocala,
 * Florida sat at PEAK on 11 December. Those were the scattered hexagons
 * turning at odd times in states nobody visits for the leaves.
 */
class AutumnColourTest {

    private val lat = 44.5

    /** A real-shaped autumn: 20 C declining to 2 C, enough to peak anything. */
    private fun season(): List<DayInput> {
        val start = LocalDate.of(2026, 9, 1)
        return (0 until 100).map { i ->
            val mean = 20.0 - 18.0 * (i / 99.0)
            DayInput(start.plusDays(i.toLong()), WeatherKind.OBSERVED, mean + 5.0, mean - 5.0, 2.0)
        }
    }

    private fun scoreAt(forestTypeGroup: Int?, day: LocalDate) =
        CoolingDegreeDayModel.score(CellInput(lat, 300, forestTypeGroup), season(), day)

    @Test
    fun `an evergreen forest never turns, however cold it gets`() {
        // The weather here is more than enough to peak a maple. That is the
        // point: cooling accumulates over a spruce stand exactly as it does
        // over a maple, and nothing in the model used to stop it.
        val end = LocalDate.of(2026, 12, 9)
        for (code in listOf(100, 120, 160, 180, 200, 220, 260, 300)) {
            val score = scoreAt(code, end)
            assertEquals(0.0, score.progression, "conifer $code should not turn")
            assertEquals(FoliageStage.NO_CHANGE, score.stage)
        }
    }

    @Test
    fun `a maple in the same weather turns completely`() {
        // Guards against the suppression being accidentally universal.
        val score = scoreAt(800, LocalDate.of(2026, 12, 9))
        assertTrue(score.progression > 90.0, "was ${score.progression}")
    }

    @Test
    fun `western larch keeps its season`() {
        // A conifer that drops its needles and goes bright gold. Western
        // Montana's larch is a display people travel for, and suppressing it
        // on a taxonomic technicality would be the wrong kind of correct.
        val score = scoreAt(320, LocalDate.of(2026, 12, 9))
        assertEquals(ForestTypeGroup.LARCH, ForestTypeGroup.forCode(320))
        assertTrue(score.progression > 90.0, "larch should turn, was ${score.progression}")
    }

    @Test
    fun `a display that never happens is not a vivid one`() {
        assertEquals(0.0, scoreAt(120, LocalDate.of(2026, 10, 20)).intensity)
        assertTrue(scoreAt(800, LocalDate.of(2026, 10, 20)).intensity > 0.0)
    }

    @Test
    fun `an unsurveyed cell is never suppressed`() {
        // The rollout guarantee. A cell with no type behaves exactly as it did
        // before any of this existed, so a gap in the survey cannot silently
        // erase forest from the map.
        assertTrue(scoreAt(null, LocalDate.of(2026, 12, 9)).progression > 90.0)
    }

    @Test
    fun `suppression reads types, not only group codes`() {
        // Stored values are individual FIA types; no list of group codes would
        // catch 128, which is a spruce-fir type and still a conifer.
        assertEquals(0.0, scoreAt(128, LocalDate.of(2026, 12, 9)).progression)
        assertTrue(scoreAt(841, LocalDate.of(2026, 12, 9)).progression > 90.0)
    }
}
