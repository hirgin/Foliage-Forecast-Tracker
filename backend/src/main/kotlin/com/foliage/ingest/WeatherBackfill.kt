package com.foliage.ingest

import com.foliage.forecast.ForecastService
import com.foliage.grid.ConusStates
import com.foliage.ingest.weather.WeatherIngest
import com.foliage.persistence.CellRepository
import com.foliage.persistence.NormalRepository
import java.time.Duration
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * Fills in the country a day's allowance at a time.
 *
 * **Why this exists.** Climatology is the expensive half of the weather
 * pipeline: five years of archive for every res 5 parent. Measured against
 * Open-Meteo's free tier, a day's allowance covers roughly 650 parents, and
 * the country needs 19,904. That is about a month of daily runs — far too many
 * to do by hand, and exactly the sort of thing that gets half-done and then
 * forgotten.
 *
 * So the nightly deploy calls this instead. Each run takes the next states
 * that are not finished, works until either the allowance or its time budget
 * runs out, and stops cleanly. The map fills in over the following weeks
 * without anyone driving it.
 *
 * **Order matters.** States are taken in the foliage-first order of
 * [ConusStates.ALL], so the places people actually search for arrive first and
 * the desert southwest last.
 *
 * Resumption is by *completeness*, not by presence: a state whose climatology
 * died partway through an allowance has rows and is still unfinished, and
 * skipping it on that basis would strand it forever. This is the same lesson
 * the terrain bootstrap learned; see ADR-0007.
 */
@Service
class WeatherBackfill(
    private val cells: CellRepository,
    private val normals: NormalRepository,
    private val weatherIngest: WeatherIngest,
    private val forecastService: ForecastService,
    private val audit: com.foliage.ingest.audit.IngestRunRecorder,
    private val season: com.foliage.ingest.weather.Season,
    private val forecasts: com.foliage.persistence.ForecastRepository,
    @org.springframework.beans.factory.annotation.Value("\${foliage.grid.min-canopy-pct}")
    private val minCanopyPct: Int,
    @org.springframework.beans.factory.annotation.Value("\${foliage.grid.metro-population}")
    private val metroPopulation: Int,
) {

    /**
     * The res 5 parents a state actually needs weather for.
     *
     * Has to match what the climatology build loads, or a state would never
     * look complete: it covers only parents carrying cells that are scored,
     * where the plain parent list also counts ground that is neither forest
     * nor city -- 31,476 parents nationally against 19,904 worth having.
     */
    private fun scoreableParents(fips: String): List<Long> =
        cells.scoreableParentsByRes4(fips, minCanopyPct, metroPopulation).values.flatten()

    private val log = LoggerFactory.getLogger(javaClass)

    /** Share of a state's cells that must have peaked before December is skipped. */
    private val PEAKED_SHARE = 0.95

    /**
     * How many days a parent must hold before it counts as loaded.
     *
     * Extending the season has to make everything incomplete again, or the
     * added days are never fetched and the map stops progressing partway
     * through autumn without saying so.
     */
    private fun seasonDays(): Int = season.days(java.time.LocalDate.now().year).size

    /**
     * The season as it was before December was added, in days.
     *
     * What a state that has already turned is held to. Derived from the season
     * rather than hardcoded at 76, so shifting the start moves both together.
     */
    private fun daysThroughNovember(): Int {
        val year = java.time.LocalDate.now().year
        val cutoff = java.time.LocalDate.of(year, 11, 15)
        return season.days(year).count { !it.isAfter(cutoff) }
    }

    /**
     * States whose forest has effectively finished turning.
     *
     * The threshold is deliberately high. Getting this wrong in the generous
     * direction means a state that still has colour coming is written off and
     * frozen partway through its autumn, which is the exact failure the
     * December extension existed to fix. Getting it wrong the other way just
     * fetches a month of weather nobody needed.
     */
    private fun statesThatHavePeaked(): Set<String> =
        runCatching {
            forecasts.peakCoverageByState(minCanopyPct)
                .filter { it.cells > 0 && it.withForecast.toDouble() / it.cells >= PEAKED_SHARE }
                .map { it.state }
                .toSet()
        }.getOrDefault(emptySet())

    /**
     * Brings every already-complete state up to date.
     *
     * Run before [run], because keeping what is already on the map honest
     * matters more than adding to it: both draw on the same daily allowance,
     * and a map that silently ages is worse than one that is merely small.
     *
     * This replaced a hardcoded Vermont FIPS in the nightly deploy. That was
     * right when Vermont was the only state loaded and quietly wrong the
     * moment it was not -- every other state would have frozen on the day it
     * landed while Vermont alone stayed current, with nothing to show that had
     * happened.
     *
     * Only complete states are touched. A half-loaded one is the backfill's
     * job, and refreshing its observations would not make it scoreable.
     */
    fun refreshLoaded(
        maxStates: Int = 8,
        budget: Duration = Duration.ofMinutes(45),
    ): RefreshResult {
        val deadline = Instant.now().plus(budget)
        val covered = runCatching { normals.cellsWithNormals(seasonDays()) }.getOrDefault(emptySet())
        val lastRefreshed = runCatching { audit.lastForecastRefreshByState() }
            .getOrDefault(emptyMap())
        val refreshed = mutableListOf<String>()
        var quotaSpent = false

        // Stalest first, and capped.
        //
        // Refreshing every loaded state every night looks right and starves
        // the backfill. A forecast refresh costs roughly a tenth of what
        // climatology does per res 5 parent, but there are 19,904 parents to
        // cover: once twenty-odd states are loaded, refreshing all of them
        // would consume the whole daily allowance and the load would stall
        // there permanently, with no sign of why.
        //
        // A cap plus staleness ordering keeps the cost flat as the map grows.
        // Every state still comes round; it just takes a few nights rather
        // than one, which is well inside how fast a foliage forecast moves.
        val candidates = ConusStates.ALL
            .mapNotNull { state ->
                val fips = runCatching { cells.stateFipsFor(state) }.getOrNull() ?: return@mapNotNull null
                val parents = scoreableParents(fips)
                if (parents.isEmpty() || !covered.containsAll(parents)) null else state to fips
            }
            .sortedBy { (_, fips) -> lastRefreshed[fips] ?: Instant.EPOCH }

        for ((state, fips) in candidates) {
            if (refreshed.size >= maxStates) break
            if (Instant.now().isAfter(deadline)) break

            try {
                weatherIngest.refreshForecast(fips)
                // Rescored as well as refetched. New observations that never
                // reach the model change nothing on the map.
                forecastService.computeState(fips)
                refreshed += state
            } catch (e: QuotaExhausted) {
                log.info(
                    "{} allowance spent refreshing {}; the rest keep yesterday's data, and this resumes {}",
                    e.window, state, e.resumesIn,
                )
                quotaSpent = true
                break
            } catch (e: Exception) {
                log.error("refreshing {} failed: {}", state, e.message)
            }
        }
        log.info(
            "refreshed {} of {} loaded states, quotaSpent={}",
            refreshed.size, candidates.size, quotaSpent,
        )
        return RefreshResult(refreshed, candidates.size, quotaSpent)
    }

    /**
     * @param maxStates how many states to attempt at most
     * @param budget wall-clock ceiling; a scheduled deploy runs inside a job
     *   with its own limit, and being killed halfway is worse than stopping
     */
    fun run(maxStates: Int = 6, budget: Duration = Duration.ofMinutes(90)): BackfillResult {
        val deadline = Instant.now().plus(budget)
        val done = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        var quotaSpent = false
        var stoppedForTime = false

        // Two coverage sets, because states do not all need the same season.
        //
        // A state whose forest has already turned by mid-November cannot use
        // another month of cooling: its cells are saturated, and December
        // weather would change nothing about what the map shows. Asking for it
        // anyway spends a metered API and a metered database to arrive at the
        // same answer, and on this project both of those ran out in one day.
        //
        // So states that have peaked are considered complete at the old
        // mid-November end, and only the ones still turning are held to the
        // full season. Measured before writing this: Louisiana sat at 41%
        // progression on 15 November having never peaked, while Vermont was at
        // 100% and had been for weeks. One of those needs December.
        val coveredFull = runCatching { normals.cellsWithNormals(seasonDays()) }
            .getOrDefault(emptySet())
        val coveredThroughNovember = runCatching { normals.cellsWithNormals(daysThroughNovember()) }
            .getOrDefault(emptySet())
        val finished = statesThatHavePeaked()

        // Emptiest first, not foliage-first.
        //
        // [ConusStates.ALL] is ordered by how much anyone cares about a state's
        // autumn, which is the right order for a cold start: it puts New
        // England on the map first and the desert southwest last. It is the
        // wrong order once every state has *some* data, because it then spends
        // the allowance topping up states that already work while states with
        // actual holes in them wait behind.
        //
        // That is not hypothetical. Lowering the canopy floor left 14,575
        // hexagons with no forecast at all -- 79% of North Dakota, 51% of
        // South Dakota, 44% of Nevada -- and every one of those states sits at
        // the back of the foliage-first queue precisely because nobody visits
        // them for the leaves. Ordering by how much of a state is missing
        // sends the allowance where the map is emptiest.
        //
        // Ties keep the foliage-first order, so a cold start still behaves as
        // it did: with nothing loaded every state is equally empty by share,
        // and Maine goes first.
        val byNeed = ConusStates.ALL
            .mapNotNull { state ->
                val fips = runCatching { cells.stateFipsFor(state) }.getOrNull()
                    ?: return@mapNotNull null
                val parents = runCatching { scoreableParents(fips) }.getOrDefault(emptyList())
                if (parents.isEmpty()) return@mapNotNull null
                val covered = if (state in finished) coveredThroughNovember else coveredFull
                val missing = parents.count { it !in covered }
                Triple(state, missing.toDouble() / parents.size, missing)
            }
            .filter { (_, _, missing) -> missing > 0 }
            .sortedByDescending { (_, share, _) -> share }
            .map { (state, _, _) -> state }

        for (state in byNeed) {
            if (done.size >= maxStates) break
            if (Instant.now().isAfter(deadline)) {
                stoppedForTime = true
                break
            }

            val fips = runCatching { cells.stateFipsFor(state) }.getOrNull()
            if (fips == null) {
                // Not tiled yet. The grid bootstrap owns that, not this.
                skipped += state
                continue
            }

            val parents = scoreableParents(fips)
            val covered = if (state in finished) coveredThroughNovember else coveredFull
            if (parents.isNotEmpty() && covered.containsAll(parents)) {
                skipped += state
                continue
            }

            log.info(
                "backfilling {} ({}): {} of {} parents already have normals",
                state, fips, parents.count { it in covered }, parents.size,
            )

            try {
                // Climatology first, because it is the scarce half and it is
                // resumable in chunks. A state larger than a day's allowance
                // takes several nights, and fetching its observations up front
                // each time would spend the allowance on work that has to be
                // redone anyway.
                weatherIngest.buildClimatology(fips)

                // Only reached once climatology is complete for this state.
                weatherIngest.refreshForecast(fips)

                // And only score once the weather behind it is whole. A state
                // with observations but no normals has no weather for most of
                // autumn, scores without error and never reaches peak, and
                // would render as a confident "no change" all season.
                forecastService.computeState(fips)
                done += state
            } catch (e: QuotaExhausted) {
                // Not a failure. Climatology writes each chunk as it lands, so
                // whatever this run managed is kept and tomorrow resumes with
                // the remainder -- which is what stops a state bigger than one
                // day's allowance from blocking the queue forever.
                log.info("{} allowance spent during {}; stopping, resumes {}", e.window, state, e.resumesIn)
                quotaSpent = true
                break
            } catch (e: Exception) {
                log.error("{} failed: {}", state, e.message)
                skipped += state
            }
        }

        val remaining = ConusStates.ALL.size - done.size - skipped.size
        log.info(
            "backfill finished: {} states loaded, {} skipped, quotaSpent={} ",
            done.size, skipped.size, quotaSpent,
        )
        return BackfillResult(
            statesLoaded = done,
            statesSkipped = skipped.size,
            statesUntouched = remaining.coerceAtLeast(0),
            stoppedOnQuota = quotaSpent,
            stoppedOnTime = stoppedForTime,
        )
    }
}

data class BackfillResult(
    val statesLoaded: List<String>,
    /** Already complete, or not tiled yet. */
    val statesSkipped: Int,
    val statesUntouched: Int,
    /** True when the daily allowance ran out, which is expected and not an error. */
    val stoppedOnQuota: Boolean,
    val stoppedOnTime: Boolean,
)

data class RefreshResult(
    val statesRefreshed: List<String>,
    /** How many were eligible, so a capped run can be told from a complete one. */
    val statesLoaded: Int,
    val stoppedOnQuota: Boolean,
)
