package com.foliage.ingest.weather

import com.foliage.domain.DailyRecord
import com.foliage.domain.WeatherDay
import com.foliage.domain.WeatherKind
import com.foliage.domain.WeatherNormal
import com.foliage.forecast.PhenologyModel
import com.foliage.grid.H3Grid
import com.foliage.grid.LonLat
import com.foliage.ingest.weather.hrrr.HrrrWeatherSource
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
    private val hrrr: HrrrWeatherSource,
    private val season: Season,
    private val audit: IngestRunRecorder,
    @Value("\${foliage.weather.climatology-years}") private val climatologyYears: Int,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** Observed trailing window plus the 16-day forecast, in one pass. */
    /** [stateFips] null covers every res 5 parent in the loaded grid. */
    fun refreshForecast(stateFips: String?, today: LocalDate = LocalDate.now()): WeatherIngestResult {
        val scope = stateFips ?: "all"
        val runId = audit.start("open-meteo", "weather-forecast:$scope")
        var written = 0L
        try {
            val parents = if (stateFips == null) cells.allRes5Parents()
                          else cells.distinctRes5Parents(stateFips)
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

            return result(scope, parents.size, series, written)
        } catch (e: Exception) {
            audit.fail(runId, written, e)
            throw e
        }
    }

    /**
     * Refines the recent window with HRRR at native 3 km.
     *
     * Unlike the other jobs this samples **res 6 cells directly** -- 649 of
     * them for Vermont rather than 110 res 5 parents -- because that is the
     * entire point: those cells get their own weather instead of a lapse-rate
     * downscale. Bandwidth is bounded by hours, not cells, since one fetched
     * hour is sampled at every point.
     *
     * Runs after the Open-Meteo pass. The upsert prefers the finer resolution
     * within the same provenance and merges field-wise, so HRRR's temperatures
     * win while Open-Meteo's precipitation survives -- HRRR's surface analysis
     * carries none.
     */
    fun refreshHrrr(stateFips: String?, days: Int, today: LocalDate = LocalDate.now()): WeatherIngestResult {
        val scope = stateFips ?: "all"
        val runId = audit.start("noaa-hrrr", "weather-hrrr:$scope")
        var written = 0L
        try {
            val cells6 = if (stateFips == null) cells.findAll(0) else cells.findByState(stateFips, 0)
            val points = cells6.map { LonLat(it.centroidLon, it.centroidLat) }
            // Yesterday backwards: today's later hours have not been published.
            val to = today.minusDays(1)
            val from = to.minusDays((days - 1).toLong())
            log.info("HRRR {} to {} at {} res 6 cells", from, to, points.size)

            val series = hrrr.daily(points, from, to)

            val rows = cells6.flatMapIndexed { i, cell ->
                series[i].map { r ->
                    WeatherDay(
                        h3 = cell.h3,
                        day = r.day,
                        resolution = hrrr.nativeResolution,
                        kind = WeatherKind.OBSERVED,
                        tmaxC = r.tmaxC,
                        tminC = r.tminC,
                        precipMm = r.precipMm,
                        radiationMj = r.radiationMj,
                    )
                }
            }
            written = weather.upsertAll(rows).toLong()
            audit.succeed(runId, written)

            return WeatherIngestResult(
                stateFips = scope,
                cellsRequested = points.size,
                cellsWithData = series.count { it.isNotEmpty() },
                rowsWritten = written,
                yearsAveraged = null,
                byKind = weather.countByKind(),
                coverageFrom = rows.minOfOrNull { it.day }?.toString(),
                coverageTo = rows.maxOfOrNull { it.day }?.toString(),
            )
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
    /**
     * Five-year normals for a state, built in resumable chunks.
     *
     * **Chunked because a state can be bigger than a day's allowance.** This
     * used to fetch every parent's five years into memory and write once at
     * the end, so a quota abort threw all of it away. New York needs 559 res 5
     * parents and cannot finish inside one day of the free tier, and it sits
     * near the front of the queue -- so the nightly backfill spent nine
     * minutes on it, wrote nothing, and did the same again the next night.
     * The load could never progress past it.
     *
     * Now each chunk is written as it completes, and parents that already have
     * normals are skipped, so a state accumulates across nights instead of
     * restarting. Quota exhaustion stops the run with everything up to that
     * point saved.
     */
    fun buildClimatology(
        stateFips: String?,
        today: LocalDate = LocalDate.now(),
        chunkSize: Int = 80,
    ): WeatherIngestResult {
        val scope = stateFips ?: "all"
        val runId = audit.start("open-meteo-archive", "weather-climatology:$scope")
        var written = 0L
        var cellsDone = 0
        var earliest: LocalDate? = null
        var latest: LocalDate? = null
        try {
            val allParents = if (stateFips == null) cells.allRes5Parents()
                             else cells.distinctRes5Parents(stateFips)
            // Resume: whatever an earlier run managed is not fetched again.
            val covered = normals.cellsWithNormals()
            val parents = allParents.filterNot { it in covered }
            val year = today.year
            log.info(
                "climatology for {}: {} of {} parents still needed",
                scope, parents.size, allParents.size,
            )

            for (chunk in parents.chunked(chunkSize)) {
                val points = chunk.map { grid.centroid(it) }

                // Accumulate per cell, per calendar day, across past seasons.
                val sums = HashMap<Pair<Int, MonthDay>, MutableList<DailyRecord>>()
                for (back in 1..climatologyYears) {
                    val y = year - back
                    val series = source.archive(points, season.start(y), season.end(y))
                    series.forEachIndexed { i, recs ->
                        recs.forEach { r ->
                            sums.getOrPut(i to MonthDay.from(r.day)) { mutableListOf() }.add(r)
                        }
                    }
                }

                // Normals go to their own table and are never overwritten by
                // daily ingest -- the drought term needs them on days we also
                // have observations for. See the amendment to ADR-0005.
                val normalRows = sums.map { (key, recs) ->
                    val (i, md) = key
                    val minima = recs.mapNotNull { it.tminC }
                    WeatherNormal(
                        h3 = chunk[i],
                        monthDay = md,
                        resolution = source.nativeResolution,
                        tmaxC = recs.meanOf { it.tmaxC },
                        tminC = recs.meanOf { it.tminC },
                        precipMm = recs.meanOf { it.precipMm },
                        // Chilling is computed per year and *then* averaged.
                        // Doing it the other way round -- deriving it from the
                        // mean tmin -- destroys the signal, because the
                        // threshold is nonlinear and cold snaps fall on
                        // different dates each year.
                        chillUnits = minima
                            .map { (PhenologyModel.CHILL_THRESHOLD_C - it).coerceAtLeast(0.0) }
                            .takeIf { it.isNotEmpty() }?.average(),
                        frostFrequency = minima
                            .takeIf { it.isNotEmpty() }
                            ?.let { m -> m.count { it <= 0.0 }.toDouble() / m.size },
                        yearsAveraged = climatologyYears,
                    )
                }
                normals.upsertAll(normalRows)

                // The same means also seed weather_daily as a fallback
                // estimate, under the usual precedence rule.
                val rows = sums.mapNotNull { (key, recs) ->
                    val (i, md) = key
                    val day = runCatching { md.atYear(year) }.getOrNull() ?: return@mapNotNull null
                    DailyRecord(
                        day = day,
                        tmaxC = recs.meanOf { it.tmaxC },
                        tminC = recs.meanOf { it.tminC },
                        precipMm = recs.meanOf { it.precipMm },
                        radiationMj = recs.meanOf { it.radiationMj },
                    ).toWeatherDay(chunk[i], WeatherKind.CLIMATOLOGY)
                }
                written += weather.upsertAll(rows).toLong()
                cellsDone += chunk.size
                rows.minOfOrNull { it.day }?.let { d -> earliest = earliest?.coerceAtMost(d) ?: d }
                rows.maxOfOrNull { it.day }?.let { d -> latest = latest?.coerceAtLeast(d) ?: d }
                log.info("climatology {}: {} of {} parents done", scope, cellsDone, parents.size)
            }

            audit.succeed(runId, written)
            return WeatherIngestResult(
                stateFips = scope,
                cellsRequested = parents.size,
                cellsWithData = cellsDone,
                rowsWritten = written,
                yearsAveraged = climatologyYears,
                byKind = weather.countByKind(),
                coverageFrom = earliest?.toString(),
                coverageTo = latest?.toString(),
            )
        } catch (e: Exception) {
            // Whatever completed is already committed; only the remainder is
            // lost, and the next run picks it up.
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
