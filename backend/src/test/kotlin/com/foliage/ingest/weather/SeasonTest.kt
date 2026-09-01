package com.foliage.ingest.weather

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SeasonTest {

    private val season = Season("09-01", "11-15")

    @Test
    fun `resolves month-day config into concrete dates for a year`() {
        assertEquals(LocalDate.of(2026, 9, 1), season.start(2026))
        assertEquals(LocalDate.of(2026, 11, 15), season.end(2026))
    }

    @Test
    fun `spans 76 inclusive days`() {
        // 30 September + 31 October + 15 November.
        val days = season.days(2026)
        assertEquals(76, days.size)
        assertEquals(season.start(2026), days.first())
        assertEquals(season.end(2026), days.last())
    }

    @Test
    fun `the season is far longer than the forecast horizon`() {
        // The premise of ADR-0005: most of the season can only ever be
        // climatology. If this ever stops being true the design can simplify.
        assertTrue(
            season.days(2026).size > WeatherKindPolicy.FORECAST_HORIZON_DAYS * 2,
            "season should substantially exceed the forecast horizon",
        )
    }

    @Test
    fun `the configured season runs long enough for the south`() {
        // The tests above build their own Season, so they check the class and
        // never the season the application actually runs. That is the gap that
        // let a fitted constant drift away from its data earlier in this
        // project, and it applies here for the same reason: the number that
        // matters is the one in the config.
        //
        // Measured against the loaded grid at an 11-15 end: Louisiana reached
        // peak in 0% of sampled cells and sat at 48% progression on the last
        // day with 98% still climbing, and Alabama and Texas were no better.
        // The season has to reach into December or the Gulf states are cut off
        // mid-autumn -- which renders as a forecast that simply stops, and
        // looks like a real one.
        val yaml = javaClass.getResource("/application.yml")!!.readText()
        val block = yaml.substringAfter("  season:")
        val md = Regex("(start|end): \"(\\d\\d-\\d\\d)\"")
            .findAll(block).associate { it.groupValues[1] to it.groupValues[2] }

        val configured = Season(md.getValue("start"), md.getValue("end"))
        assertTrue(
            configured.end(2026).monthValue >= 12,
            "the season ends ${md["end"]}, before the Gulf states finish turning",
        )
        assertTrue(
            configured.days(2026).size >= 100,
            "the season is only ${configured.days(2026).size} days",
        )
    }
    @Test
    fun `handles leap years without shifting the season`() {
        assertEquals(LocalDate.of(2024, 9, 1), season.start(2024))
        assertEquals(76, season.days(2024).size)
    }
}
