package com.foliage.ingest

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Which allowance ran out, and therefore when to come back.
 *
 * Both windows abort the run the same way, so this was reported as the daily
 * allowance whichever one it was. That is wrong by a factor of twenty-four
 * when the limit is hourly: a load that could resume within the hour reads as
 * finished for the day, and nobody re-runs it.
 */
class QuotaExhaustedTest {

    @Test
    fun `the hourly limit says to come back within the hour`() {
        val e = QuotaExhausted(
            "open-meteo",
            """{"reason":"Hourly API request limit exceeded. Please try again in the next hour.","error":true}""",
        )
        assertEquals("hourly", e.window)
        assertEquals("within the hour", e.resumesIn)
    }

    @Test
    fun `the daily limit says tomorrow`() {
        val e = QuotaExhausted(
            "open-meteo",
            """{"reason":"Daily API request limit exceeded. Please try again tomorrow.","error":true}""",
        )
        assertEquals("daily", e.window)
        assertEquals("tomorrow", e.resumesIn)
    }

    @Test
    fun `an unrecognised message does not invent a wait`() {
        // Open-Meteo could word this differently tomorrow. Guessing "daily" at
        // an unknown message is how the original bug read to anyone looking.
        val e = QuotaExhausted("open-meteo", """{"reason":"Too many requests","error":true}""")
        assertEquals("current", e.window)
        assertEquals("once the window resets", e.resumesIn)
    }
}
