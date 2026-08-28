package com.foliage.ingest.weather.hrrr

import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HrrrRunTest {

    @Test
    fun `builds the bucket key NOAA actually publishes`() {
        val run = HrrrRun(LocalDate.of(2026, 8, 27), cycleHour = 12, forecastHour = 0)
        assertEquals("hrrr.20260827/conus/hrrr.t12z.wrfsfcf00.grib2", run.key)
        assertEquals("hrrr.20260827/conus/hrrr.t12z.wrfsfcf00.grib2.idx", run.indexKey)
    }

    @Test
    fun `pads single-digit cycle and forecast hours`() {
        val run = HrrrRun(LocalDate.of(2026, 8, 27), cycleHour = 6, forecastHour = 3)
        assertEquals("hrrr.20260827/conus/hrrr.t06z.wrfsfcf03.grib2", run.key)
    }

    @Test
    fun `synoptic cycles reach 48 hours, others only 18`() {
        // Verified against the bucket: t12z f48 exists, f49 is 404;
        // t13z f18 exists, f19 is 404.
        listOf(0, 6, 12, 18).forEach { assertEquals(48, HrrrRun.maxForecastHour(it)) }
        listOf(1, 5, 13, 17, 23).forEach { assertEquals(18, HrrrRun.maxForecastHour(it)) }
    }

    @Test
    fun `rejects a forecast hour beyond the cycle's reach`() {
        val date = LocalDate.of(2026, 8, 27)
        HrrrRun(date, 12, 48) // fine
        assertFailsWith<IllegalArgumentException> { HrrrRun(date, 12, 49) }
        HrrrRun(date, 13, 18) // fine
        assertFailsWith<IllegalArgumentException> { HrrrRun(date, 13, 19) }
    }

    @Test
    fun `rejects nonsense cycle and forecast hours`() {
        val date = LocalDate.of(2026, 8, 27)
        assertFailsWith<IllegalArgumentException> { HrrrRun(date, 24, 0) }
        assertFailsWith<IllegalArgumentException> { HrrrRun(date, -1, 0) }
        assertFailsWith<IllegalArgumentException> { HrrrRun(date, 12, -1) }
    }

    @Test
    fun `valid time is the cycle plus the forecast hour`() {
        val run = HrrrRun(LocalDate.of(2026, 8, 27), 12, 6)
        assertEquals(Instant.parse("2026-08-27T18:00:00Z"), run.validAt)
    }

    @Test
    fun `a forecast hour can roll past midnight into the next day`() {
        val run = HrrrRun(LocalDate.of(2026, 8, 27), 18, 12)
        assertEquals(Instant.parse("2026-08-28T06:00:00Z"), run.validAt)
    }

    @Test
    fun `analysis for an instant is that hour's own cycle at f00`() {
        val run = HrrrRun.analysisAt(Instant.parse("2026-08-27T12:34:56Z"))
        assertEquals(HrrrRun(LocalDate.of(2026, 8, 27), 12, 0), run)
        assertEquals(Instant.parse("2026-08-27T12:00:00Z"), run.validAt)
    }

    @Test
    fun `a forecast resolves to the lead hour from the issuing cycle`() {
        val run = HrrrRun.forecastFor(
            target = Instant.parse("2026-08-28T06:00:00Z"),
            issuedBy = Instant.parse("2026-08-27T12:20:00Z"),
        )
        assertEquals(HrrrRun(LocalDate.of(2026, 8, 27), 12, 18), run)
        assertEquals(Instant.parse("2026-08-28T06:00:00Z"), run!!.validAt)
    }

    @Test
    fun `a target beyond the cycle's reach has no product`() {
        // 12z reaches 48 h; this asks for 60.
        assertNull(
            HrrrRun.forecastFor(
                target = Instant.parse("2026-08-30T00:00:00Z"),
                issuedBy = Instant.parse("2026-08-27T12:00:00Z"),
            ),
        )
        // A non-synoptic cycle gives up much sooner.
        assertNull(
            HrrrRun.forecastFor(
                target = Instant.parse("2026-08-28T18:00:00Z"),
                issuedBy = Instant.parse("2026-08-27T13:00:00Z"),
            ),
        )
    }

    @Test
    fun `a target in the past has no forecast`() {
        assertNull(
            HrrrRun.forecastFor(
                target = Instant.parse("2026-08-27T06:00:00Z"),
                issuedBy = Instant.parse("2026-08-27T12:00:00Z"),
            ),
        )
    }

    @Test
    fun `the horizon is far shorter than the foliage season`() {
        // The premise of this whole source: HRRR refines the next couple of
        // days at 3 km and cannot speak to October at all. Open-Meteo keeps
        // the 16-day forecast and the climatology. See ADR-0005 and ADR-0006.
        val synoptic = HrrrRun.horizonFrom(Instant.parse("2026-08-27T12:00:00Z"))
        val standard = HrrrRun.horizonFrom(Instant.parse("2026-08-27T13:00:00Z"))
        assertEquals(48, synoptic.toHours())
        assertEquals(18, standard.toHours())
        assertTrue(
            synoptic.toDays() < 7,
            "if HRRR ever reaches a week, the layering in ADR-0006 can be revisited",
        )
    }
}
