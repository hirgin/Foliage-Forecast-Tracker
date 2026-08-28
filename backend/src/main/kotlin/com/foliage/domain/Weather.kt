package com.foliage.domain

import java.time.LocalDate

/**
 * Where a cell-day's weather came from. See ADR-0005 — the forecast horizon is
 * 16 days but the season is ~75, so most of the season is climatology until it
 * draws close.
 */
enum class WeatherKind { OBSERVED, FORECAST, CLIMATOLOGY }

/** One day of weather at one cell. */
data class WeatherDay(
    val h3: Long,
    val day: LocalDate,
    val resolution: Int,
    val kind: WeatherKind,
    val tmaxC: Double?,
    val tminC: Double?,
    val precipMm: Double?,
    val radiationMj: Double?,
)

/** A source-agnostic daily record, before it is attached to a cell. */
data class DailyRecord(
    val day: LocalDate,
    val tmaxC: Double?,
    val tminC: Double?,
    val precipMm: Double?,
    val radiationMj: Double?,
)
