package com.foliage.ingest.terrain

import com.fasterxml.jackson.databind.ObjectMapper
import com.foliage.grid.LonLat
import com.foliage.ingest.RetryPolicy
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.HttpServerErrorException
import org.springframework.web.client.RestClient

/**
 * Elevation from USGS 3DEP.
 *
 * Chosen for one reason only: it is not rate-limited. Open-Meteo's elevation
 * endpoint is metered by request weight and began returning 429 after seven
 * batches, which was the documented blocker on leaving Vermont.
 *
 * **Its 1 m resolution buys nothing here, despite appearances.** Measured
 * across all 649 Vermont cells, 3DEP and Open-Meteo's ~90 m DEM agree to
 * within a few metres:
 *
 *     3DEP        min 30   median 371   max 967
 *     Open-Meteo  min 27   median 380   max 981
 *
 * That is expected on reflection: one centroid sample per 36 km2 hexagon
 * cannot exploit metre-scale detail. The finer source is also roughly 60x
 * slower per point (0.36 s against ~0.003 s), so this is a straight trade of
 * throughput for freedom from throttling, not an accuracy win.
 *
 * At CONUS scale neither is really right — see docs/data-sources.md on the
 * bulk DEM, which would be both faster and entirely sufficient.
 */
@Component
@Primary
class Usgs3depElevationSource(
    private val restClient: RestClient,
    @Value("\${foliage.terrain.elevation-3dep-url}") private val url: String,
    @Value("\${foliage.terrain.elevation-batch-size}") private val batchSize: Int,
) : ElevationSource {

    private val log = LoggerFactory.getLogger(javaClass)
    private val retry = RetryPolicy(maxAttempts = 3, initialBackoffMs = 4_000)

    // 504 Gateway Timeout is the characteristic failure here: CloudFront gives
    // up before the service finishes a large spread-out batch. Treat any 5xx
    // as transient and retry with a smaller effective load.
    private val isTransient: (Throwable) -> Boolean = { it is HttpServerErrorException }

    override fun elevation(points: List<LonLat>): List<Int?> =
        points.chunked(batchSize).flatMapIndexed { i, batch ->
            runCatching {
                retry.execute("3dep batch $i", isTransient) { sampleBatch(batch) }
            }.getOrElse {
                // Degrade: those cells stay unsampled and a later run fills
                // them, rather than losing an entire CONUS bootstrap.
                log.warn("3DEP batch {} failed: {}", i, it.message)
                List(batch.size) { null }
            }
        }

    private fun sampleBatch(batch: List<LonLat>): List<Int?> {
        val geometry = batch.joinToString(
            ",",
            prefix = """{"points":[""",
            postfix = """],"spatialReference":{"wkid":4326}}""",
        ) { "[${it.lon},${it.lat}]" }

        val form = LinkedMultiValueMap<String, String>().apply {
            add("geometry", geometry)
            add("geometryType", "esriGeometryMultipoint")
            add("returnFirstValueOnly", "true")
            add("f", "json")
        }

        val body = restClient.post()
            .uri("$url/getSamples")
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(form)
            .retrieve()
            .body(String::class.java) ?: return List(batch.size) { null }

        return Usgs3depParser.parse(body, batch.size)
    }
}

/**
 * Parsing for 3DEP's `getSamples` response.
 *
 * Same shape as the canopy service, and the same trap: results are placed by
 * `locationId` because points off the raster are omitted entirely rather than
 * returned as nulls. Relying on array position would attach the wrong
 * elevation to the wrong cell.
 */
object Usgs3depParser {

    private val mapper = ObjectMapper()

    /** Below this, a returned value is a NoData sentinel rather than terrain. */
    private const val MIN_PLAUSIBLE_M = -500.0

    /** Denali is 6,190 m; anything above this is not CONUS terrain. */
    private const val MAX_PLAUSIBLE_M = 6_500.0

    fun parse(json: String, pointCount: Int): List<Int?> {
        val out = arrayOfNulls<Int>(pointCount)
        val samples = mapper.readTree(json).path("samples")

        for (s in samples) {
            val id = s.path("locationId").asInt(-1)
            if (id !in 0 until pointCount) continue
            // Values arrive as strings, and off-raster points report large
            // negative sentinels rather than being omitted in some responses.
            val v = s.path("value").asText(null)?.toDoubleOrNull() ?: continue
            if (v < MIN_PLAUSIBLE_M || v > MAX_PLAUSIBLE_M) continue
            out[id] = Math.round(v).toInt()
        }
        return out.toList()
    }
}
