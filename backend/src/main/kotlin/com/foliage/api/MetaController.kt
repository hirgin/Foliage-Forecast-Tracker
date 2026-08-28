package com.foliage.api

import com.foliage.config.DatabaseBootstrap
import com.foliage.config.DatabaseStatus
import com.foliage.persistence.CellRepository
import com.foliage.persistence.Coverage
import com.foliage.ingest.weather.Season
import com.foliage.persistence.WeatherRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Service metadata. The frontend calls this on load to decide whether it can
 * render a map at all, and to label the build it is talking to.
 */
@RestController
@RequestMapping("/api/v1")
class MetaController(
    private val databaseBootstrap: DatabaseBootstrap,
    private val cells: CellRepository,
    private val weather: WeatherRepository,
    private val season: Season,
    @Value("\${foliage.model-version}") private val modelVersion: String,
    @Value("\${foliage.grid.resolution}") private val gridResolution: Int,
) {

    @GetMapping("/meta")
    fun meta(): MetaResponse {
        // Degrade rather than 500: meta is what the UI uses to decide whether
        // it can render anything at all, so it must answer even when the
        // database is unreachable.
        val grid = runCatching { cells.countByState("50") }.getOrNull()
        val coverage = runCatching { weather.coverage() }.getOrNull()
        val byKind = runCatching { weather.countByKind() }.getOrDefault(emptyMap())

        return MetaResponse(
            service = "foliage-forecast",
            modelVersion = modelVersion,
            gridResolution = gridResolution,
            database = databaseBootstrap.status,
            cellCount = grid,
            weather = coverage,
            weatherByKind = byKind,
            // The UI needs these before it can request a forecast at all, so
            // they must not depend on a forecast response existing.
            seasonStart = season.start(java.time.LocalDate.now().year).toString(),
            seasonEnd = season.end(java.time.LocalDate.now().year).toString(),
        )
    }
}

data class MetaResponse(
    val service: String,
    val modelVersion: String,
    val gridResolution: Int,
    val database: DatabaseStatus,
    val cellCount: Long?,
    val weather: Coverage?,
    val weatherByKind: Map<String, Long>,
    val seasonStart: String,
    val seasonEnd: String,
)
