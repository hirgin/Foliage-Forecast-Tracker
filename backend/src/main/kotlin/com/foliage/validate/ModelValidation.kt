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

data class ValidationResult(
    val season: Int,
    val statesRequested: List<String>,
    val observationsFetched: Int,
    val observationsMatched: Int,
    val meanSignedError: Double?,
    val meanAbsoluteError: Double?,
    /** Ordered by how far the model is out, worst first. */
    val bySpecies: List<SpeciesAccuracy>,
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
    fun run(states: List<String>, year: Int, pastSeasons: Int = 3): ValidationResult {
        val from = season.start(year)
        val to = season.end(year)

        val observations = (1..pastSeasons).flatMap { back ->
            val y = year - back
            states.flatMap { npn.forState(it, season.start(y), season.end(y)) }
        }
        log.info("validation: {} leaf-colour observations across {} states", observations.size, states.size)

        // One query per cell would be one round trip per observation against a
        // hosted database. The season for a cell is a single read, and several
        // observations usually share one.
        val byCell = observations.groupBy { grid.cellAt(LonLat(lon = it.longitude, lat = it.latitude), resolution) }

        val errors = mutableListOf<Pair<LeafColourObservation, Double>>()
        for ((h3, group) in byCell) {
            val series = runCatching { forecasts.timeline(h3) }.getOrDefault(emptyList())
            if (series.isEmpty()) continue
            val byDay: Map<LocalDate, Double> = series.associate { it.day to it.progression }
            for (o in group) {
                // Matched on month and day. The year the volunteer looked is
                // deliberately discarded; see the note on run().
                val sameDay = runCatching { o.date.withYear(year) }.getOrNull() ?: continue
                val modelled = byDay[sameDay] ?: continue
                errors += o to (modelled - o.percentColored)
            }
        }

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
            note = "Progression points against USA-NPN 'Colored leaves' intensity, " +
                "from the $pastSeasons seasons before $year, matched by calendar date. " +
                "Positive means the model shows more colour than was observed. " +
                "Each observation is one plant; each cell is 3 km of landscape. " +
                "Tests the climatological season, not the 16-day forecast window.",
        )
    }
}
