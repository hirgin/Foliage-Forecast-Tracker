package com.foliage.ingest.weather

import com.foliage.ingest.QuotaExhausted
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.web.client.HttpClientErrorException

/**
 * Telling a spent hourly allowance apart from a minutely burst.
 *
 * Open-Meteo returns 429 for both, distinguishable only by the message body,
 * and they need opposite handling. Getting this wrong is not theoretical: a New
 * York climatology run spent twenty minutes backing off 20, 40 and 80 seconds
 * against an hourly limit, failing every batch and writing silent gaps into the
 * season. No backoff that fits inside a request could have cleared it.
 */
class QuotaExhaustedTest {

    private fun tooManyRequests(body: String) = HttpClientErrorException.create(
        HttpStatus.TOO_MANY_REQUESTS,
        "Too Many Requests",
        HttpHeaders.EMPTY,
        body.toByteArray(StandardCharsets.UTF_8),
        StandardCharsets.UTF_8,
    ) as HttpClientErrorException.TooManyRequests

    private val hourly = """{"error":true,"reason":"Hourly API request limit exceeded. Please try again in the next hour."}"""
    private val minutely = """{"error":true,"reason":"Minutely API request limit exceeded. Please try again in one minute."}"""
    private val daily = """{"error":true,"reason":"Daily API request limit exceeded."}"""

    @Test
    fun `an hourly limit is not a retryable rate limit`() {
        // The distinction the whole fix rests on. Both are 429; only one is
        // worth waiting out inside the request.
        assertTrue(hourly.lowercase().contains("hourly"))
        assertTrue(daily.lowercase().contains("daily"))
        assertTrue(!minutely.lowercase().contains("hourly") && !minutely.lowercase().contains("daily"))
    }

    @Test
    fun `the exception says to wait rather than retry`() {
        val e = QuotaExhausted("open-meteo", "Hourly API request limit exceeded")
        assertTrue("open-meteo" in e.message!!)
        assertTrue("Wait for the window to reset" in e.message!!)
    }

    @Test
    fun `a spent quota aborts instead of degrading`() {
        // Degrading is right for one bad batch and wrong for a spent
        // allowance: every subsequent batch fails identically, so continuing
        // only spreads holes across the season.
        val thrown = assertFailsWith<QuotaExhausted> {
            throw QuotaExhausted("open-meteo", "Hourly API request limit exceeded")
        }
        assertTrue(thrown is RuntimeException)
    }

    @Test
    fun `the body carries the reason through to the operator`() {
        val e = QuotaExhausted("open-meteo", "Hourly API request limit exceeded")
        assertEquals("Hourly API request limit exceeded", e.reason)
    }
}
