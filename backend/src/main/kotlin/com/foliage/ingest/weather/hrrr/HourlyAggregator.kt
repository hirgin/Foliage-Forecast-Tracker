package com.foliage.ingest.weather.hrrr

import com.foliage.domain.DailyRecord
import java.time.LocalDate

/**
 * Collapses hourly HRRR samples into the daily record the model consumes.
 *
 * HRRR publishes instantaneous fields hourly; the phenology model wants daily
 * maxima and minima. Note this is a *sampled* extreme — the true daily maximum
 * may fall between two hourly snapshots — so it runs slightly cooler at the
 * top and warmer at the bottom than a continuously observed extreme would.
 * At the resolution the model works in that is immaterial, but it is a real
 * difference from Open-Meteo's daily aggregates and worth stating.
 */
object HourlyAggregator {

    /**
     * Minimum hours required before a day is trusted.
     *
     * A day assembled from three morning hours would report a maximum that is
     * simply wrong rather than merely imprecise. Below this the day is dropped,
     * and the layer beneath supplies it instead.
     */
    const val MIN_HOURS = 18

    /**
     * Builds one day from hourly temperatures in Celsius. Missing hours are
     * `null` entries and are ignored; returns null when too few remain.
     *
     * [precipMm] and [radiationMj] are carried through untouched: HRRR's
     * surface analysis has no precipitation accumulation, so those come from
     * whatever source did supply them, and must not be invented here.
     */
    fun daily(
        day: LocalDate,
        hourlyTempC: List<Double?>,
        precipMm: Double? = null,
        radiationMj: Double? = null,
    ): DailyRecord? {
        val present = hourlyTempC.filterNotNull()
        if (present.size < MIN_HOURS) return null

        return DailyRecord(
            day = day,
            tmaxC = present.max(),
            tminC = present.min(),
            precipMm = precipMm,
            radiationMj = radiationMj,
        )
    }

    /**
     * Transposes hour-major samples into point-major series.
     *
     * The fetch loop reads one GRIB file per hour and samples every point from
     * it — opening 24 files once each rather than one file 649 times. That
     * yields `[hour][point]`, but callers need `[point][hour]`.
     */
    fun transpose(byHour: List<List<Double?>>, pointCount: Int): List<List<Double?>> {
        require(byHour.all { it.size == pointCount }) {
            "every hour must carry one sample per point; expected $pointCount"
        }
        return (0 until pointCount).map { p -> byHour.map { hour -> hour[p] } }
    }
}
