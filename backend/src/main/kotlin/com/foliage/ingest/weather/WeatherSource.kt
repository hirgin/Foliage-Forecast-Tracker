package com.foliage.ingest.weather

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.foliage.domain.DailyRecord
import com.foliage.domain.WeatherKind
import com.foliage.grid.LonLat
import java.time.LocalDate

/**
 * Daily weather for a set of points.
 *
 * The seam that lets NOAA HRRR replace Open-Meteo in phase 6 without anything
 * downstream noticing. Implementations must not leak source-specific types.
 */
interface WeatherSource {

    /** Daily records per point, in input order. An empty list means that point failed. */
    fun daily(points: List<LonLat>, from: LocalDate, to: LocalDate): List<List<DailyRecord>>

    /** The H3 resolution this source is natively accurate at. See ADR-0005. */
    val nativeResolution: Int
}

/**
 * Classifies a date's provenance.
 *
 * Kept separate from any source so the rule is stated once and tested
 * directly: it decides what the site is allowed to claim about a given day.
 */
object WeatherKindPolicy {

    /** Open-Meteo's hard limit; `forecast_days=17` returns HTTP 400. */
    const val FORECAST_HORIZON_DAYS = 16L

    fun classify(day: LocalDate, today: LocalDate): WeatherKind = when {
        day.isBefore(today) -> WeatherKind.OBSERVED
        !day.isAfter(today.plusDays(FORECAST_HORIZON_DAYS)) -> WeatherKind.FORECAST
        else -> WeatherKind.CLIMATOLOGY
    }
}

/**
 * Parsing for Open-Meteo's daily response.
 *
 * The response shape depends on how many coordinates were requested: a bare
 * object for one, an array for several. Handling only the array form works in
 * testing and then silently breaks on the last batch of a run whose size
 * happens to be one.
 */
object OpenMeteoDailyParser {

    private val mapper = ObjectMapper()

    fun parse(json: String, expectedLocations: Int): List<List<DailyRecord>> {
        val root = mapper.readTree(json)
        val locations: List<JsonNode> = if (root.isArray) root.toList() else listOf(root)

        return (0 until expectedLocations).map { i ->
            locations.getOrNull(i)?.let { parseLocation(it) } ?: emptyList()
        }
    }

    private fun parseLocation(node: JsonNode): List<DailyRecord> {
        val daily = node.path("daily")
        val times = daily.path("time")
        if (!times.isArray) return emptyList()

        return times.mapIndexed { i, t ->
            DailyRecord(
                day = LocalDate.parse(t.asText()),
                tmaxC = daily.num("temperature_2m_max", i),
                tminC = daily.num("temperature_2m_min", i),
                precipMm = daily.num("precipitation_sum", i),
                radiationMj = daily.num("shortwave_radiation_sum", i),
            )
        }
    }

    /** Open-Meteo emits JSON null for gaps; treat anything non-numeric as missing. */
    private fun JsonNode.num(field: String, i: Int): Double? =
        path(field).path(i).takeIf { it.isNumber }?.asDouble()
}
