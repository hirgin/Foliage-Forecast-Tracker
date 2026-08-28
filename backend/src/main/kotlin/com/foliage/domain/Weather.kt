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

/**
 * A climatological normal: what a calendar day is usually like at a cell.
 *
 * Year-independent by definition, which is why it is keyed by [monthDay]
 * rather than a date. Distinct from a CLIMATOLOGY row in `weather_daily`,
 * which is a fallback *estimate* for one specific day and gets replaced as
 * real data arrives. See the amendment to ADR-0005.
 */
data class WeatherNormal(
    val h3: Long,
    val monthDay: java.time.MonthDay,
    val resolution: Int,
    val tmaxC: Double?,
    val tminC: Double?,
    val precipMm: Double?,
    val yearsAveraged: Int,
)
