package com.foliage.api

import com.foliage.ingest.GridBootstrap
import com.foliage.ingest.BackfillResult
import com.foliage.ingest.ElevationRefreshResult
import com.foliage.ingest.WeatherBackfill
import com.foliage.ingest.GridBootstrapResult
import com.foliage.ingest.RegionBootstrapResult
import com.foliage.ingest.places.PlaceIngest
import com.foliage.ingest.places.PlaceIngestResult
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
    private val staticExporter: StaticExporter,
    private val placeIngest: PlaceIngest,
    private val weatherBackfill: WeatherBackfill,
) {

    @PostMapping("/bootstrap-grid")
    fun bootstrapGrid(@RequestParam state: String): GridBootstrapResult =
        gridBootstrap.bootstrapState(state)

    /**
     * Bootstraps a whole region. Long-running: a full CONUS pass is thousands
     * of third-party samples and many hours, so it skips states already loaded
     * and can simply be re-invoked after an interruption.
     */
    @PostMapping("/bootstrap-region")
    fun bootstrapRegion(
        @RequestParam region: String,
        @RequestParam(defaultValue = "false") force: Boolean,
    ): RegionBootstrapResult = gridBootstrap.bootstrapRegion(region, force)

    /**
     * Re-derives elevation for an already-tiled state without touching canopy.
     * Seconds rather than the minutes a full re-bootstrap costs; see
     * GridBootstrap.refreshElevation.
     */
    @PostMapping("/refresh-elevation")
    fun refreshElevation(@RequestParam stateFips: String): ElevationRefreshResult =
        gridBootstrap.refreshElevation(stateFips)

    /** Observed trailing window plus the 16-day forecast. Cheap; run daily. */
    @PostMapping("/ingest-forecast")
    fun ingestForecast(@RequestParam(required = false) stateFips: String?): WeatherIngestResult =
        weatherIngest.refreshForecast(stateFips)

    /** Multi-year archive mean for the far season. Expensive; run once a season. */
    @PostMapping("/ingest-climatology")
    fun ingestClimatology(@RequestParam(required = false) stateFips: String?): WeatherIngestResult =
        weatherIngest.buildClimatology(stateFips)

    /** Refines the recent window with HRRR at 3 km. Bandwidth-heavy; see ADR-0006. */
    @PostMapping("/ingest-hrrr")
    fun ingestHrrr(
        @RequestParam(required = false) stateFips: String?,
        @RequestParam(defaultValue = "2") days: Int,
    ): WeatherIngestResult = weatherIngest.refreshHrrr(stateFips, days)

    /** Scores the whole season for a state. Cheap; rerun after any ingest. */
    @PostMapping("/compute-forecast")
    fun computeForecast(@RequestParam(required = false) stateFips: String?): ForecastRunResult =
        forecastService.computeState(stateFips)

    /**
     * Loads US places from GeoNames. One-off: the source changes rarely, and
     * every US place is stored regardless of the current grid, so expanding
     * the grid needs no re-ingest.
     */
    @PostMapping("/ingest-places")
    fun ingestPlaces(): PlaceIngestResult = placeIngest.ingest()

    /**
     * Removes a state's scores, for one computed against incomplete weather.
     * See ForecastRepository.deleteByState.
     */
    @PostMapping("/clear-forecast")
    fun clearForecast(@RequestParam stateFips: String): Map<String, Any> =
        mapOf("stateFips" to stateFips, "rowsDeleted" to forecastService.clearState(stateFips))

    /**
     * Loads the next few unfinished states, stopping when the daily Open-Meteo
     * allowance or the time budget runs out. Called by the nightly deploy; see
     * WeatherBackfill.
     */
    @PostMapping("/backfill")
    fun backfill(
        @RequestParam(defaultValue = "6") maxStates: Int,
        @RequestParam(defaultValue = "90") maxMinutes: Long,
    ): BackfillResult = weatherBackfill.run(maxStates, java.time.Duration.ofMinutes(maxMinutes))

    /** Writes the season out as static JSON for CDN publishing. */
    @PostMapping("/export")
    fun export(
        @RequestParam(required = false) stateFips: String?,
        @RequestParam(defaultValue = "build/site-data") path: String,
    ): ExportResult = staticExporter.export(java.nio.file.Path.of(path), stateFips)
}
