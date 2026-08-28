package com.foliage.ingest

import org.slf4j.LoggerFactory

/**
 * Retry with exponential backoff for rate-limited third-party calls.
 *
 * Open-Meteo meters by request *weight*, not request count: a batch of 100
 * coordinates costs far more than a single lookup, so a handful of large
 * batches fired back-to-back can exhaust a minutely allowance in seconds.
 * Observed in the first Vermont bootstrap, which lost its final elevation
 * batch to a 429.
 *
 * Sleeping is injected so the policy can be tested without real delays.
 */
class RetryPolicy(
    private val maxAttempts: Int = 4,
    private val initialBackoffMs: Long = 2_000,
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) },
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Runs [block], retrying while [isRetryable] says the failure is transient.
     * Backoff doubles each attempt. The final failure is rethrown so callers
     * can decide whether to degrade or abort.
     */
    fun <T> execute(description: String, isRetryable: (Throwable) -> Boolean, block: () -> T): T {
        var backoff = initialBackoffMs
        var lastError: Throwable? = null

        repeat(maxAttempts) { attempt ->
            try {
                return block()
            } catch (e: Throwable) {
                if (!isRetryable(e)) throw e
                lastError = e
                if (attempt < maxAttempts - 1) {
                    log.warn(
                        "{} rate-limited (attempt {}/{}), backing off {} ms",
                        description, attempt + 1, maxAttempts, backoff,
                    )
                    sleeper(backoff)
                    backoff *= 2
                }
            }
        }
        throw lastError ?: IllegalStateException("$description failed with no recorded error")
    }
}
