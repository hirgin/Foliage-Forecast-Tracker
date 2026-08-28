package com.foliage.api

import com.foliage.forecast.ForecastService
import com.foliage.grid.H3Grid
import com.foliage.ingest.weather.Season
import com.foliage.persistence.ForecastRepository
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate
import java.util.concurrent.TimeUnit

@RestController
@RequestMapping("/api/v1")
class ForecastController(
    private val forecasts: ForecastRepository,
    private val forecastService: ForecastService,
    private val grid: H3Grid,
    private val season: Season,
) {

    /**
     * Every scored cell on one day. The map's primary request.
     *
     * H3 indexes go out as hex strings: they are 64-bit and JavaScript loses
     * precision above 2^53.
     */
    @GetMapping("/forecast")
    fun forecast(@RequestParam(required = false) date: String?): ResponseEntity<ForecastResponse> {
        val day = date?.let { LocalDate.parse(it) } ?: LocalDate.now()
        val rows = forecasts.byDay(day)

        return ResponseEntity.ok()
            .cacheControl(CacheControl.maxAge(30, TimeUnit.MINUTES).cachePublic())
            .body(
                ForecastResponse(
                    date = day.toString(),
                    count = rows.size,
                    seasonStart = season.start(day.year).toString(),
                    seasonEnd = season.end(day.year).toString(),
                    cells = rows.map {
                        ForecastCellDto(
                            h3 = grid.toAddress(it.h3),
                            progression = it.progression,
                            intensity = it.intensity,
                            stage = it.stage.name,
                            confidence = it.confidence,
                        )
                    },
                ),
            )
    }

    /** One cell's whole season, for the detail panel's curve. */
    @GetMapping("/cells/{h3}/timeline")
    fun timeline(@PathVariable h3: String): ResponseEntity<TimelineResponse> {
        val index = grid.fromAddress(h3) ?: return ResponseEntity.badRequest().build()
        val rows = forecasts.timeline(index)
        if (rows.isEmpty()) return ResponseEntity.notFound().build()

        val peak = rows.filter { it.stage.name == "PEAK" }.minByOrNull { it.day }
        return ResponseEntity.ok(
            TimelineResponse(
                h3 = h3,
                peakDay = peak?.day?.toString(),
                days = rows.map {
                    TimelineDayDto(
                        date = it.day.toString(),
                        progression = it.progression,
                        intensity = it.intensity,
                        stage = it.stage.name,
                        confidence = it.confidence,
                    )
                },
            ),
        )
    }

    /**
     * Why this cell scores as it does on this day.
     *
     * Recomputed rather than read: storing per-factor contributions would
     * nearly triple the forecast table (ADR-0004), and this serves one cell.
     */
    @GetMapping("/cells/{h3}/explain")
    fun explain(
        @PathVariable h3: String,
        @RequestParam(required = false) date: String?,
    ): ResponseEntity<ExplainResponse> {
        val index = grid.fromAddress(h3) ?: return ResponseEntity.badRequest().build()
        val day = date?.let { LocalDate.parse(it) } ?: LocalDate.now()
        val score = forecastService.explain(index, day) ?: return ResponseEntity.notFound().build()

        return ResponseEntity.ok(
            ExplainResponse(
                h3 = h3,
                date = day.toString(),
                progression = score.progression,
                intensity = score.intensity,
                stage = score.stage.name,
                confidence = score.confidence,
                factors = score.factors.map { FactorDto(it.name, it.value, it.effect, it.detail) },
            ),
        )
    }
}

data class ForecastResponse(
    val date: String,
    val count: Int,
    val seasonStart: String,
    val seasonEnd: String,
    val cells: List<ForecastCellDto>,
)

data class ForecastCellDto(
    val h3: String,
    val progression: Double,
    val intensity: Double,
    val stage: String,
    val confidence: Double,
)

data class TimelineResponse(val h3: String, val peakDay: String?, val days: List<TimelineDayDto>)

data class TimelineDayDto(
    val date: String,
    val progression: Double,
    val intensity: Double,
    val stage: String,
    val confidence: Double,
)

data class ExplainResponse(
    val h3: String,
    val date: String,
    val progression: Double,
    val intensity: Double,
    val stage: String,
    val confidence: Double,
    val factors: List<FactorDto>,
)

data class FactorDto(val name: String, val value: Double, val effect: String, val detail: String)
