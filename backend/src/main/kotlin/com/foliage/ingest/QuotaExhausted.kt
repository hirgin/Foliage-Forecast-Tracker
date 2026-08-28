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
    RuntimeException("$source quota exhausted: $reason. Wait for the window to reset and re-run.")
