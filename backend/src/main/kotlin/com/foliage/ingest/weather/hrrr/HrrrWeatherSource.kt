package com.foliage.ingest.weather.hrrr

import com.foliage.domain.DailyRecord
import com.foliage.grid.LonLat
import com.foliage.ingest.weather.WeatherSource
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.io.path.deleteIfExists

/**
 * NOAA HRRR as a [WeatherSource], at a native 3 km.
 *
 * This does **not** replace Open-Meteo. HRRR reaches 48 hours from the
 * synoptic cycles and 18 from the rest, against a 76-day season, so it refines
 * the recent past and the next couple of days and cannot speak to October at
 * all. It layers above Open-Meteo rather than displacing it — see ADR-0006.
 *
 * What it buys: `nativeResolution` is 6, so cells sampled from HRRR need no
 * lapse-rate downscale. They get their own weather.
 *
 * The cost is bandwidth. A day is 24 hourly analyses at roughly 1.2 MB each,
 * so about 29 MB per day of coverage, which is why [maxDays] exists.
 */
@Component
class HrrrWeatherSource(
    private val client: HrrrClient,
    private val sampler: Grib2Sampler,
    @Value("\${foliage.hrrr.max-days}") private val maxDays: Int,
    @Value("\${foliage.hrrr.min-hours-per-day}") private val minHours: Int,
) : WeatherSource {

    private val log = LoggerFactory.getLogger(javaClass)

    /** 3 km, matching H3 resolution 6. The whole point of this source. */
    override val nativeResolution = 6

    override fun daily(points: List<LonLat>, from: LocalDate, to: LocalDate): List<List<DailyRecord>> {
        if (points.isEmpty()) return emptyList()

        val days = generateSequence(from) { it.plusDays(1) }
            .takeWhile { !it.isAfter(to) }
            .toList()

        // A guard, not a preference. 90 days would be 2,160 range requests and
        // some 2.5 GB, which is a mistake worth refusing rather than making.
        require(days.size <= maxDays) {
            "HRRR ingest is limited to $maxDays days (asked for ${days.size}); " +
                "it covers the recent window only, and Open-Meteo supplies the rest"
        }

        val byDay = days.map { day -> day to collectDay(day, points) }
        return points.indices.map { p ->
            byDay.mapNotNull { (_, perPoint) -> perPoint?.getOrNull(p) }
        }
    }

    /** One day's records for every point, or null if the day is unusable. */
    private fun collectDay(day: LocalDate, points: List<LonLat>): List<DailyRecord?>? {
        val hourly = ArrayList<List<Double?>>(24)
        val scratch = Files.createTempDirectory("hrrr-$day")

        try {
            for (hour in 0..23) {
                val run = HrrrRun.analysisAt(day.atTime(hour, 0).toInstant(ZoneOffset.UTC))
                val file = scratch.resolve("t%02dz.grib2".format(hour))
                val fetched = client.fetchVariable(run, "TMP", "2 m above ground", file)

                if (fetched == null) {
                    // A missing hour is normal near the edge of the rolling
                    // window. Record the gap and let the hour threshold decide.
                    hourly += List(points.size) { null }
                    continue
                }
                hourly += sampler.sample(fetched, points).map { sampler.kelvinToCelsius(it) }
                file.deleteIfExists()
            }
        } finally {
            runCatching { Files.deleteIfExists(scratch) }
        }

        val present = hourly.count { row -> row.any { it != null } }
        if (present < minHours) {
            log.info("{}: only {} of 24 hours available, leaving the day to Open-Meteo", day, present)
            return null
        }

        log.info("{}: assembled from {} hourly analyses at {} points", day, present, points.size)
        val byPoint = HourlyAggregator.transpose(hourly, points.size)
        return byPoint.map { series -> HourlyAggregator.daily(day, series) }
    }
}
