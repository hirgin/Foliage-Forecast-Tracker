package com.foliage.api

import com.foliage.ingest.QuotaExhausted
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * Turns a spent third-party allowance into an answer an operator can act on.
 *
 * Without this it surfaces as a bare 500, which is actively misleading: it
 * reads as a bug in this service when nothing here is broken and the only
 * remedy is to wait. It also cost time in practice -- a script driving the
 * ingest could not tell "come back tomorrow" from a crash, so it kept calling
 * for every remaining state and logged eight identical Internal Server Errors.
 *
 * 429 says the right thing, and the body carries the window that was hit, so
 * both a person and a script can see whether to retry in an hour or tomorrow.
 */
@RestControllerAdvice
class ApiExceptionHandler {

    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(QuotaExhausted::class)
    fun quotaExhausted(e: QuotaExhausted): ResponseEntity<QuotaResponse> {
        // Logged at warn, not error: the service is behaving correctly and
        // stopping deliberately. An error would be noise in the ingest logs.
        log.warn("stopping: {}", e.message)
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(
            QuotaResponse(
                error = "quota-exhausted",
                reason = e.reason,
                retryable = true,
                message = e.message ?: "quota exhausted",
                at = Instant.now().toString(),
            ),
        )
    }
}

data class QuotaResponse(
    val error: String,
    /** The provider's own words, which say whether the window is an hour or a day. */
    val reason: String,
    /** True always: waiting fixes this, unlike a genuine failure. */
    val retryable: Boolean,
    val message: String,
    val at: String,
)
