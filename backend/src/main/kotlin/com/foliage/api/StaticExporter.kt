package com.foliage.api

import com.fasterxml.jackson.databind.ObjectMapper
import com.foliage.api.PackedFormat.putMagic
import com.foliage.forecast.ForecastService
import com.foliage.grid.H3Grid
import com.foliage.ingest.weather.Season
import com.foliage.persistence.CellRepository
import com.foliage.persistence.ForecastRepository
import com.foliage.persistence.PlaceRepository
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
    private val places: PlaceRepository,
    private val grid: H3Grid,
    private val season: Season,
    @Value("\${foliage.model-version}") private val modelVersion: String,
    @Value("\${foliage.grid.min-canopy-pct}") private val minCanopyPct: Int,
    @Value("\${foliage.grid.metro-population}") private val metroPopulation: Int,
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

    /**
     * Resolution the map falls back to when zoomed out.
     *
     * A res 6 cell is about 3 km across. Fitting the country on a phone puts
     * that under a pixel, so the national view -- the first thing anyone sees
     * -- rendered as a faint speckle rather than a map. A res 4 cell is around
     * 22 km, which is a few pixels at that zoom and reads as a continuous
     * field of colour.
     *
     * Two levels rather than one, because res 4 to res 6 is a 49x jump in area
     * and leaves a band around zoom 6 where the coarse cells look blocky and
     * the detailed ones are still under two pixels. Res 5 is ~8 km and fills
     * it. Together they cost about 16% of the detailed payload.
     */
    private val aggregateResolutions = listOf(4, 5)

    /** Shard key: the res 3 ancestor, up to ~343 res 6 cells per shard. */
    private fun shardOf(h3: Long): String = grid.toAddress(grid.parent(h3, 3))

    /**
     * Whether a place on unforested ground is worth pointing at nearby woods.
     *
     * Rescuing every one of them added 110,000 entries and 57% to the search
     * index, mostly hamlets in farmland whose nearest trees are ten kilometres
     * off. The point of the rescue is cities people actually search for, and
     * destinations that happen to sit on open ground.
     *
     * So: somewhere with enough people to be looked up, or somewhere that is a
     * destination in its own right. A named park or mountain is worth finding
     * however few people live in it; a hamlet of forty in the middle of Kansas
     * is not.
     */
    private fun worthRescuing(place: com.foliage.domain.Place): Boolean =
        place.population >= 1_000 || place.kind != com.foliage.ingest.places.PlaceKind.TOWN

    /**
     * How thin a day may be, relative to the fullest day, and still be worth
     * publishing.
     *
     * Measured against the best day rather than against the grid, which is the
     * correction that matters. A fixed fraction of the grid cannot tell "most
     * of the country reaches this day" from "a quarter of it does": at a
     * quarter, December passed the test while three quarters of the map was
     * still grey there -- which is the tail this was supposed to remove.
     *
     * Relative to the fullest day it adapts on its own. While the refill is
     * part done the season stops where the bulk of the data stops, and as
     * states land it extends by itself, with no threshold to retune.
     */
    private val MIN_DAY_COVERAGE = 0.9

    /**
     * The season, truncated to the days that actually have a forecast.
     *
     * Extending the season to 15 December was correct for the model and wrong
     * to publish immediately: every state's normals had to be refetched for
     * the added month, and until that finished the map had a calendar it could
     * not fill. Playing the season through ran off the end of the data and the
     * country turned grey -- which reads as the forecast collapsing in
     * December rather than as a load in progress.
     *
     * Publishing only as far as the data reaches means the slider always ends
     * on a real day, and grows by itself as the backfill lands. Nothing is
     * hidden that exists, and nothing is offered that does not.
     */
    private fun publishableDays(all: List<LocalDate>, gridSize: Int): List<LocalDate> {
        val counts = all.associateWith { forecasts.countByDay(it) }
        val fullest = counts.values.maxOrNull() ?: 0
        // Never return nothing: a completely empty forecast table is a
        // different failure, and the caller already refuses that with a clear
        // message rather than writing an empty season.
        if (fullest == 0) return all

        val threshold = (fullest * MIN_DAY_COVERAGE).toInt()
        val last = all.lastOrNull { counts.getValue(it) >= threshold } ?: return all
        val kept = all.filter { !it.isAfter(last) }
        log.info(
            "publishing {} of {} days: the season is full to {} ({} of {} cells), and thinner after",
            kept.size, all.size, last, counts.getValue(last), gridSize,
        )
        return kept
    }

    /** [stateFips] null exports the whole loaded grid. */
    fun export(target: Path, stateFips: String?, year: Int = LocalDate.now().year): ExportResult {
        // Forest only, the same floor the forecast scores at, for the same
        // reason plus a second one: every exported cell costs three bytes per
        // day in each daily file, so shipping the unforested two thirds would
        // roughly double the payload to draw hexagons that can never carry a
        // colour.
        // Every cell is drawn, evergreens included. They are forest, they
        // belong on a forest map, and an evergreen hexagon drawn green in
        // November is telling the truth. What they are kept out of is the
        // *average* below.
        val grid6 = (if (stateFips == null) cells.findAll(minCanopyPct, metroPopulation)
                     else cells.findByState(stateFips, minCanopyPct, metroPopulation))
            .sortedBy { it.h3 }
        require(grid6.isNotEmpty()) { "no cells for ${stateFips ?: "the grid"}" }

        // After the grid, because how far the data reaches is measured against
        // how big the grid is.
        val days = publishableDays(season.days(year), grid6.size)

        Files.createDirectories(target.resolve("forecast"))
        for (res in aggregateResolutions) Files.createDirectories(target.resolve("forecast-r$res"))
        Files.createDirectories(target.resolve("timeline"))
        Files.createDirectories(target.resolve("factors"))

        // Position in this list is the cell's identity everywhere else.
        val order = grid6.map { it.h3 }

        // Which cells belong in a foliage average. Built once here rather than
        // looked up per day per resolution.
        val showsColour: Map<Long, Boolean> =
            grid6.associate { it.h3 to com.foliage.forecast.ForestTypeGroup.showsColour(it.forestTypeGroup) }
        val indexOf = order.withIndex().associate { (i, h3) -> h3 to i }

        val peakDays = forecasts.peakDayByCell()
        val factorsByCell = forecastService.peakFactors(stateFips, peakDays, year)

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

        // --- the rest of the grid, as bare hexagons -----------------------
        //
        // Everything tiled that is not forest: farmland, towns, water. It has
        // no forecast and never will, so it is written as a plain list of
        // indexes and nothing else -- no elevation, no canopy, and crucially
        // no entry in any daily file.
        //
        // That distinction is what makes this affordable. The reason these
        // cells were left out originally was that every exported cell costs
        // three bytes per day in each of 76 daily files, so carrying the
        // unforested two thirds through the daily pipeline would roughly
        // double the payload. Carrying them once, as identity alone, costs a
        // single file and leaves the daily files exactly as they were.
        //
        // They are drawn flat and unlabelled. Without them the map has a hole
        // wherever there is no forest -- a quarter of Ohio, a fifth of
        // Maryland -- which reads as broken data rather than as farmland.
        val scoreable = HashSet(order)
        val bare = (if (stateFips == null) cells.findAll(0, metroPopulation)
                    else cells.findByState(stateFips, 0, metroPopulation))
            .filter { it.h3 !in scoreable }
            .sortedBy { it.h3 }
        writeJson(
            target.resolve("cells-bare.json"),
            mapOf("count" to bare.size, "h3" to bare.map { grid.toAddress(it.h3) }),
        )

        // The coarse level's own index. Same contract as cells.json: position
        // is identity in every packed file at this resolution.
        val coarseLevels = aggregateResolutions.map { res ->
            val children = order.groupBy { grid.parent(it, res) }
            val cellOrder = children.keys.sorted()
            writeJson(
                target.resolve("cells-r$res.json"),
                mapOf(
                    "count" to cellOrder.size,
                    "resolution" to res,
                    "h3" to cellOrder.map { grid.toAddress(it) },
                ),
            )
            Triple(res, children, cellOrder)
        }

        // --- one packed file per day --------------------------------------
        for (day in days) {
            val byCell = forecasts.byDay(day).associateBy { it.h3 }
            val n = order.size
            val buf = PackedFormat.buffer(PackedFormat.HEADER_BYTES + PackedFormat.CHANNELS * n)
            buf.putMagic(PackedFormat.MAGIC_DAY).putInt(n)

            // Three separate runs rather than interleaved triples: each
            // channel then compresses against itself, and neighbouring cells
            // hold similar values, so gzip does considerably better.
            // An evergreen hexagon is written as "no reading" rather than as a
            // score, which the client draws faded: present on the map, plainly
            // not part of the autumn.
            //
            // Scoring them NO_CHANGE was worse than it sounds. NO_CHANGE is the
            // green of a forest that has not turned *yet*, so a December map
            // grew pockets of green implying colour still to come from stands
            // that were never going to produce any.
            fun reading(h3: Long, pick: (com.foliage.persistence.StoredForecast) -> Double?): Double? =
                if (showsColour[h3] == false) null else byCell[h3]?.let(pick)

            for (h3 in order) buf.put(PackedFormat.quantise(reading(h3) { it.progression }).toByte())
            for (h3 in order) buf.put(PackedFormat.quantise(reading(h3) { it.intensity }).toByte())
            for (h3 in order) buf.put(PackedFormat.quantiseUnit(reading(h3) { it.confidence }).toByte())

            write(target.resolve("forecast/$day.bin"), buf.array())

            // Same day at each coarser level. Averaged over the children that
            // actually have a score, so a parent straddling the edge of the
            // loaded area reports what is known rather than being dragged
            // toward zero by cells that have no forecast yet.
            for ((res, children, cellOrder) in coarseLevels) {
                val coarse = PackedFormat.buffer(
                    PackedFormat.HEADER_BYTES + PackedFormat.CHANNELS * cellOrder.size,
                )
                coarse.putMagic(PackedFormat.MAGIC_DAY).putInt(cellOrder.size)
                // Averaged over the children that can change colour, not all
                // of them. A parent holding one maple and three spruces is
                // showing the maple's autumn; including the spruces at zero
                // would report it as permanently a quarter turned.
                //
                // A parent with no colouring children at all reports nothing
                // and is drawn faded. Averaging its evergreens to zero instead
                // would paint it the green of a forest yet to turn, which is
                // how a December map grew pockets of green over country that
                // was never going to change colour.
                fun meanOver(pick: (com.foliage.persistence.StoredForecast) -> Double): List<Double?> =
                    cellOrder.map { parent ->
                        val colouring = children[parent].orEmpty().filter { showsColour[it] != false }
                        val values = colouring.mapNotNull { byCell[it]?.let(pick) }
                        if (values.isEmpty()) null else values.average()
                    }
                for (v in meanOver { it.progression }) coarse.put(PackedFormat.quantise(v).toByte())
                for (v in meanOver { it.intensity }) coarse.put(PackedFormat.quantise(v).toByte())
                for (v in meanOver { it.confidence }) coarse.put(PackedFormat.quantiseUnit(v).toByte())

                write(target.resolve("forecast-r$res/$day.bin"), coarse.array())
            }
        }

        // --- timelines and factors, sharded by res 3 ancestor -------------
        val shards = grid6.groupBy { shardOf(it.h3) }

        // Gathered while writing rather than read separately. The meta block
        // needs to know which cells carry a forecast, and that used to come
        // from the whole-table read; counting them as each shard passes
        // through costs nothing and avoids asking the database twice.
        val cellsWithForecast = HashSet<Long>()
        for ((shard, members) in shards) {
            // Read this shard's timelines rather than the whole table. See
            // ForecastRepository.timelinesFor: the single-statement read was
            // refused outright once the table passed 15M rows.
            val timelines = forecasts.timelinesFor(members.map { it.h3 })
            cellsWithForecast += timelines.keys
            val n = members.size
            val d = days.size
            val buf = PackedFormat.buffer(12 + 4 * n + PackedFormat.CHANNELS * n * d)
            buf.putMagic(PackedFormat.MAGIC_TIMELINE).putInt(n).putInt(d)

            // Global indices, so the client can map a shard back onto the index.
            members.forEach { buf.putInt(indexOf.getValue(it.h3)) }

            for (cell in members) {
                // An evergreen writes no series at all, for the same reason it
                // writes no daily reading: a flat zero curve in the detail
                // panel is a claim that the stand is yet to turn.
                val colours = com.foliage.forecast.ForestTypeGroup.showsColour(cell.forestTypeGroup)
                val series = timelines[cell.h3].orEmpty().associateBy { it.day }
                for (day in days) {
                    val row = if (colours) series[day] else null
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

        // --- searchable places --------------------------------------------
        //
        // Parallel arrays for the same reason the daily files use them:
        // repeating six keys per entry roughly triples the file.
        //
        // Places on ground the forest mask excludes are kept and pointed at
        // the nearest forested cell instead of being dropped. Boston's own
        // hexagon is 2% canopy, so it was not in the index at all and
        // searching for it returned nineteen small Bostons in other states.
        // "The nearest woods to Boston" is a useful answer; nothing is not.
        val gridPlaces = places.findInGrid()
        val resolved = gridPlaces.mapNotNull { place ->
            val own = indexOf[place.h3]
            if (own != null) {
                place to (own to false)
            } else if (worthRescuing(place)) {
                // Outward ring by ring, stopping at the first forested cell.
                // Four steps is roughly 12 km; past that "nearest woods" stops
                // being a useful answer and the place is better left out.
                val near = (1..4).firstNotNullOfOrNull { k ->
                    grid.disk(place.h3, k).firstNotNullOfOrNull { indexOf[it] }
                }
                near?.let { place to (it to true) }
            } else {
                null
            }
        }

        writeJson(
            target.resolve("places.json"),
            mapOf(
                "count" to resolved.size,
                "name" to resolved.map { it.first.name },
                "state" to resolved.map { it.first.stateCode },
                "kind" to resolved.map { it.first.kind.name },
                "population" to resolved.map { it.first.population },
                // Index into cells.json rather than the h3 string: the client
                // needs the cell's position anyway to read a packed day.
                "cell" to resolved.map { it.second.first },
                // True when the cell is nearby rather than the place's own,
                // so the UI can say so instead of implying a forecast for a
                // city centre that has no trees in it.
                "nearby" to resolved.map { it.second.second },
                "lat" to resolved.map { Math.round(it.first.latitude * 1e4) / 1e4 },
                "lon" to resolved.map { Math.round(it.first.longitude * 1e4) / 1e4 },
            ),
        )

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
                // Tiled but not forest, drawn flat so the grid has no holes.
                "bareCellCount" to bare.size,
                // How far the nightly backfill has got. The load takes about a
                // month of daily runs against a metered API, and without this
                // the only way to tell whether a night's run did anything was
                // to read CI logs. The site should be able to say.
                // Cells that have a forecast at all, not cells that reach
                // peak. A cell scored somewhere too warm to turn never peaks,
                // and counting only peaks understated how much of the map is
                // actually done.
                "cellsForecast" to cellsWithForecast.count { indexOf.containsKey(it) },
                "statesForecast" to grid6
                    .filter { cellsWithForecast.contains(it.h3) }
                    .mapNotNull { it.stateName }
                    .distinct().size,
                "placeCount" to resolved.size,
                "shardCount" to shards.size,
                "seasonStart" to days.first().toString(),
                // The last day with data, not the last day in the calendar.
                // See publishableDays.
                "seasonEnd" to days.last().toString(),
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
