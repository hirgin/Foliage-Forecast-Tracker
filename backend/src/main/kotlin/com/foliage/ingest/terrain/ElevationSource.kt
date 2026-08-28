package com.foliage.ingest.terrain

import com.fasterxml.jackson.databind.ObjectMapper
import com.foliage.grid.LonLat

/**
 * Ground elevation at a set of points.
 *
 * Elevation is not decoration here: it is the dominant sub-grid driver of
 * foliage timing, and the reason a hexagon grid beats county averages at all.
 * It also carries the lapse-rate correction that downscales res 5 weather to
 * res 6 cells while Open-Meteo is the weather source. See ADR-0002.
 */
interface ElevationSource {

    /** Metres above sea level for each point, in input order; null if unavailable. */
    fun elevation(points: List<LonLat>): List<Int?>
}

/**
 * Parsing for Open-Meteo's `/v1/elevation` response. Split from transport so
 * it can be driven by a captured fixture; see docs/testing.md.
 */
object OpenMeteoElevationParser {

    private val mapper = ObjectMapper()

    /**
     * Open-Meteo returns a bare `elevation` array positionally matched to the
     * request's coordinate list. A short or absent array is treated as missing
     * data rather than an error, so one bad batch cannot abort a bootstrap.
     */
    fun parse(json: String, pointCount: Int): List<Int?> {
        val node = mapper.readTree(json).path("elevation")
        return (0 until pointCount).map { i ->
            node.path(i).takeIf { it.isNumber }?.asDouble()?.let { Math.round(it).toInt() }
        }
    }
}
