package com.foliage.ingest.weather

import com.foliage.domain.DailyRecord
import com.foliage.grid.LonLat
import com.foliage.ingest.RetryPolicy
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestClient
import org.springframework.web.util.UriComponentsBuilder
import java.time.LocalDate

private const val DAILY_VARS =
    "temperature_2m_max,temperature_2m_min,precipitation_sum,shortwave_radiation_sum"

/**
 * Open-Meteo. Free and keyless, but metered by request *weight*, so batches
 * are modest and every call retries on 429. See ADR-0004 and ADR-0005.
 *
 * Native resolution is ~9–25 km (ERA5/GFS), which is why weather is ingested
 * at H3 res 5 and downscaled to res 6 cells by lapse rate rather than being
 * fetched at res 6 — that would invent precision the model does not have.
 */
@Component
class OpenMeteoWeatherSource(
    private val restClient: RestClient,
    @Value("\${foliage.weather.forecast-url}") private val forecastUrl: String,
    @Value("\${foliage.weather.archive-url}") private val archiveUrl: String,
    @Value("\${foliage.weather.batch-size}") private val batchSize: Int,
    @Value("\${foliage.weather.forecast-days}") private val forecastDays: Int,
    @Value("\${foliage.weather.past-days}") private val pastDays: Int,
) : WeatherSource {

    private val log = LoggerFactory.getLogger(javaClass)
    private val retry = RetryPolicy(maxAttempts = 4, initialBackoffMs = 20_000)
    private val isRateLimit: (Throwable) -> Boolean = { it is HttpClientErrorException.TooManyRequests }

    override val nativeResolution = 5

    /** Observed trailing window plus the forecast horizon, in one call per batch. */
    override fun daily(points: List<LonLat>, from: LocalDate, to: LocalDate): List<List<DailyRecord>> =
        fetch(points) { batch ->
            baseUri(forecastUrl, batch)
                .queryParam("past_days", pastDays)
                .queryParam("forecast_days", forecastDays)
        }

    /** Historical reanalysis for an explicit date range. Used to build climatology. */
    fun archive(points: List<LonLat>, from: LocalDate, to: LocalDate): List<List<DailyRecord>> =
        fetch(points) { batch ->
            baseUri(archiveUrl, batch)
                .queryParam("start_date", from.toString())
                .queryParam("end_date", to.toString())
        }

    private fun fetch(
        points: List<LonLat>,
        uri: (List<LonLat>) -> UriComponentsBuilder,
    ): List<List<DailyRecord>> =
        points.chunked(batchSize).flatMapIndexed { i, batch ->
            runCatching {
                retry.execute("weather batch $i", isRateLimit) {
                    val body = restClient.get().uri(uri(batch).build(true).toUri())
                        .retrieve().body(String::class.java)
                    if (body == null) List(batch.size) { emptyList() }
                    else OpenMeteoDailyParser.parse(body, batch.size)
                }
            }.getOrElse {
                // Degrade: these points stay unfetched and a later run picks
                // them up, rather than losing the whole ingest.
                log.warn("weather batch {} failed after retries: {}", i, it.message)
                List(batch.size) { emptyList<DailyRecord>() }
            }
        }

    private fun baseUri(url: String, batch: List<LonLat>) =
        UriComponentsBuilder.fromUriString(url)
            .queryParam("latitude", batch.joinToString(",") { it.lat.toString() })
            .queryParam("longitude", batch.joinToString(",") { it.lon.toString() })
            .queryParam("daily", DAILY_VARS)
            .queryParam("timezone", "UTC")
}
