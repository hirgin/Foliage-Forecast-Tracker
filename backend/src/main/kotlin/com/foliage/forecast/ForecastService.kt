package com.foliage.forecast

import com.foliage.domain.Cell
import com.foliage.domain.WeatherDay
import com.foliage.ingest.audit.IngestRunRecorder
import com.foliage.ingest.weather.Season
import com.foliage.persistence.CellRepository
import com.foliage.persistence.ForecastRepository
import com.foliage.persistence.NormalRepository
import com.foliage.persistence.StoredForecast
import com.foliage.persistence.WeatherRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.time.LocalDate
import java.time.MonthDay
import kotlin.system.measureTimeMillis

/**
 * Runs the phenology model over a state's grid and stores the result.
 *
 * The join that matters: weather lives at res 5 because that is Open-Meteo's
 * native accuracy, but scoring happens at res 6 so each cell can be corrected
 * to its own elevation. That downscale is the reason a hexagon grid beats
 * county averages at all.
 */
@Service
class ForecastService(
    private val cells: CellRepository,
    private val weather: WeatherRepository,
    private val normals: NormalRepository,
    private val forecasts: ForecastRepository,
    private val season: Season,
    private val audit: IngestRunRecorder,
    @Value("\${foliage.model-version}") private val modelVersion: String,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun computeState(stateFips: String, year: Int = LocalDate.now().year): ForecastRunResult {
        val runId = audit.start("model", "forecast:$stateFips")
        var written = 0L
        try {
            val grid = cells.findByState(stateFips, minCanopyPct = 0)
            require(grid.isNotEmpty()) { "no cells for state $stateFips -- run the grid bootstrap first" }

            val parents = grid.map { it.parentRes5 }.distinct()
            val series = weather.seriesByCell(parents)
            val precipNormals = normals.precipNormalsByCell()

            // A res 5 cell's reference elevation is the mean of its res 6
            // children. The weather was reported for the parent as a whole, so
            // that mean is the height it effectively describes; each child is
            // then corrected relative to it.
            val referenceElevation: Map<Long, Double> = grid
                .groupBy { it.parentRes5 }
                .mapValues { (_, children) ->
                    children.mapNotNull { it.elevationM }.takeIf { it.isNotEmpty() }?.average() ?: 0.0
                }

            val days = season.days(year)
            val rows = ArrayList<StoredForecast>(grid.size * days.size)

            val elapsed = measureTimeMillis {
                for (cell in grid) {
                    val parentSeries = series[cell.parentRes5] ?: continue
                    val reference = referenceElevation[cell.parentRes5]
                    val inputs = parentSeries.map { it.downscaledTo(cell, reference) }
                    val cumulativeNormal = cumulativePrecip(precipNormals[cell.parentRes5], days)

                    for (day in days) {
                        val score = PhenologyModel.score(
                            cell = CellInput(cell.centroidLat, cell.elevationM),
                            days = inputs,
                            target = day,
                            normalPrecipMm = cumulativeNormal[day],
                        )
                        rows += StoredForecast(
                            h3 = cell.h3,
                            day = day,
                            progression = score.progression,
                            intensity = score.intensity,
                            stage = score.stage,
                            confidence = score.confidence,
                        )
                    }
                }
            }
            log.info("scored {} cell-days in {} ms", rows.size, elapsed)

            written = forecasts.upsertAll(rows, modelVersion).toLong()
            audit.succeed(runId, written)

            val peaks = forecasts.peakDayByCell()
            return ForecastRunResult(
                stateFips = stateFips,
                cells = grid.size,
                days = days.size,
                rowsWritten = written,
                scoringMs = elapsed,
                cellsReachingPeak = peaks.size,
                earliestPeak = peaks.values.minOrNull()?.toString(),
                medianPeak = peaks.values.sorted().let { if (it.isEmpty()) null else it[it.size / 2].toString() },
                latestPeak = peaks.values.maxOrNull()?.toString(),
            )
        } catch (e: Exception) {
            audit.fail(runId, written, e)
            throw e
        }
    }

    /** Everything the explain endpoint needs for one cell on one day. */
    fun explain(h3: Long, day: LocalDate, year: Int = LocalDate.now().year): FoliageScore? {
        val cell = cells.findByH3(h3) ?: return null
        val parentSeries = weather.seriesByCell(listOf(cell.parentRes5))[cell.parentRes5] ?: return null

        val siblings = cells.findByParent(cell.parentRes5)
        val reference = siblings.mapNotNull { it.elevationM }.takeIf { it.isNotEmpty() }?.average()
        val inputs = parentSeries.map { it.downscaledTo(cell, reference) }
        val cumulative = cumulativePrecip(normals.precipNormalsByCell()[cell.parentRes5], season.days(year))

        return PhenologyModel.score(
            cell = CellInput(cell.centroidLat, cell.elevationM),
            days = inputs,
            target = day,
            normalPrecipMm = cumulative[day],
        )
    }

    /** Normal precipitation accumulated from season start to each day. */
    private fun cumulativePrecip(
        byMonthDay: Map<MonthDay, Double>?,
        days: List<LocalDate>,
    ): Map<LocalDate, Double?> {
        if (byMonthDay == null) return days.associateWith { null }
        var running = 0.0
        return days.associateWith { d ->
            running += byMonthDay[MonthDay.from(d)] ?: 0.0
            running
        }
    }

    /** Corrects a parent cell's reading to this cell's own elevation. */
    private fun WeatherDay.downscaledTo(cell: Cell, referenceElevationM: Double?) = DayInput(
        day = day,
        kind = kind,
        tmaxC = LapseRate.adjust(tmaxC, cell.elevationM, referenceElevationM),
        tminC = LapseRate.adjust(tminC, cell.elevationM, referenceElevationM),
        precipMm = precipMm,
    )
}

data class ForecastRunResult(
    val stateFips: String,
    val cells: Int,
    val days: Int,
    val rowsWritten: Long,
    val scoringMs: Long,
    val cellsReachingPeak: Int,
    val earliestPeak: String?,
    val medianPeak: String?,
    val latestPeak: String?,
)
