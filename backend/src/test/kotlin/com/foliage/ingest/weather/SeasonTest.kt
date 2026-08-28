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
    fun `handles leap years without shifting the season`() {
        assertEquals(LocalDate.of(2024, 9, 1), season.start(2024))
        assertEquals(76, season.days(2024).size)
    }
}
