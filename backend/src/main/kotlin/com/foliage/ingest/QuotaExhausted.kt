package com.foliage.ingest

/**
 * A third-party allowance is spent for a window measured in hours, not seconds.
 *
 * Open-Meteo enforces two limits with the same 429 status, distinguishable only
 * by the message body. They need opposite handling:
 *
 *  - **Minutely** ("Minutely API request limit exceeded") is what [RetryPolicy]
 *    is for. A few seconds of backoff clears it and the batch succeeds.
 *  - **Hourly or daily** ("Please try again in the next hour") cannot be waited
 *    out inside a request. Backing off 20, 40 and 80 seconds against it fails
 *    three times and then gives up, and because the batch loop degrades rather
 *    than aborts, the run continues writing nothing for every remaining batch.
 *
 * That second case actually happened: a New York climatology run burned 20
 * minutes producing silent gaps after the hourly allowance was spent. Retrying
 * could not have helped, and continuing only spread the damage.
 *
 * So this is deliberately *not* retryable and deliberately *not* degraded. It
 * aborts the job at the first occurrence, leaving what was already written
 * intact and resumable, and says plainly that the fix is to wait rather than to
 * try harder.
 */
class QuotaExhausted(source: String, val reason: String) :
    RuntimeException("$source quota exhausted: $reason. Wait for the window to reset and re-run.") {

    /**
     * Which allowance ran out, read back off the message.
     *
     * Both abort the run identically, so for a while everything logged this as
     * the *daily* allowance being spent. That reads as "nothing more will load
     * until tomorrow", and when the limit was actually the hourly one it was
     * wrong by a factor of twenty-four -- a load that could have resumed in
     * forty minutes looked finished for the day, and nobody re-ran it.
     *
     * Handling does not change. What is reported does.
     */
    val window: String = when {
        reason.contains("hourly", ignoreCase = true) -> "hourly"
        reason.contains("daily", ignoreCase = true) -> "daily"
        else -> "current"
    }

    /** Plain-language wait, for a log line someone reads at 2am. */
    val resumesIn: String = when (window) {
        "hourly" -> "within the hour"
        "daily" -> "tomorrow"
        else -> "once the window resets"
    }
}
