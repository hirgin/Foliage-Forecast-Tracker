package com.foliage.ingest.weather.hrrr

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Addresses one HRRR product: a model cycle plus a forecast hour.
 *
 * HRRR runs hourly and reaches **48 hours ahead from the synoptic cycles**
 * (00/06/12/18z) but only **18** from the others. That is the fact that
 * decides what this source can and cannot do: it refines the recent past and
 * the next couple of days at 3 km, and it cannot answer anything about
 * October. Open-Meteo keeps the 16-day horizon and the climatology.
 */
data class HrrrRun(
    val date: LocalDate,
    val cycleHour: Int,
    val forecastHour: Int,
) {
    init {
        require(cycleHour in 0..23) { "cycle hour must be 0..23, got $cycleHour" }
        require(forecastHour >= 0) { "forecast hour must be >= 0, got $forecastHour" }
        require(forecastHour <= maxForecastHour(cycleHour)) {
            "cycle ${cycleHour}z reaches only ${maxForecastHour(cycleHour)} h, asked for $forecastHour"
        }
    }

    /** The instant this product is valid for. */
    val validAt: Instant
        get() = date.atStartOfDay(ZoneOffset.UTC).toInstant()
            .plus(Duration.ofHours((cycleHour + forecastHour).toLong()))

    /** Key within the NOAA bucket, e.g. `hrrr.20260827/conus/hrrr.t12z.wrfsfcf00.grib2`. */
    val key: String
        get() = "hrrr.%s/conus/hrrr.t%02dz.wrfsfcf%02d.grib2".format(
            date.format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE),
            cycleHour,
            forecastHour,
        )

    val indexKey: String get() = "$key.idx"

    companion object {
        /** Cycles that run out to 48 hours; every other cycle stops at 18. */
        val SYNOPTIC_CYCLES = setOf(0, 6, 12, 18)

        const val SYNOPTIC_MAX_HOUR = 48
        const val STANDARD_MAX_HOUR = 18

        fun maxForecastHour(cycleHour: Int): Int =
            if (cycleHour in SYNOPTIC_CYCLES) SYNOPTIC_MAX_HOUR else STANDARD_MAX_HOUR

        /**
         * The analysis valid at [instant] — the model's own best estimate of
         * conditions then, taken from that hour's own cycle at forecast hour 0.
         * This is what to use for observed weather.
         */
        fun analysisAt(instant: Instant): HrrrRun {
            val utc = instant.atZone(ZoneOffset.UTC)
            return HrrrRun(utc.toLocalDate(), utc.hour, 0)
        }

        /**
         * A product valid at [target] produced by the most recent cycle at or
         * before [issuedBy], or null if [target] is beyond that cycle's reach.
         *
         * Real ingest also has to allow for publication lag: a cycle appears in
         * the bucket roughly 45-90 minutes after its nominal time, which is why
         * callers pass an explicit [issuedBy] rather than "now".
         */
        fun forecastFor(target: Instant, issuedBy: Instant): HrrrRun? {
            val cycle = issuedBy.atZone(ZoneOffset.UTC).withMinute(0).withSecond(0).withNano(0)
            val lead = Duration.between(cycle.toInstant(), target).toHours()
            if (lead < 0) return null

            val max = maxForecastHour(cycle.hour)
            if (lead > max) return null

            return HrrrRun(cycle.toLocalDate(), cycle.hour, lead.toInt())
        }

        /**
         * How far ahead HRRR can see from a given moment: the best reach of any
         * cycle at or before it. Used to decide where HRRR stops and
         * Open-Meteo takes over.
         */
        fun horizonFrom(issuedBy: Instant): Duration {
            val hour = issuedBy.atZone(ZoneOffset.UTC).hour
            return Duration.ofHours(maxForecastHour(hour).toLong())
        }
    }
}
