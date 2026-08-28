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
    @Value("\${foliage.grid.min-canopy-pct}") private val minCanopyPct: Int,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** [stateFips] null scores the whole loaded grid rather than one state. */
    fun computeState(stateFips: String?, year: Int = LocalDate.now().year): ForecastRunResult {
        val scope = stateFips ?: "all"
        val runId = audit.start("model", "forecast:$scope")
        var written = 0L
        try {
            // Forest only. The grid stores every tiled cell so the canopy
            // floor can be retuned without re-sampling terrain, but scoring
            // ground with no trees is meaningless -- nationally that is
            // 126,516 of 217,412 cells, some 9.6M rows describing when the
            // Nevada desert changes colour. findAll keeps cells whose canopy
            // is unsampled, so a terrain gap leaves a cell uncoloured rather
            // than deleting it from the map.
            val grid = if (stateFips == null) cells.findAll(minCanopyPct)
                       else cells.findByState(stateFips, minCanopyPct)
            require(grid.isNotEmpty()) { "no cells for $scope -- run the grid bootstrap first" }

            val parents = grid.map { it.parentRes5 }.distinct()
            val series = weather.seriesByCell(parents)
            // HRRR writes at res 6 under each cell's own index, so its rows
            // never collide with Open-Meteo's res 5 parent rows -- they are
            // separate keys. The two sources are therefore merged on read,
            // not on write. See ADR-0006.
            val fineSeries = weather.seriesByCell(grid.map { it.h3 })
            val precipNormals = normals.precipNormalsByCell()
            val chillNormals = normals.chillUnitsByCell()

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
            val seasonStart = season.start(year)
            val rows = ArrayList<StoredForecast>(grid.size * days.size)

            val elapsed = measureTimeMillis {
                for (cell in grid) {
                    val parentSeries = series[cell.parentRes5] ?: continue
                    val reference = referenceElevation[cell.parentRes5]
                    val chill = chillNormals[cell.parentRes5]
                    val inputs = mergeSources(
                        coarse = parentSeries,
                        fine = fineSeries[cell.h3].orEmpty(),
                        cell = cell,
                        reference = reference,
                        chillNormals = chill,
                    )
                    val cumulativeNormal = cumulativePrecip(precipNormals[cell.parentRes5], days)

                    for (day in days) {
                        val score = PhenologyModel.score(
                            cell = CellInput(cell.centroidLat, cell.elevationM),
                            days = inputs,
                            target = day,
                            normalPrecipMm = cumulativeNormal[day],
                            precipFrom = seasonStart,
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

            // Scoped to the state that was just scored. Unscoped, every run
            // reported the whole table's peaks, which made each state look
            // identical to the last.
            val peaks = forecasts.peakDayByCell(stateFips)
            return ForecastRunResult(
                stateFips = scope,
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
        val chill = normals.chillUnitsByCell()[cell.parentRes5]
        // Same merge as computeState, or the explanation would describe
        // different inputs from the ones that produced the score on the map.
        val inputs = mergeSources(
            coarse = parentSeries,
            fine = weather.seriesByCell(listOf(cell.h3))[cell.h3].orEmpty(),
            cell = cell,
            reference = reference,
            chillNormals = chill,
        )
        val cumulative = cumulativePrecip(normals.precipNormalsByCell()[cell.parentRes5], season.days(year))

        return PhenologyModel.score(
            cell = CellInput(cell.centroidLat, cell.elevationM),
            days = inputs,
            target = day,
            normalPrecipMm = cumulative[day],
            precipFrom = season.start(year),
        )
    }

    /**
     * Factor breakdowns for every cell at its own peak day, loading the shared
     * reference data once.
     *
     * Calling [explain] in a loop re-reads every normal and re-queries weather
     * per cell -- an N+1 that took four minutes over 649 cells during the first
     * static export. This does the same work with a handful of queries.
     */
    /** Drops a state's scores; see ForecastRepository.deleteByState. */
    fun clearState(stateFips: String): Int = forecasts.deleteByState(stateFips)

    fun peakFactors(stateFips: String?, peakDays: Map<Long, LocalDate>, year: Int): Map<Long, List<Factor>> {
        // Same forest floor as scoring; see computeState.
        val grid = if (stateFips == null) cells.findAll(minCanopyPct)
                   else cells.findByState(stateFips, minCanopyPct)
        val parents = grid.map { it.parentRes5 }.distinct()
        val series = weather.seriesByCell(parents)
        val fine = weather.seriesByCell(grid.map { it.h3 })
        val precipNormals = normals.precipNormalsByCell()
        val chillNormals = normals.chillUnitsByCell()
        val seasonDays = season.days(year)
        val seasonStart = season.start(year)

        val referenceElevation: Map<Long, Double> = grid
            .groupBy { it.parentRes5 }
            .mapValues { (_, children) ->
                children.mapNotNull { it.elevationM }.takeIf { it.isNotEmpty() }?.average() ?: 0.0
            }

        return grid.mapNotNull { cell ->
            val day = peakDays[cell.h3] ?: return@mapNotNull null
            val parentSeries = series[cell.parentRes5] ?: return@mapNotNull null
            val inputs = mergeSources(
                coarse = parentSeries,
                fine = fine[cell.h3].orEmpty(),
                cell = cell,
                reference = referenceElevation[cell.parentRes5],
                chillNormals = chillNormals[cell.parentRes5],
            )
            val cumulative = cumulativePrecip(precipNormals[cell.parentRes5], seasonDays)
            val score = PhenologyModel.score(
                cell = CellInput(cell.centroidLat, cell.elevationM),
                days = inputs,
                target = day,
                normalPrecipMm = cumulative[day],
                precipFrom = seasonStart,
            )
            cell.h3 to score.factors
        }.toMap()
    }

    /**
     * Combines a cell's own 3 km readings with its parent's ~9 km series.
     *
     * Field-wise on purpose. HRRR supplies temperature at the cell's own
     * resolution and needs no lapse-rate correction; it carries no
     * precipitation at all, so that still comes from Open-Meteo. Replacing the
     * whole day with the finer row would blank the precipitation and silently
     * disable the drought term for exactly the recent days that matter most.
     */
    private fun mergeSources(
        coarse: List<WeatherDay>,
        fine: List<WeatherDay>,
        cell: Cell,
        reference: Double?,
        chillNormals: Map<MonthDay, Double>?,
    ): List<DayInput> {
        val downscaled = coarse.associate { it.day to it.downscaledTo(cell, reference, chillNormals) }
        if (fine.isEmpty()) return downscaled.values.sortedBy { it.day }

        val merged = downscaled.toMutableMap()
        for (row in fine) {
            val existing = merged[row.day]
            merged[row.day] = DayInput(
                day = row.day,
                // The finer source is more trustworthy about provenance too:
                // it is an analysis of what happened, not a forecast.
                kind = row.kind,
                // Native resolution: no lapse-rate correction applied.
                tmaxC = row.tmaxC ?: existing?.tmaxC,
                tminC = row.tminC ?: existing?.tminC,
                precipMm = row.precipMm ?: existing?.precipMm,
                chillUnits = existing?.chillUnits.takeIf { row.tminC == null },
            )
        }
        return merged.values.sortedBy { it.day }
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

    /**
     * Corrects a parent cell's reading to this cell's own elevation, and for
     * climatological days supplies chilling that was averaged as a derived
     * quantity rather than derived from averaged temperature. See V6.
     *
     * The lapse-rate correction is applied to the chilling override too: a
     * ridge is colder than the parent cell's mean, so it chills more, and the
     * whole point of scoring at res 6 is to express that.
     */
    private fun WeatherDay.downscaledTo(
        cell: Cell,
        referenceElevationM: Double?,
        chillNormals: Map<MonthDay, Double>?,
    ): DayInput {
        val tmin = LapseRate.adjust(tminC, cell.elevationM, referenceElevationM)
        val chill = if (kind == com.foliage.domain.WeatherKind.CLIMATOLOGY) {
            chillNormals?.get(MonthDay.from(day))?.let { base ->
                val cooling = (tmin ?: 0.0) - (tminC ?: 0.0)
                (base - cooling).coerceAtLeast(0.0)
            }
        } else {
            null
        }
        return DayInput(
            day = day,
            kind = kind,
            tmaxC = LapseRate.adjust(tmaxC, cell.elevationM, referenceElevationM),
            tminC = tmin,
            precipMm = precipMm,
            chillUnits = chill,
        )
    }
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
