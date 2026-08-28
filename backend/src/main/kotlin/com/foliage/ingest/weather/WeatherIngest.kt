package com.foliage.ingest.weather

import com.foliage.domain.DailyRecord
import com.foliage.domain.WeatherDay
import com.foliage.domain.WeatherKind
import com.foliage.domain.WeatherNormal
import com.foliage.grid.H3Grid
import com.foliage.ingest.audit.IngestRunRecorder
import com.foliage.persistence.CellRepository
import com.foliage.persistence.NormalRepository
import com.foliage.persistence.WeatherRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.time.LocalDate
import java.time.MonthDay

/**
 * Populates `weather_daily` for a state's res 5 cells.
 *
 * Two jobs, matching ADR-0005. Both are idempotent, and the repository's
 * upsert refuses to downgrade a day's provenance, so they can run in any
 * order and any number of times.
 */
@Service
class WeatherIngest(
    private val grid: H3Grid,
    private val cells: CellRepository,
    private val weather: WeatherRepository,
    private val normals: NormalRepository,
    private val source: OpenMeteoWeatherSource,
    private val season: Season,
    private val audit: IngestRunRecorder,
    @Value("\${foliage.weather.climatology-years}") private val climatologyYears: Int,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** Observed trailing window plus the 16-day forecast, in one pass. */
    fun refreshForecast(stateFips: String, today: LocalDate = LocalDate.now()): WeatherIngestResult {
        val runId = audit.start("open-meteo", "weather-forecast:$stateFips")
        var written = 0L
        try {
            val parents = cells.distinctRes5Parents(stateFips)
            val points = parents.map { grid.centroid(it) }
            log.info("fetching forecast for {} res 5 cells in state {}", parents.size, stateFips)

            val series = source.daily(points, today.minusDays(90), today.plusDays(16))

            val rows = parents.flatMapIndexed { i, h3 ->
                series[i].map { r ->
                    r.toWeatherDay(h3, WeatherKindPolicy.classify(r.day, today))
                }
            }
            written = weather.upsertAll(rows).toLong()
            audit.succeed(runId, written)

            return result(stateFips, parents.size, series, written)
        } catch (e: Exception) {
            audit.fail(runId, written, e)
            throw e
        }
    }

    /**
     * Fills the far season from a multi-year archive mean.
     *
     * This is the only thing that can say anything about October in August.
     * Written for the whole season; the upsert leaves observed and forecast
     * days alone, so it only ever lands where nothing better exists.
     */
    fun buildClimatology(stateFips: String, today: LocalDate = LocalDate.now()): WeatherIngestResult {
        val runId = audit.start("open-meteo-archive", "weather-climatology:$stateFips")
        var written = 0L
        try {
            val parents = cells.distinctRes5Parents(stateFips)
            val points = parents.map { grid.centroid(it) }
            val year = today.year

            // Accumulate per cell, per calendar day, across past seasons.
            val sums = HashMap<Pair<Int, MonthDay>, MutableList<DailyRecord>>()
            for (back in 1..climatologyYears) {
                val y = year - back
                log.info("archive {} for {} cells", y, parents.size)
                val series = source.archive(points, season.start(y), season.end(y))
                series.forEachIndexed { i, recs ->
                    recs.forEach { r ->
                        sums.getOrPut(i to MonthDay.from(r.day)) { mutableListOf() }.add(r)
                    }
                }
            }

            // Normals go to their own table and are never overwritten by daily
            // ingest -- the drought term needs them on days we also have
            // observations for. See the amendment to ADR-0005.
            val normalRows = sums.map { (key, recs) ->
                val (i, md) = key
                WeatherNormal(
                    h3 = parents[i],
                    monthDay = md,
                    resolution = source.nativeResolution,
                    tmaxC = recs.meanOf { it.tmaxC },
                    tminC = recs.meanOf { it.tminC },
                    precipMm = recs.meanOf { it.precipMm },
                    yearsAveraged = climatologyYears,
                )
            }
            val normalsWritten = normals.upsertAll(normalRows)
            log.info("wrote {} climatological normals", normalsWritten)

            // The same means also seed weather_daily as a fallback estimate,
            // under the usual precedence rule.
            val rows = sums.mapNotNull { (key, recs) ->
                val (i, md) = key
                val day = runCatching { md.atYear(year) }.getOrNull() ?: return@mapNotNull null
                DailyRecord(
                    day = day,
                    tmaxC = recs.meanOf { it.tmaxC },
                    tminC = recs.meanOf { it.tminC },
                    precipMm = recs.meanOf { it.precipMm },
                    radiationMj = recs.meanOf { it.radiationMj },
                ).toWeatherDay(parents[i], WeatherKind.CLIMATOLOGY)
            }

            written = weather.upsertAll(rows).toLong()
            audit.succeed(runId, written)

            return WeatherIngestResult(
                stateFips = stateFips,
                cellsRequested = parents.size,
                cellsWithData = rows.map { it.h3 }.distinct().size,
                rowsWritten = written,
                yearsAveraged = climatologyYears,
                byKind = weather.countByKind(),
                coverageFrom = rows.minOfOrNull { it.day }?.toString(),
                coverageTo = rows.maxOfOrNull { it.day }?.toString(),
            )
        } catch (e: Exception) {
            audit.fail(runId, written, e)
            throw e
        }
    }

    private fun result(
        stateFips: String,
        requested: Int,
        series: List<List<DailyRecord>>,
        written: Long,
    ) = WeatherIngestResult(
        stateFips = stateFips,
        cellsRequested = requested,
        cellsWithData = series.count { it.isNotEmpty() },
        rowsWritten = written,
        yearsAveraged = null,
        byKind = weather.countByKind(),
        coverageFrom = series.flatten().minOfOrNull { it.day }?.toString(),
        coverageTo = series.flatten().maxOfOrNull { it.day }?.toString(),
    )

    private fun DailyRecord.toWeatherDay(h3: Long, kind: WeatherKind) = WeatherDay(
        h3 = h3,
        day = day,
        resolution = source.nativeResolution,
        kind = kind,
        tmaxC = tmaxC,
        tminC = tminC,
        precipMm = precipMm,
        radiationMj = radiationMj,
    )

    /** Mean of the present values; null if a variable was missing in every year. */
    private fun List<DailyRecord>.meanOf(pick: (DailyRecord) -> Double?): Double? =
        mapNotNull(pick).takeIf { it.isNotEmpty() }?.average()
}

data class WeatherIngestResult(
    val stateFips: String,
    val cellsRequested: Int,
    val cellsWithData: Int,
    val rowsWritten: Long,
    val yearsAveraged: Int?,
    val byKind: Map<String, Long>,
    val coverageFrom: String?,
    val coverageTo: String?,
)
