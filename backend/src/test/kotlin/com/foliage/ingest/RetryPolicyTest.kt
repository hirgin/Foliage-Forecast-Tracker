package com.foliage.ingest

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RetryPolicyTest {

    private class RateLimited : RuntimeException("429")
    private class Fatal : RuntimeException("400")

    private val slept = mutableListOf<Long>()
    private val policy = RetryPolicy(maxAttempts = 4, initialBackoffMs = 100) { slept += it }

    private val retryable: (Throwable) -> Boolean = { it is RateLimited }

    @Test
    fun `returns immediately when the call succeeds`() {
        val result = policy.execute("test", retryable) { "ok" }
        assertEquals("ok", result)
        assertTrue(slept.isEmpty(), "should not sleep on success")
    }

    @Test
    fun `retries a rate-limited call and succeeds`() {
        var attempts = 0
        val result = policy.execute("test", retryable) {
            if (++attempts < 3) throw RateLimited()
            "recovered"
        }
        assertEquals("recovered", result)
        assertEquals(3, attempts)
        // Backoff doubles: two failures means two sleeps of 100 then 200.
        assertEquals(listOf(100L, 200L), slept)
    }

    @Test
    fun `gives up after maxAttempts and rethrows the last error`() {
        var attempts = 0
        assertFailsWith<RateLimited> {
            policy.execute("test", retryable) { attempts++; throw RateLimited() }
        }
        assertEquals(4, attempts)
        // No sleep after the final attempt -- nothing follows it.
        assertEquals(listOf(100L, 200L, 400L), slept)
    }

    @Test
    fun `a non-retryable error propagates immediately without backing off`() {
        var attempts = 0
        assertFailsWith<Fatal> {
            policy.execute("test", retryable) { attempts++; throw Fatal() }
        }
        assertEquals(1, attempts, "a fatal error must not be retried")
        assertTrue(slept.isEmpty())
    }
}
