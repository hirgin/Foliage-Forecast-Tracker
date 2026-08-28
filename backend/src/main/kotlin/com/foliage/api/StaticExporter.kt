package com.foliage.api

import com.fasterxml.jackson.databind.ObjectMapper
import com.foliage.forecast.ForecastService
import com.foliage.grid.H3Grid
import com.foliage.ingest.weather.Season
import com.foliage.persistence.CellRepository
import com.foliage.persistence.ForecastRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate

/**
 * Writes the whole season out as static JSON.
 *
 * The read path is inherently static: the pipeline precomputes every score, so
 * the API only ever serves stored snapshots. Publishing those as files on a
 * CDN removes the need for a running server entirely -- which matters, because
 * every free JVM host cold-starts a Spring Boot app in 30-60 s, and a portfolio
 * link that hangs for a minute is worse than no link.
 *
 * The backend remains what it actually is: a batch pipeline.
 */
@Service
class StaticExporter(
    private val cells: CellRepository,
    private val forecasts: ForecastRepository,
    private val forecastService: ForecastService,
    private val grid: H3Grid,
    private val season: Season,
    @Value("\${foliage.model-version}") private val modelVersion: String,
) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val mapper = ObjectMapper()

    fun export(target: Path, stateFips: String, year: Int = LocalDate.now().year): ExportResult {
        val days = season.days(year)
        val grid6 = cells.findByState(stateFips, minCanopyPct = 0)
        require(grid6.isNotEmpty()) { "no cells for state $stateFips" }

        Files.createDirectories(target.resolve("forecast"))
        Files.createDirectories(target.resolve("timeline"))

        // Peak day per cell, then all factor breakdowns in one pass. Calling
        // explain() per cell re-reads the reference data every time.
        val peakDays = forecasts.peakDayByCell()
        val factorsByCell = forecastService.peakFactors(stateFips, peakDays, year)
        val timelines = forecasts.allTimelines()

        var files = 0
        var bytes = 0L

        fun write(path: Path, value: Any) {
            val json = mapper.writeValueAsBytes(value)
            Files.write(path, json)
            files++
            bytes += json.size
        }

        // One file per day: the map's primary fetch.
        for (day in days) {
            val rows = forecasts.byDay(day)
            write(
                target.resolve("forecast/$day.json"),
                mapOf(
                    "date" to day.toString(),
                    "count" to rows.size,
                    "seasonStart" to season.start(year).toString(),
                    "seasonEnd" to season.end(year).toString(),
                    "cells" to rows.map {
                        mapOf(
                            "h3" to grid.toAddress(it.h3),
                            "progression" to it.progression,
                            "intensity" to it.intensity,
                            "stage" to it.stage.name,
                            "confidence" to it.confidence,
                        )
                    },
                ),
            )
        }

        // One file per cell: fetched only when a hexagon is clicked. Factor
        // *values* travel; the human-readable sentences are rendered in the
        // browser, which keeps these files small.
        for (cell in grid6) {
            val address = grid.toAddress(cell.h3)
            val timeline = timelines[cell.h3].orEmpty()
            val peak = peakDays[cell.h3]?.toString()

            write(
                target.resolve("timeline/$address.json"),
                mapOf(
                    "h3" to address,
                    "peakDay" to peak,
                    "elevationM" to cell.elevationM,
                    "canopyPct" to cell.canopyPct,
                    "days" to timeline.map {
                        mapOf(
                            "date" to it.day.toString(),
                            "progression" to it.progression,
                            "intensity" to it.intensity,
                            "stage" to it.stage.name,
                            "confidence" to it.confidence,
                        )
                    },
                    // Explanations are recomputed per cell, so exporting one
                    // per day would mean 49k files. A cell's drivers are
                    // exported at its peak instead, which is the date anyone
                    // actually asks "why" about.
                    "factorsAtPeak" to (factorsByCell[cell.h3]?.map {
                        mapOf(
                            "name" to it.name,
                            "value" to it.value,
                            "effect" to it.effect,
                            "detail" to it.detail,
                        )
                    } ?: emptyList()),
                ),
            )
        }

        write(
            target.resolve("meta.json"),
            mapOf(
                "service" to "foliage-forecast",
                "modelVersion" to modelVersion,
                "gridResolution" to 6,
                "stateFips" to stateFips,
                "cellCount" to grid6.size,
                "seasonStart" to season.start(year).toString(),
                "seasonEnd" to season.end(year).toString(),
                "generatedAt" to java.time.Instant.now().toString(),
                "mode" to "static",
            ),
        )

        log.info("exported {} files ({} KB) to {}", files, bytes / 1024, target.toAbsolutePath())
        return ExportResult(
            files = files,
            bytes = bytes,
            days = days.size,
            cells = grid6.size,
            target = target.toAbsolutePath().toString(),
        )
    }
}

data class ExportResult(
    val files: Int,
    val bytes: Long,
    val days: Int,
    val cells: Int,
    val target: String,
)
