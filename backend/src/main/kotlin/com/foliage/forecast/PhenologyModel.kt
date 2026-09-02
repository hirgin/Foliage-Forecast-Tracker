package com.foliage.forecast

import com.foliage.domain.WeatherKind
import java.time.LocalDate

enum class FoliageStage { NO_CHANGE, PATCHY, PARTIAL, NEAR_PEAK, PEAK, PAST_PEAK }

/** One day of weather for one cell, already lapse-rate corrected. */
data class DayInput(
    val day: LocalDate,
    val kind: WeatherKind,
    val tmaxC: Double?,
    val tminC: Double?,
    val precipMm: Double?,
    /**
     * Precomputed chilling for this day, overriding what would be derived
     * from [tminC].
     *
     * Set for CLIMATOLOGY days. Chilling is a threshold function, so deriving
     * it from a multi-year *mean* temperature destroys it -- cold snaps fall
     * on different dates each year and average away. Climatological chilling
     * is therefore computed per year and averaged, then supplied here. See V6.
     */
    val chillUnits: Double? = null,
)

data class CellInput(
    val latitude: Double,
    val elevationM: Int?,
    /**
     * FIA forest type group code, or null where the cell has not been sampled.
     *
     * Defaulted so that every existing caller and test scores exactly as it
     * did before the species term existed. A grid of 141,274 cells cannot be
     * sampled in one job, so "not sampled yet" has to be an ordinary state
     * rather than a failure -- see [com.foliage.forecast.ForestTypeGroup].
     */
    val forestTypeGroup: Int? = null,
)

/** A single named term, so the UI can explain *why* a cell scores as it does. */
data class Factor(val name: String, val value: Double, val effect: String, val detail: String)

data class FoliageScore(
    val progression: Double,
    val stage: FoliageStage,
    val intensity: Double,
    val confidence: Double,
    val factors: List<Factor>,
)

/**
 * Scores forest colour progression for one cell on one day.
 *
 * Deliberately a transparent weighted model rather than a fitted one. There is
 * no ground truth for when foliage actually peaked, so a regression could not
 * be validated either — and an explainable model at least lets the UI say why
 * a hexagon is the colour it is. Every constant below is a stated assumption,
 * not a fitted parameter.
 *
 * Correctness here means internal consistency: bounded outputs, and monotonic
 * response to each driver. Those are what the tests assert. Accuracy against
 * reality is explicitly not claimed.
 */
object PhenologyModel {

    /** Day length below which temperate broadleaf senescence is taken to begin. */
    const val PHOTOPERIOD_THRESHOLD_H = 13.0

    /**
     * Accumulated chilling above which the threshold is taken to bind on
     * essentially every night, in degree-nights per day.
     *
     * Only used to correct climatological chilling to a cell's own elevation.
     * See [adjustClimatologicalChill].
     */
    const val CHILL_ADJUST_SATURATION = 3.0

    /**
     * Climatological chilling corrected to a cell's own elevation.
     *
     * [base] is a five-year mean of `max(0, threshold - tmin)`, and [coolingC]
     * is how much colder this cell is than the parent reading it inherits
     * (negative when the cell is colder, matching a lapse-rate adjustment).
     *
     * **Shifting the mean by the full temperature difference is wrong.** The
     * derivative of `E[max(0, T - tmin)]` with respect to a temperature shift
     * is `P(tmin < T)`, not 1. Where the threshold rarely binds -- which is
     * most of a mild coastal autumn -- that probability is near zero and the
     * correction should be near zero too.
     *
     * Subtracting the whole difference instead let a few tens of metres of
     * elevation erase a month of chilling. Sampled across coastal southern New
     * England the result was bimodal, either 0-5 or 27-33 accumulated chill
     * days with nothing in between, and two cells sharing one res 5 parent --
     * so identical weather -- came out at 30 and 5.
     *
     * The binding probability is approximated by how much chilling there
     * already is, saturating once there is enough to imply the threshold is
     * crossed nightly. That is first-order correct at both ends: no correction
     * where nothing chills, a full linear one where everything does.
     */
    fun adjustClimatologicalChill(base: Double, coolingC: Double): Double {
        val binding = (base / CHILL_ADJUST_SATURATION).coerceIn(0.0, 1.0)
        return (base - coolingC * binding).coerceAtLeast(0.0)
    }

    /** Nights below this accumulate chilling and accelerate colour. */
    const val CHILL_THRESHOLD_C = 7.0

    /** Warm days above this delay senescence. */
    const val WARM_THRESHOLD_C = 20.0

    /**
     * How strongly a degree of chilling amplifies a day's photoperiod forcing.
     *
     * This is what makes elevation matter. At 0.09 the model produced a
     * correct *direction* (elevation-progression correlation 0.79) but far too
     * small a magnitude: 954 m of elevation moved peak by about two days,
     * against a field rule of thumb of roughly a week per 300 m. Raising the
     * gain widens temperature-driven separation between valley and ridge.
     */
    const val CHILL_GAIN = 0.30

    /**
     * Forcing contributed by chilling in its own right, per degree below the
     * threshold, independent of how far day length has fallen.
     *
     * Without this, chilling could only ever *multiply* the photoperiod term.
     * Photoperiod slightly favours southern latitudes (see the negative result
     * in docs/model.md), so the two partly cancelled and the model produced
     * almost no north-to-south progression -- 1.0 progression point across
     * Vermont, measured on the full grid, against source data carrying 31%
     * more accumulated chilling in the north.
     *
     * Cold nights drive senescence whether or not daylength is changing
     * quickly, so they earn a term of their own.
     */
    const val CHILL_DIRECT = 0.55

    /** How strongly a degree of excess warmth subtracts from it. */
    const val WARM_DELAY = 0.05

    /**
     * Rate at which accumulated forcing converts to progression.
     *
     * Progression saturates rather than growing linearly:
     *
     *     progression = 100 * (1 - exp(-SENESCENCE_RATE * forcing))
     *
     * A canopy can only turn once, so further chilling after the leaves have
     * changed cannot keep pushing progression at the same rate. A linear form
     * was tried first and produced a season that reached past-peak five days
     * after peak, because cumulative forcing accelerates through October. The
     * saturating form gives a peak window of eight or nine days, which is what
     * a real Vermont season looks like.
     *
     * Calibrated against the ingested Vermont data so PEAK lands around
     * 8 October and PAST_PEAK around the 17th, matching published norms.
     * This is the single number that sets *when* peak lands, and it is
     * guarded by a test.
     */
    const val SENESCENCE_RATE = 0.0402

    /** A hard freeze strips leaves rather than colouring them. */
    const val HARD_FREEZE_C = -4.0

    /**
     * Diurnal range beyond this adds no further vividness.
     *
     * Without the cap, a hard freeze *raised* intensity: a -8 C night under a
     * 15 C day is a 23 C spread, which the vividness term read as ideal
     * conditions and which outweighed the freeze penalty. Wide day-night
     * swings help colour, but only up to a point, and not by freezing.
     */
    const val DIURNAL_CAP_C = 15.0

    /** Multiplier applied once a hard freeze has occurred. */
    const val HARD_FREEZE_INTENSITY_FACTOR = 0.45

    fun score(
        cell: CellInput,
        days: List<DayInput>,
        target: LocalDate,
        normalPrecipMm: Double? = null,
        /**
         * Where precipitation accumulation starts.
         *
         * Must match the window [normalPrecipMm] was accumulated over, or the
         * drought anomaly compares different spans of time. The weather series
         * reaches back further than the season for chilling purposes, so
         * summing it whole against a season-length normal made every cell look
         * soaked and silently disabled the drought term.
         */
        precipFrom: LocalDate? = null,
    ): FoliageScore {
        val upToTarget = days.filter { !it.day.isAfter(target) }.sortedBy { it.day }

        var forcing = 0.0
        // Chilling is reported as accumulated degree-nights, not as a count of
        // nights. Counting days where chilling was non-zero silently lied on
        // climatological days: there `chill` is a five-year *mean* of
        // max(0, threshold - tmin), which is a small positive on most days in a
        // mild climate. Cape Cod was reporting "32 nights below 7C" by mid
        // October, which is not close to true, while Newport an hour away
        // reported none. Degree-nights is what the model actually accumulates,
        // and it means the same thing whichever provenance a day came from.
        var chillUnitsTotal = 0.0
        var observedChillNights = 0
        var climatologyDays = 0
        var frostDays = 0
        var hardFreeze = false

        for (d in upToTarget) {
            val photo = Photoperiod.senescenceForcing(cell.latitude, d.day, PHOTOPERIOD_THRESHOLD_H)
            if (photo <= 0.0) continue

            val chill = d.chillUnits
                ?: d.tminC?.let { (CHILL_THRESHOLD_C - it).coerceAtLeast(0.0) }
                ?: 0.0
            val warm = d.tmaxC?.let { (it - WARM_THRESHOLD_C).coerceAtLeast(0.0) } ?: 0.0

            chillUnitsTotal += chill
            if (d.kind == com.foliage.domain.WeatherKind.CLIMATOLOGY) climatologyDays++
            // Only a real night has a real minimum. A climatological day's
            // tmin is a mean across years and cannot be counted as one night.
            else if ((d.tminC ?: Double.MAX_VALUE) <= CHILL_THRESHOLD_C) observedChillNights++
            d.tminC?.let {
                if (it <= 0.0) frostDays++
                if (it <= HARD_FREEZE_C) hardFreeze = true
            }

            forcing += (
                photo * (1 + CHILL_GAIN * chill) +
                    CHILL_DIRECT * chill -
                    WARM_DELAY * warm
                ).coerceAtLeast(0.0)
        }

        // Drought shortens and dulls the season, and brings it forward slightly.
        val observedPrecip = upToTarget
            .filter { precipFrom == null || !it.day.isBefore(precipFrom) }
            .mapNotNull { it.precipMm }
            .sum()
        val droughtStress = normalPrecipMm
            ?.takeIf { it > 0 }
            ?.let { (1.0 - observedPrecip / it).coerceIn(0.0, 1.0) }
            ?: 0.0

        // Drought accelerates by adding forcing, before the saturating transform.
        val effectiveForcing = forcing * (1 + 0.15 * droughtStress)
        val progression = (100.0 * (1 - Math.exp(-SENESCENCE_RATE * effectiveForcing))).coerceIn(0.0, 100.0)

        // Vivid colour wants warm sunny days and cool nights: a wide diurnal
        // range. Drought dulls it; a hard freeze ends it.
        val recent = upToTarget.takeLast(14)
        val diurnal = recent.mapNotNull { d ->
            val hi = d.tmaxC; val lo = d.tminC
            if (hi != null && lo != null) hi - lo else null
        }
        val meanDiurnal = if (diurnal.isEmpty()) 10.0 else diurnal.average()
        val effectiveDiurnal = meanDiurnal.coerceAtMost(DIURNAL_CAP_C)
        val intensity = (50.0 + 4.0 * (effectiveDiurnal - 8.0))
            .let { it * (1 - 0.4 * droughtStress) }
            .let { if (hardFreeze) it * HARD_FREEZE_INTENSITY_FACTOR else it }
            .coerceIn(0.0, 100.0)

        return FoliageScore(
            progression = progression,
            stage = stageOf(progression),
            intensity = intensity,
            confidence = confidenceOf(upToTarget),
            factors = listOf(
                Factor(
                    "Photoperiod", forcing, "primary",
                    "Accumulated day-length forcing since the season began. Shortening days are the main trigger.",
                ),
                Factor(
                    "Chilling", chillUnitsTotal, if (chillUnitsTotal > 0) "accelerates" else "neutral",
                    buildString {
                        val degrees = "%.0f".format(chillUnitsTotal)
                        val threshold = CHILL_THRESHOLD_C.toInt()
                        if (chillUnitsTotal <= 0.0) {
                            append("No chilling below $threshold°C yet")
                        } else {
                            append("$degrees degree-nights of chilling below $threshold°C, ")
                            append("which speeds colour development")
                        }
                        // Most of a season is normals rather than measurement,
                        // and the panel should not imply otherwise. Worded so
                        // it reads correctly whether or not anything has
                        // actually been observed yet.
                        when {
                            observedChillNights > 0 && climatologyDays > 0 ->
                                append("; $observedChillNights nights observed, the rest from five-year normals")
                            observedChillNights > 0 -> append("; $observedChillNights nights observed")
                            climatologyDays > 0 -> append(", expected from five-year normals")
                        }
                        append(".")
                    },
                ),
                Factor(
                    "Frost", frostDays.toDouble(), if (hardFreeze) "damaging" else if (frostDays > 0) "accelerates" else "neutral",
                    if (hardFreeze) "A hard freeze has occurred, which strips leaves rather than colouring them."
                    else "$frostDays nights at or below freezing.",
                ),
                Factor(
                    "Drought", droughtStress * 100, if (droughtStress > 0.2) "dulls and shortens" else "neutral",
                    "Precipitation is ${"%.0f".format(observedPrecip)} mm against a normal of " +
                        (normalPrecipMm?.let { "${"%.0f".format(it)} mm" } ?: "unknown"),
                ),
                Factor(
                    "Diurnal range", meanDiurnal, "drives vividness",
                    "Mean day-night spread of ${"%.1f".format(meanDiurnal)}°C over the last two weeks.",
                ),
            ),
        )
    }

    fun stageOf(progression: Double): FoliageStage = when {
        progression < 10 -> FoliageStage.NO_CHANGE
        progression < 30 -> FoliageStage.PATCHY
        progression < 55 -> FoliageStage.PARTIAL
        progression < 75 -> FoliageStage.NEAR_PEAK
        progression < 90 -> FoliageStage.PEAK
        else -> FoliageStage.PAST_PEAK
    }

    /**
     * How much to trust the score, from the provenance of the days behind it.
     * An October estimate built on climatology is a far weaker claim than a
     * September one built on observations. See ADR-0005.
     */
    fun confidenceOf(days: List<DayInput>): Double {
        if (days.isEmpty()) return 0.0
        return days.map {
            when (it.kind) {
                WeatherKind.OBSERVED -> 1.0
                WeatherKind.FORECAST -> 0.75
                WeatherKind.CLIMATOLOGY -> 0.4
            }
        }.average()
    }
}
