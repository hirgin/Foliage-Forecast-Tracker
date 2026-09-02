package com.foliage.validate

import com.foliage.grid.H3Grid
import com.foliage.grid.LonLat
import com.foliage.ingest.weather.Season
import com.foliage.persistence.ForecastRepository
import java.time.LocalDate
import kotlin.math.abs
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

/** How the model did against one species. */
data class SpeciesAccuracy(
    val species: String,
    val observations: Int,
    /** Positive means the model says more colour than was actually there. */
    val meanSignedError: Double,
    val meanAbsoluteError: Double,
)

/**
 * The model against the observations over one stretch of the season.
 *
 * Carries both means, not just their difference, because the difference alone
 * cannot tell two very different faults apart. A constant gap across the whole
 * season is a scale mismatch -- progression and "percent of canopy coloured"
 * are related but not the same quantity. A gap that is wide in September and
 * closes by November is a curve that climbs too early, which is a real defect
 * and a fixable one.
 */
data class WindowAccuracy(
    val window: String,
    val observations: Int,
    val meanObserved: Double,
    val meanModelled: Double,
    val meanSignedError: Double,
)

/**
 * Whether the model orders the season correctly, independent of scale.
 *
 * The metric that had to exist. Comparing modelled progression against NPN's
 * "percent of canopy coloured" point-for-point compares two different
 * quantities. Progression describes a 3 km stand; an NPN record describes one
 * plant. Individual plants do reach the top bucket, but a stand's plants are
 * never all there at once, so the *mean* of observations flattens around 75
 * late in the season while modelled progression carries on to 100. Measured in
 * Vermont, observations sat at 72.2 in late September against a modelled 73.3
 * -- agreement -- and then the observed mean flattened while the modelled one
 * did not, which reads as a growing error and is not one.
 *
 * A first attempt to quantify that gap reported the mean of the top decile of
 * observations, which is just the top bucket's midpoint (97.5) and says
 * nothing about a plateau. The honest version of that evidence is [byWindow],
 * which shows both means side by side over the season.
 *
 * The signed mean is still worth reporting, but it cannot be optimised
 * against: three separate least-squares fits against it all "improved" by
 * pushing peak into late October, which is flatly wrong for New England and
 * was caught only by checking peak dates against published windows.
 *
 * Rank correlation asks the question that survives the mismatch: when the
 * model says one observation is further along than another, is it? A model
 * with a constant offset scores perfectly here, and a model whose timing is
 * genuinely wrong does not.
 */
data class RankAgreement(
    val pairs: Int,
    /** Spearman correlation over matched observations, -1 to 1. */
    val spearman: Double,
)

data class ValidationResult(
    val season: Int,
    val statesRequested: List<String>,
    val observationsFetched: Int,
    val observationsMatched: Int,
    val meanSignedError: Double?,
    val meanAbsoluteError: Double?,
    /** Ordered by how far the model is out, worst first. */
    val bySpecies: List<SpeciesAccuracy>,
    /** Chronological, so the shape of the error over the season is readable. */
    val byWindow: List<WindowAccuracy>,
    /** Scale-invariant timing agreement; read this before the signed error. */
    val rank: RankAgreement?,
    val note: String,
)

/**
 * Measures the model against observations of real leaves.
 *
 * This is the first check in the project that is not the model marking its own
 * homework. Everything else asserts internal consistency -- bounded outputs,
 * monotonic response to each driver, peak landing where the fitted constant
 * says it should. All of that can be true of a model that is confidently wrong.
 *
 * Here each observation is a volunteer's record of how much of one plant's
 * canopy had turned on a given day. The hexagon containing it has a modelled
 * progression for that same day. The difference between them is the first
 * honest error figure this model has had.
 *
 * **Read the aggregate, not a pair.** An observation is a single plant; a cell
 * is 3 km of averaged landscape. A red maple in a front garden turning ahead of
 * the woods behind it is a real disagreement between a plant and its
 * neighbourhood, not evidence the model is broken. Scatter is expected; bias is
 * the signal.
 *
 * **Why per species.** The largest known residual is that the model assumes
 * maple-beech everywhere -- aspen and birch run early, oak runs late -- and
 * every NPN observation names its plant. Grouping the error by species turns a
 * suspicion drawn from four reference towns into a measurement.
 */
@Service
class ModelValidation(
    private val npn: NpnObservations,
    private val forecasts: ForecastRepository,
    private val grid: H3Grid,
    private val season: Season,
    @Value("\${foliage.grid.resolution}") private val resolution: Int,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Cells per timeline query. Bounded so no single statement is refused. */
    private val TIMELINE_BATCH = 200

    /** Below this, a rank correlation is not measuring anything. */
    private val MIN_PAIRS_FOR_RANK = 30

    /** Below this, a per-species figure is noise dressed as a measurement. */
    private val minObservationsPerSpecies = 8

    /**
     * Observations are read from past autumns and compared by calendar date.
     *
     * They have to be. A forecast for this year cannot be checked against this
     * year's leaves until the leaves have turned, and running the check only in
     * December would make it useless for building the thing.
     *
     * Comparing across years is not a fudge here, it is what the model already
     * claims. Beyond the 16-day horizon every cell is scored from climatology --
     * ADR-0005 says so, and 84% of a season is on that side of the line. The
     * model's October is explicitly "a typical October", so the honest test is
     * against what typical Octobers actually looked like. Several past seasons
     * are used rather than one, because a single autumn is weather and the
     * claim being tested is about climate.
     *
     * What this cannot test is the first 16 days, where the model is a real
     * forecast rather than a typical year. That window needs this year's
     * observations and will not exist until the season runs.
     */
    /**
     * @param species when given, only observations of these plants are compared,
     *   matched loosely on the common name. The point of it: this model
     *   represents a maple-beech stand, and the raw NPN aggregate is 120
     *   species including shrubs, ornamentals and early-turning invasives.
     *   Measuring a maple model against that mixture asks it to be right about
     *   plants it never claimed to describe, and the answer would be a number
     *   nobody could act on.
     */
    fun run(
        states: List<String>,
        year: Int,
        pastSeasons: Int = 3,
        species: List<String> = emptyList(),
    ): ValidationResult {
        val from = season.start(year)
        val to = season.end(year)

        val wanted = species.map { it.trim().lowercase() }.filter { it.isNotBlank() }
        val observations = (1..pastSeasons).flatMap { back ->
            val y = year - back
            states.flatMap { npn.forState(it, season.start(y), season.end(y)) }
        }.filter { o ->
            wanted.isEmpty() || wanted.any { o.label.lowercase().contains(it) }
        }
        log.info("validation: {} leaf-colour observations across {} states", observations.size, states.size)

        // One query per cell would be one round trip per observation against a
        // hosted database. The season for a cell is a single read, and several
        // observations usually share one.
        val byCell = observations.groupBy { grid.cellAt(LonLat(lon = it.longitude, lat = it.latitude), resolution) }

        val errors = mutableListOf<Pair<LeafColourObservation, Double>>()

        // Read in batches, not one cell at a time.
        //
        // A query per cell is a round trip per cell to a hosted database, and
        // observations spread across thousands of them. The first run sat for
        // half an hour on exactly this while it worked through the cells one by
        // one. Batching is the same fix the export needed, for the same reason.
        var batchesRead = 0
        var batchesFailed = 0
        for (batch in byCell.keys.chunked(TIMELINE_BATCH)) {
            val series = runCatching { forecasts.timelinesFor(batch) }
                .onSuccess { batchesRead++ }
                .onFailure { batchesFailed++; log.warn("timeline batch failed: {}", it.message) }
                .getOrDefault(emptyMap())
            for (h3 in batch) {
                val rows = series[h3].orEmpty()
                if (rows.isEmpty()) continue
                val byDay: Map<LocalDate, Double> = rows.associate { it.day to it.progression }
                val group = byCell[h3].orEmpty()
                for (o in group) {
                // Matched on month and day. The year the volunteer looked is
                // deliberately discarded; see the note on run().
                    val sameDay = runCatching { o.date.withYear(year) }.getOrNull() ?: continue
                    val modelled = byDay[sameDay] ?: continue
                    errors += o to (modelled - o.percentColored)
                }
            }
        }

        // A validation that cannot read the forecasts has not validated
        // anything, and must not report that as a clean zero.
        //
        // This is exactly what happened the first time it ran for real: the
        // database's usage quota was exhausted, every batch threw, and the
        // result came back "179 observations fetched, 0 matched" -- which reads
        // as a finding about the data rather than a total outage. The
        // getOrDefault that produced it was written as resilience. Resilience
        // that turns an outage into a plausible answer is worse than a crash.
        if (batchesFailed > 0 && batchesRead == 0) {
            error(
                "could not read any forecasts: all $batchesFailed timeline reads failed. " +
                    "Nothing was compared, so there is no accuracy figure to report.",
            )
        }
        if (batchesFailed > 0) {
            log.warn(
                "{} of {} timeline reads failed; the figures below cover only what was readable",
                batchesFailed, batchesFailed + batchesRead,
            )
        }

        // Half-month windows: coarse enough that each holds a usable count,
        // fine enough to show a curve rising too early rather than averaging
        // that away into one number.
        val byWindow = errors
            .groupBy { (o, _) ->
                val half = if (o.date.dayOfMonth <= 15) "1-15" else "16-end"
                "%s %s".format(o.date.month.name.lowercase().replaceFirstChar { it.uppercase() }.take(3), half)
            }
            .map { (label, rows) ->
                WindowAccuracy(
                    window = label,
                    observations = rows.size,
                    meanObserved = rows.sumOf { it.first.percentColored } / rows.size,
                    meanModelled = rows.sumOf { it.first.percentColored + it.second } / rows.size,
                    meanSignedError = rows.sumOf { it.second } / rows.size,
                )
            }
            .sortedBy { w ->
                val month = listOf("Sep", "Oct", "Nov", "Dec").indexOf(w.window.take(3))
                month * 2 + if (w.window.endsWith("1-15")) 0 else 1
            }

        val rank = rankAgreement(errors)

        val signed = errors.map { it.second }
        val bySpecies = errors
            .groupBy { it.first.label.ifBlank { "unnamed" } }
            .filterValues { it.size >= minObservationsPerSpecies }
            .map { (name, rows) ->
                SpeciesAccuracy(
                    species = name,
                    observations = rows.size,
                    meanSignedError = rows.sumOf { it.second } / rows.size,
                    meanAbsoluteError = rows.sumOf { abs(it.second) } / rows.size,
                )
            }
            .sortedByDescending { abs(it.meanSignedError) }

        return ValidationResult(
            season = year,
            statesRequested = states,
            observationsFetched = observations.size,
            observationsMatched = errors.size,
            meanSignedError = signed.takeIf { it.isNotEmpty() }?.average(),
            meanAbsoluteError = signed.takeIf { it.isNotEmpty() }?.map { abs(it) }?.average(),
            bySpecies = bySpecies,
            byWindow = byWindow,
            rank = rank,
            note = "Progression points against USA-NPN 'Colored leaves' intensity, " +
                "from the $pastSeasons seasons before $year, matched by calendar date. " +
                "Positive means the model shows more colour than was observed. " +
                "Each observation is one plant; each cell is 3 km of landscape. " +
                "Tests the climatological season, not the 16-day forecast window.",
        )
    }

    /** Delegates to [RankCorrelation]; see the note on [RankAgreement]. */
    private fun rankAgreement(errors: List<Pair<LeafColourObservation, Double>>): RankAgreement? {
        if (errors.size < MIN_PAIRS_FOR_RANK) return null
        val observed = errors.map { it.first.percentColored }
        val modelled = errors.map { it.first.percentColored + it.second }
        val rho = RankCorrelation.spearman(observed, modelled) ?: return null
        return RankAgreement(pairs = observed.size, spearman = rho)
    }
}

/**
 * Spearman rank correlation, with tied values sharing their average rank.
 *
 * Separated from the service so it can be tested without a database, a
 * network, or a Spring context. Ties are not incidental here -- NPN intensity
 * is bucketed, so hundreds of observations share each of six values, and
 * breaking those ties by input order would inject noise proportional to how
 * coarse the source is.
 */
internal object RankCorrelation {

    /** Null when the inputs cannot support a correlation at all. */
    fun spearman(a: List<Double>, b: List<Double>): Double? {
        if (a.size != b.size || a.size < 2) return null
        val ra = averagedRanks(a)
        val rb = averagedRanks(b)
        val meanA = ra.average()
        val meanB = rb.average()
        var num = 0.0
        var sa = 0.0
        var sb = 0.0
        for (i in ra.indices) {
            val x = ra[i] - meanA
            val y = rb[i] - meanB
            num += x * y
            sa += x * x
            sb += y * y
        }
        val denom = Math.sqrt(sa * sb)
        // Zero variance on either side: every value tied, so there is no
        // ordering to agree or disagree about.
        return if (denom == 0.0) null else num / denom
    }

    /** Ranks with tied values sharing their average rank. */
    fun averagedRanks(values: List<Double>): DoubleArray {
        val order = values.indices.sortedBy { values[it] }
        val ranks = DoubleArray(values.size)
        var i = 0
        while (i < order.size) {
            var j = i
            while (j + 1 < order.size && values[order[j + 1]] == values[order[i]]) j++
            val shared = (i + j) / 2.0 + 1.0
            for (k in i..j) ranks[order[k]] = shared
            i = j + 1
        }
        return ranks
    }
}
