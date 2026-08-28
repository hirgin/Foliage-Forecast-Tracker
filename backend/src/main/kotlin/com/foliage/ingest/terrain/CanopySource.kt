package com.foliage.ingest.terrain

import com.fasterxml.jackson.databind.ObjectMapper
import com.foliage.grid.LonLat

/**
 * Percent tree canopy cover at a set of points.
 *
 * This is the seam that decides which hexagons count as forest. It exists as
 * an interface for the same reason `WeatherSource` does: the current
 * implementation samples a hosted raster service point-by-point, which is
 * ideal at state scale but will not scale to a full CONUS grid. Swapping in a
 * tile-based implementation later must not touch anything downstream.
 */
interface CanopySource {

    /**
     * Canopy percentage (0–100) for each point, **in input order**.
     * `null` where the service returned no value — off-raster, or ocean.
     */
    fun sample(points: List<LonLat>): List<Int?>
}

/**
 * Parsing for the USFS NLCD Tree Canopy Cover ImageServer `getSamples`
 * response. Separated from transport so it can be tested against a captured
 * fixture; see docs/testing.md.
 */
object NlcdSampleParser {

    private val mapper = ObjectMapper()

    /**
     * The service returns a `samples` array that is **not guaranteed to be
     * complete or ordered** — points with no raster coverage are simply
     * omitted. Results are therefore placed by `locationId`, not by position,
     * and missing entries stay null.
     */
    fun parse(json: String, pointCount: Int): List<Int?> {
        val out = arrayOfNulls<Int>(pointCount)
        val samples = mapper.readTree(json).path("samples")
        for (s in samples) {
            val id = s.path("locationId").asInt(-1)
            if (id !in 0 until pointCount) continue
            // `value` is a string, and is "NoData" outside the raster.
            out[id] = s.path("value").asText(null)?.toIntOrNull()?.coerceIn(0, 100)
        }
        return out.toList()
    }
}
