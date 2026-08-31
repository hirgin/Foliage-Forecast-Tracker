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
) {

    private val log = LoggerFactory.getLogger(javaClass)

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
        val covered = runCatching { normals.cellsWithNormals() }.getOrDefault(emptySet())
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
                val parents = cells.distinctRes5Parents(fips)
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
                log.info("daily allowance spent refreshing {}; the rest keep yesterday's data", state)
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

        val covered = runCatching { normals.cellsWithNormals() }.getOrDefault(emptySet())

        for (state in ConusStates.ALL) {
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

            val parents = cells.distinctRes5Parents(fips)
            if (parents.isNotEmpty() && covered.containsAll(parents)) {
                skipped += state
                continue
            }

            log.info(
                "backfilling {} ({}): {} of {} parents already have normals",
                state, fips, parents.count { it in covered }, parents.size,
            )

            try {
                weatherIngest.refreshForecast(fips)
                weatherIngest.buildClimatology(fips)
                // Only score once the weather behind it is complete. A state
                // with observations but no normals has no weather for most of
                // autumn, scores without error and never reaches peak, and
                // would render as a confident "no change" all season.
                forecastService.computeState(fips)
                done += state
            } catch (e: QuotaExhausted) {
                // Not a failure. The allowance resets and tomorrow's run picks
                // up exactly here, so stop rather than burning the rest of the
                // job on requests that cannot succeed.
                log.info("daily allowance spent during {}; stopping until it resets", state)
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
