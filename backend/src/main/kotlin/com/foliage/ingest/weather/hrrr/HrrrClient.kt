package com.foliage.ingest.weather.hrrr

import com.foliage.ingest.RetryPolicy
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpHeaders
import org.springframework.stereotype.Component
import org.springframework.web.client.HttpServerErrorException
import org.springframework.web.client.RestClient
import java.nio.file.Files
import java.nio.file.Path

/**
 * Fetches single GRIB2 messages from NOAA's public HRRR bucket.
 *
 * No credentials: HRRR is on the AWS Open Data registry. The important part is
 * that nothing here ever downloads a whole file. Each product has an `.idx`
 * sidecar listing byte offsets, so one variable costs about **1.2 MB against a
 * 133 MB file** — measured, and the reason this approach is practical at all.
 */
@Component
class HrrrClient(
    private val restClient: RestClient,
    @Value("\${foliage.hrrr.base-url}") private val baseUrl: String,
) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val retry = RetryPolicy(maxAttempts = 3, initialBackoffMs = 3_000)

    // NOAA occasionally 5xxs under load; a missing product is a 404 and must
    // not be retried, since it usually means the cycle has not published yet.
    private val isTransient: (Throwable) -> Boolean = { it is HttpServerErrorException }

    /** Parsed index for a product, or null if it is not published. */
    fun index(run: HrrrRun): List<GribRecord>? {
        val body = runCatching {
            retry.execute("hrrr index ${run.indexKey}", isTransient) {
                restClient.get().uri("$baseUrl/${run.indexKey}").retrieve().body(String::class.java)
            }
        }.getOrElse {
            log.debug("no index for {}: {}", run.indexKey, it.message)
            return null
        } ?: return null

        return HrrrIndexParser.parse(body)
    }

    /**
     * Downloads one message to [target] by byte range. Returns null when the
     * product or the variable is absent, so a caller can fall back to another
     * source rather than failing the run.
     */
    fun fetchVariable(run: HrrrRun, variable: String, level: String, target: Path): Path? {
        val records = index(run) ?: return null
        val record = HrrrIndexParser.find(records, variable, level) ?: run {
            log.warn("{} not found at {} in {}", variable, level, run.key)
            return null
        }

        return runCatching {
            retry.execute("hrrr fetch ${run.key} $variable", isTransient) {
                val bytes = restClient.get()
                    .uri("$baseUrl/${run.key}")
                    .header(HttpHeaders.RANGE, record.byteRange)
                    .retrieve()
                    .body(ByteArray::class.java)
                    ?: error("empty body for ${run.key}")

                Files.createDirectories(target.parent)
                Files.write(target, bytes)
                log.debug("fetched {} {} -> {} KB", run.key, variable, bytes.size / 1024)
                // Absolute: NetCDF-Java resolves data files relative to its own
                // cache directory otherwise. See Grib2Sampler.
                target.toAbsolutePath()
            }
        }.getOrElse {
            log.warn("failed to fetch {} from {}: {}", variable, run.key, it.message)
            null
        }
    }
}
