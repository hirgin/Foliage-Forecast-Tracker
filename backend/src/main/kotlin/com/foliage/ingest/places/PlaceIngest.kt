package com.foliage.ingest.places

import com.foliage.domain.Place
import com.foliage.grid.H3Grid
import com.foliage.grid.LonLat
import com.foliage.ingest.audit.IngestRunRecorder
import com.foliage.persistence.PlaceRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.net.URI
import java.util.zip.ZipInputStream
import kotlin.system.measureTimeMillis

/**
 * Loads US places from the GeoNames dump and resolves each to its hexagon.
 *
 * Streams the archive rather than downloading it: the file is 68 MB
 * compressed and 2.24 million rows, of which only a few hundred thousand are
 * wanted, so there is no reason to hold either the zip or the full text in
 * memory.
 *
 * Every wanted US place is stored, not only those inside the current grid.
 * Resolving a place to a hexagon needs nothing but its coordinates, so when
 * the grid expands to a new state its towns light up immediately — without
 * reprocessing two million rows.
 */
@Service
class PlaceIngest(
    private val grid: H3Grid,
    private val places: PlaceRepository,
    private val audit: IngestRunRecorder,
    @Value("\${foliage.places.url}") private val url: String,
    @Value("\${foliage.grid.resolution}") private val resolution: Int,
    @Value("\${foliage.places.batch-size}") private val batchSize: Int,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun ingest(): PlaceIngestResult {
        val runId = audit.start("geonames", "places")
        var written = 0L
        var scanned = 0L
        var kept = 0L

        try {
            val elapsed = measureTimeMillis {
                val batch = ArrayList<Place>(batchSize)

                ZipInputStream(URI(url).toURL().openStream().buffered()).use { zip ->
                    generateSequence { zip.nextEntry }
                        .filter { !it.isDirectory && it.name.endsWith(".txt") }
                        .forEach { entry ->
                            log.info("reading {}", entry.name)
                            // Not closing the reader: it would close the whole
                            // zip stream and stop the entry loop.
                            zip.bufferedReader().lineSequence().forEach { line ->
                                scanned++
                                val row = GeoNamesParser.parseLine(line) ?: return@forEach
                                if (!GeoNamesParser.isWanted(row)) return@forEach
                                val kind = PlaceKind.fromFeatureCode(row.featureCode) ?: return@forEach

                                kept++
                                batch += Place(
                                    geonameId = row.geonameId,
                                    name = row.name,
                                    stateCode = row.admin1?.take(2),
                                    latitude = row.latitude,
                                    longitude = row.longitude,
                                    population = row.population,
                                    kind = kind,
                                    h3 = grid.cellAt(LonLat(row.longitude, row.latitude), resolution),
                                )

                                if (batch.size >= batchSize) {
                                    written += places.upsertAll(batch)
                                    batch.clear()
                                }
                            }
                        }
                }
                if (batch.isNotEmpty()) written += places.upsertAll(batch)
            }

            audit.succeed(runId, written)
            log.info("scanned {} rows, kept {}, wrote {} in {} ms", scanned, kept, written, elapsed)

            return PlaceIngestResult(
                rowsScanned = scanned,
                placesKept = kept,
                rowsWritten = written,
                totalStored = places.count(),
                inGrid = places.countInGrid(),
                inGridByKind = places.countByKindInGrid(),
                elapsedMs = elapsed,
            )
        } catch (e: Exception) {
            audit.fail(runId, written, e)
            throw e
        }
    }
}

data class PlaceIngestResult(
    val rowsScanned: Long,
    val placesKept: Long,
    val rowsWritten: Long,
    val totalStored: Long,
    val inGrid: Long,
    val inGridByKind: Map<String, Long>,
    val elapsedMs: Long,
)
