package com.foliage.ingest.terrain

import com.foliage.grid.LonLat
import com.foliage.ingest.RetryPolicy
import org.slf4j.LoggerFactory
import org.springframework.web.client.HttpClientErrorException
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestClient
import org.springframework.web.util.UriComponentsBuilder

/**
 * Canopy cover from the USFS NLCD Tree Canopy Cover ImageServer.
 *
 * Points are POSTed in batches: the geometry payload for a few hundred points
 * exceeds what a URL can carry, and GET would silently truncate.
 *
 * Superseded at CONUS scale by [NlcdTileCanopySource], which reads the same
 * raster as tiles and is the @Primary source. Retained as a fallback, and as
 * the reference this implementation was validated against.
 */
@Component
class NlcdCanopySource(
    private val restClient: RestClient,
    @Value("\${foliage.terrain.canopy-url}") private val url: String,
    @Value("\${foliage.terrain.canopy-batch-size}") private val batchSize: Int,
) : CanopySource {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun sample(points: List<LonLat>): List<Int?> =
        points.chunked(batchSize).flatMapIndexed { i, batch ->
            log.debug("canopy batch {} ({} points)", i, batch.size)
            runCatching { sampleBatch(batch) }
                .getOrElse {
                    // Degrade, do not abort: one failed batch leaves those
                    // cells unsampled and resumable, rather than killing a
                    // bootstrap that is most of the way through.
                    log.warn("canopy batch {} failed: {}", i, it.message)
                    List(batch.size) { null }
                }
        }

    private fun sampleBatch(batch: List<LonLat>): List<Int?> {
        val geometry = batch.joinToString(",", prefix = """{"points":[""", postfix = """],"spatialReference":{"wkid":4326}}""") {
            "[${it.lon},${it.lat}]"
        }
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

        return NlcdSampleParser.parse(body, batch.size)
    }
}

/**
 * Ground elevation from Open-Meteo. Free, keyless, and accepts batched
 * coordinates -- but metered by request *weight*, so large batches fired
 * back-to-back trip a minutely limit. Retries on 429 with backoff.
 */
@Component
class OpenMeteoElevationSource(
    private val restClient: RestClient,
    @Value("\${foliage.terrain.elevation-url}") private val url: String,
    // Open-Meteo caps this endpoint at 100 coordinates regardless of the
    // shared setting, which 3DEP raises for its own batches.
    private val batchSize: Int = 100,
) : ElevationSource {

    private val log = LoggerFactory.getLogger(javaClass)
    private val retry = RetryPolicy(maxAttempts = 4, initialBackoffMs = 20_000)

    private val isRateLimit: (Throwable) -> Boolean = { it is HttpClientErrorException.TooManyRequests }

    override fun elevation(points: List<LonLat>): List<Int?> =
        points.chunked(batchSize).flatMapIndexed { i, batch ->
            runCatching {
                retry.execute("elevation batch $i", isRateLimit) { elevationBatch(batch) }
            }.getOrElse {
                log.warn("elevation batch {} failed after retries: {}", i, it.message)
                List(batch.size) { null }
            }
        }

    private fun elevationBatch(batch: List<LonLat>): List<Int?> {
        val uri = UriComponentsBuilder.fromUriString(url)
            .queryParam("latitude", batch.joinToString(",") { it.lat.toString() })
            .queryParam("longitude", batch.joinToString(",") { it.lon.toString() })
            .build(true)
            .toUri()

        val body = restClient.get().uri(uri).retrieve().body(String::class.java)
            ?: return List(batch.size) { null }

        return OpenMeteoElevationParser.parse(body, batch.size)
    }
}

/** State outlines from Census TIGERweb. */
@Component
class TigerWebBoundarySource(
    private val restClient: RestClient,
    @Value("\${foliage.terrain.boundary-url}") private val url: String,
) : BoundarySource {

    override fun stateBoundary(name: String): StateBoundary {
        val uri = UriComponentsBuilder.fromUriString(url)
            .queryParam("where", "NAME='$name'")
            .queryParam("outFields", "NAME,STATE")
            .queryParam("returnGeometry", "true")
            .queryParam("outSR", "4326")
            .queryParam("f", "geojson")
            .build()
            .toUri()

        val body = restClient.get().uri(uri).retrieve().body(String::class.java)
            ?: error("TIGERweb returned an empty body for '$name'")

        return TigerWebBoundaryParser.parse(body)
    }
}
