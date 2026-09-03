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
    private val forestTypeIngest: com.foliage.ingest.ForestTypeIngest,
    private val cells: com.foliage.persistence.CellRepository,
    private val normals: com.foliage.persistence.NormalRepository,
    private val modelValidation: com.foliage.validate.ModelValidation,
    private val forecasts: com.foliage.persistence.ForecastRepository,
    @org.springframework.beans.factory.annotation.Value("\${foliage.grid.min-canopy-pct}")
    private val minCanopyPct: Int,
    @org.springframework.beans.factory.annotation.Value("\${foliage.grid.metro-population}")
    private val metroPopulation: Int,
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

    /**
     * Rebuilds a state's normals from scratch under the current method.
     *
     * For a state loaded across a change in how climatology is fetched, whose
     * cells would otherwise disagree about how many years they average and how
     * coarse a reading they came from.
     */
    /**
     * Samples forest type for a state's unsampled cells.
     *
     * Bounded by both a cell count and a time budget because it reads a hosted
     * raster: a national pass is roughly a million point lookups and belongs
     * in several runs rather than one long-held request.
     */
    /** How a state's cells break down by forest type, for checking a survey. */
    @org.springframework.web.bind.annotation.GetMapping("/forest-type")
    fun forestType(@RequestParam stateFips: String): Map<String, Any> {
        val raw = cells.forestTypeBreakdown(stateFips)
        val total = raw.values.sum()
        return mapOf(
            "stateFips" to stateFips,
            "total" to total,
            "groups" to raw.entries.sortedByDescending { it.value }.map { (code, n) ->
                mapOf(
                    "code" to code,
                    "label" to when (code) {
                        -1 -> "not sampled"
                        0 -> "no forest"
                        else -> com.foliage.forecast.ForestTypeGroup.forCode(code)?.label ?: "unmapped"
                    },
                    "cells" to n,
                    "multiplier" to com.foliage.forecast.ForestTypeGroup.multiplierFor(
                        code.takeIf { it > 0 },
                    ),
                )
            },
        )
    }

    @PostMapping("/sample-forest-type")
    fun sampleForestType(
        @RequestParam stateFips: String,
        @RequestParam(defaultValue = "5000") maxCells: Int,
        @RequestParam(defaultValue = "30") maxMinutes: Long,
    ): com.foliage.ingest.ForestTypeResult =
        forestTypeIngest.run(stateFips, maxCells, java.time.Duration.ofMinutes(maxMinutes))

    @PostMapping("/reload-climatology")
    fun reloadClimatology(@RequestParam stateFips: String): Map<String, Any> {
        val before = normals.yearsAveragedByState(stateFips)
        val dropped = normals.deleteByState(stateFips)
        val rebuilt = weatherIngest.buildClimatology(stateFips)
        forecastService.computeState(stateFips)
        return mapOf(
            "stateFips" to stateFips,
            "yearsAveragedBefore" to before,
            "normalsDropped" to dropped,
            "cellsRefetched" to rebuilt.cellsRequested,
            "yearsAveragedAfter" to normals.yearsAveragedByState(stateFips),
        )
    }

    /**
     * Measures the model against real observations of coloured leaves.
     *
     * The only check here that is not the model marking its own homework.
     * Reads only -- it fetches from USA-NPN and compares, and writes nothing.
     */
    @PostMapping("/validate")
    fun validate(
        @RequestParam(required = false) states: String?,
        @RequestParam(required = false) year: Int?,
        /** Comma-separated common names, e.g. "sugar maple,red maple". */
        @RequestParam(required = false) species: String?,
    ): com.foliage.validate.ValidationResult = modelValidation.run(
        states = states?.split(",")?.map { it.trim().uppercase() }?.filter { it.isNotBlank() }
            ?: com.foliage.grid.ConusStates.POSTAL,
        year = year ?: java.time.LocalDate.now().year,
        species = species?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList(),
    )

    /**
     * How much of each state carries a forecast, as counts rather than rows.
     *
     * The cheap way to ask "what is still missing". The expensive way -- pull
     * a whole daily forecast and count the cells in it -- costs twelve
     * megabytes a look, and repeating it helped exhaust a metered allowance.
     */
    @org.springframework.web.bind.annotation.GetMapping("/coverage")
    fun coverage(
        /** peaked=true asks how much of each state has finished turning instead. */
        @RequestParam(required = false) peaked: Boolean?,
        /** day=YYYY-MM-DD asks how much of each state is scored on that day. */
        @RequestParam(required = false) day: String?,
    ): List<com.foliage.persistence.StateCoverage> =
        if (day != null) forecasts.coverageByStateOnDay(java.time.LocalDate.parse(day), minCanopyPct)
        else if (peaked == true) forecasts.peakCoverageByState(minCanopyPct)
        else forecasts.coverageByState(minCanopyPct, metroPopulation)

    /** Writes the season out as static JSON for CDN publishing. */
    @PostMapping("/export")
    fun export(
        @RequestParam(required = false) stateFips: String?,
        @RequestParam(defaultValue = "build/site-data") path: String,
    ): ExportResult = staticExporter.export(java.nio.file.Path.of(path), stateFips)
}
