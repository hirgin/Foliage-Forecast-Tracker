package com.foliage.ingest.terrain

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.foliage.grid.LonLat
import com.foliage.grid.SimplePolygon

/** A state's outline, as one or more polygons. */
data class StateBoundary(
    val fips: String,
    val name: String,
    val polygons: List<SimplePolygon>,
)

/**
 * Where state outlines come from. Census TIGERweb today; the seam exists so a
 * cached local artifact can replace it without touching the bootstrap.
 */
interface BoundarySource {
    fun stateBoundary(name: String): StateBoundary
}

/**
 * Parsing for a TIGERweb GeoJSON query response.
 *
 * States are frequently `MultiPolygon` — islands, exclaves, and lake
 * boundaries all split the outline — so both geometry types must be handled.
 * Within each polygon GeoJSON puts the outer ring first and any holes after,
 * which maps directly onto what H3 needs for tiling.
 */
object TigerWebBoundaryParser {

    private val mapper = ObjectMapper()

    fun parse(json: String): StateBoundary {
        val root = mapper.readTree(json)
        val feature = root.path("features").firstOrNull()
            ?: error("TIGERweb returned no features")

        val props = feature.path("properties")
        val name = props.path("NAME").asText(null) ?: error("feature has no NAME")
        val fips = props.path("STATE").asText(null) ?: error("feature has no STATE fips")

        val geom = feature.path("geometry")
        val polygons = when (val type = geom.path("type").asText()) {
            "Polygon" -> listOf(toPolygon(geom.path("coordinates")))
            "MultiPolygon" -> geom.path("coordinates").map { toPolygon(it) }
            else -> error("unsupported geometry type: $type")
        }

        return StateBoundary(fips = fips, name = name, polygons = polygons)
    }

    /** GeoJSON polygon: `[outerRing, hole1, hole2, ...]`, each ring `[[lon, lat], ...]`. */
    private fun toPolygon(rings: JsonNode): SimplePolygon = SimplePolygon(
        outer = toRing(rings.path(0)),
        holes = (1 until rings.size()).map { toRing(rings.path(it)) },
    )

    private fun toRing(ring: JsonNode): List<LonLat> =
        ring.map { LonLat(lon = it.path(0).asDouble(), lat = it.path(1).asDouble()) }
}
