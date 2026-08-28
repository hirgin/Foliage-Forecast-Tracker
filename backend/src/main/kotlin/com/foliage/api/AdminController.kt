package com.foliage.api

import com.foliage.ingest.GridBootstrap
import com.foliage.ingest.GridBootstrapResult
import com.foliage.forecast.ForecastRunResult
import com.foliage.forecast.ForecastService
import com.foliage.ingest.weather.WeatherIngest
import com.foliage.ingest.weather.WeatherIngestResult
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * Manual triggers for pipeline jobs.
 *
 * Absent unless `foliage.admin.enabled` is true: these endpoints do real work
 * against third-party services and the database, and nothing about them should
 * be reachable in a deployed read-only site.
 */
@RestController
@RequestMapping("/api/v1/admin")
@ConditionalOnProperty("foliage.admin.enabled", havingValue = "true")
class AdminController(
    private val gridBootstrap: GridBootstrap,
    private val weatherIngest: WeatherIngest,
    private val forecastService: ForecastService,
) {

    @PostMapping("/bootstrap-grid")
    fun bootstrapGrid(@RequestParam state: String): GridBootstrapResult =
        gridBootstrap.bootstrapState(state)

    /** Observed trailing window plus the 16-day forecast. Cheap; run daily. */
    @PostMapping("/ingest-forecast")
    fun ingestForecast(@RequestParam stateFips: String): WeatherIngestResult =
        weatherIngest.refreshForecast(stateFips)

    /** Multi-year archive mean for the far season. Expensive; run once a season. */
    @PostMapping("/ingest-climatology")
    fun ingestClimatology(@RequestParam stateFips: String): WeatherIngestResult =
        weatherIngest.buildClimatology(stateFips)

    /** Scores the whole season for a state. Cheap; rerun after any ingest. */
    @PostMapping("/compute-forecast")
    fun computeForecast(@RequestParam stateFips: String): ForecastRunResult =
        forecastService.computeState(stateFips)
}
