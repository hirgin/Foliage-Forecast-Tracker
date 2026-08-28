package com.foliage.api

import com.fasterxml.jackson.databind.ObjectMapper
import com.foliage.api.PackedFormat.putMagic
import com.foliage.forecast.ForecastService
import com.foliage.grid.H3Grid
import com.foliage.ingest.weather.Season
import com.foliage.persistence.CellRepository
import com.foliage.persistence.ForecastRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate

/**
 * Writes the season out as a static site payload.
 *
 * The read path is entirely precomputed, so publishing it as files on a CDN
 * removes the need for a running server -- and with it the 30-60 s cold start
 * every free JVM host imposes on a Spring Boot app.
 *
 * Layout, chosen so this survives the jump from one state to CONUS:
 *
 *     meta.json              season bounds, model version, counts
 *     cells.json             the cell index -- written ONCE, defines the order
 *     forecast/<date>.bin    three parallel byte arrays in that order
 *     timeline/<res3>.bin    whole-season series, sharded by res 3 ancestor
 *     factors/<res3>.json    peak-day explanations, sharded the same way
 *
 * Two decisions carry the scaling. Cell identifiers live in the index rather
 * than being repeated in every daily file, which takes a cell-day from ~95
 * bytes of JSON to 3. And timelines are sharded by H3 res 3 ancestor rather
 * than written per cell, because 76,041 individual files is not something to
 * put on a CDN -- a click fetches one shard of a few hundred cells instead.
 */
@Service
class StaticExporter(
    private val cells: CellRepository,
    private val forecasts: ForecastRepository,
    private val forecastService: ForecastService,
    private val grid: H3Grid,
    private val season: Season,
    @Value("\${foliage.model-version}") private val modelVersion: String,
) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val mapper = ObjectMapper()

    /**
     * "Vermont", "Vermont and Maine", "6 states", "the contiguous United States".
     * Naming every state once the grid is national would be unreadable.
     */
    private fun coverageLabel(states: List<String>): String = when {
        states.isEmpty() -> "United States"
        states.size == 1 -> states.single()
        states.size == 2 -> "${states[0]} and ${states[1]}"
        states.size >= 45 -> "the contiguous United States"
        else -> "${states.size} states"
    }

    /** Shard key: the res 3 ancestor, up to ~343 res 6 cells per shard. */
    private fun shardOf(h3: Long): String = grid.toAddress(grid.parent(h3, 3))

    /** [stateFips] null exports the whole loaded grid. */
    fun export(target: Path, stateFips: String?, year: Int = LocalDate.now().year): ExportResult {
        val days = season.days(year)
        val grid6 = (if (stateFips == null) cells.findAll(0) else cells.findByState(stateFips, 0))
            .sortedBy { it.h3 }
        require(grid6.isNotEmpty()) { "no cells for ${stateFips ?: "the grid"}" }

        Files.createDirectories(target.resolve("forecast"))
        Files.createDirectories(target.resolve("timeline"))
        Files.createDirectories(target.resolve("factors"))

        // Position in this list is the cell's identity everywhere else.
        val order = grid6.map { it.h3 }
        val indexOf = order.withIndex().associate { (i, h3) -> h3 to i }

        val peakDays = forecasts.peakDayByCell()
        val factorsByCell = forecastService.peakFactors(stateFips, peakDays, year)
        val timelines = forecasts.allTimelines()

        var files = 0
        var bytes = 0L
        fun write(path: Path, payload: ByteArray) {
            Files.write(path, payload)
            files++
            bytes += payload.size
        }
        fun writeJson(path: Path, value: Any) = write(path, mapper.writeValueAsBytes(value))

        // --- the index, written once -------------------------------------
        writeJson(
            target.resolve("cells.json"),
            mapOf(
                "count" to grid6.size,
                "h3" to grid6.map { grid.toAddress(it.h3) },
                "elevationM" to grid6.map { it.elevationM },
                "canopyPct" to grid6.map { it.canopyPct },
            ),
        )

        // --- one packed file per day --------------------------------------
        for (day in days) {
            val byCell = forecasts.byDay(day).associateBy { it.h3 }
            val n = order.size
            val buf = PackedFormat.buffer(PackedFormat.HEADER_BYTES + PackedFormat.CHANNELS * n)
            buf.putMagic(PackedFormat.MAGIC_DAY).putInt(n)

            // Three separate runs rather than interleaved triples: each
            // channel then compresses against itself, and neighbouring cells
            // hold similar values, so gzip does considerably better.
            for (h3 in order) buf.put(PackedFormat.quantise(byCell[h3]?.progression).toByte())
            for (h3 in order) buf.put(PackedFormat.quantise(byCell[h3]?.intensity).toByte())
            for (h3 in order) buf.put(PackedFormat.quantiseUnit(byCell[h3]?.confidence).toByte())

            write(target.resolve("forecast/$day.bin"), buf.array())
        }

        // --- timelines and factors, sharded by res 3 ancestor -------------
        val shards = grid6.groupBy { shardOf(it.h3) }
        for ((shard, members) in shards) {
            val n = members.size
            val d = days.size
            val buf = PackedFormat.buffer(12 + 4 * n + PackedFormat.CHANNELS * n * d)
            buf.putMagic(PackedFormat.MAGIC_TIMELINE).putInt(n).putInt(d)

            // Global indices, so the client can map a shard back onto the index.
            members.forEach { buf.putInt(indexOf.getValue(it.h3)) }

            for (cell in members) {
                val series = timelines[cell.h3].orEmpty().associateBy { it.day }
                for (day in days) {
                    val row = series[day]
                    buf.put(PackedFormat.quantise(row?.progression).toByte())
                    buf.put(PackedFormat.quantise(row?.intensity).toByte())
                    buf.put(PackedFormat.quantiseUnit(row?.confidence).toByte())
                }
            }
            write(target.resolve("timeline/$shard.bin"), buf.array())

            // Explanations stay JSON: they are prose, and only fetched on a click.
            writeJson(
                target.resolve("factors/$shard.json"),
                members.associate { cell ->
                    grid.toAddress(cell.h3) to mapOf(
                        "peakDay" to peakDays[cell.h3]?.toString(),
                        "factors" to factorsByCell[cell.h3]?.map {
                            mapOf(
                                "name" to it.name,
                                "value" to it.value,
                                "effect" to it.effect,
                                "detail" to it.detail,
                            )
                        }.orEmpty(),
                    )
                },
            )
        }

        writeJson(
            target.resolve("meta.json"),
            mapOf(
                "service" to "foliage-forecast",
                "modelVersion" to modelVersion,
                "format" to "packed-v1",
                "gridResolution" to 6,
                "shardResolution" to 3,
                "stateFips" to (stateFips ?: "all"),
                // Human-readable coverage for the UI header, derived from the
                // states actually loaded rather than hardcoded.
                "coverage" to coverageLabel(grid6.mapNotNull { it.stateName }.distinct().sorted()),
                "stateCount" to grid6.mapNotNull { it.stateName }.distinct().size,
                "cellCount" to grid6.size,
                "shardCount" to shards.size,
                "seasonStart" to season.start(year).toString(),
                "seasonEnd" to season.end(year).toString(),
                "generatedAt" to java.time.Instant.now().toString(),
                "mode" to "static",
            ),
        )

        log.info(
            "exported {} files ({} KB) across {} shards to {}",
            files, bytes / 1024, shards.size, target.toAbsolutePath(),
        )
        return ExportResult(
            files = files,
            bytes = bytes,
            days = days.size,
            cells = grid6.size,
            shards = shards.size,
            target = target.toAbsolutePath().toString(),
        )
    }
}

data class ExportResult(
    val files: Int,
    val bytes: Long,
    val days: Int,
    val cells: Int,
    val shards: Int,
    val target: String,
)
