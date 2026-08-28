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
)

data class CellInput(val latitude: Double, val elevationM: Int?)

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

    /** Nights below this accumulate chilling and accelerate colour. */
    const val CHILL_THRESHOLD_C = 7.0

    /** Warm days above this delay senescence. */
    const val WARM_THRESHOLD_C = 20.0

    /** How strongly a degree of chilling amplifies a day's photoperiod forcing. */
    const val CHILL_GAIN = 0.09

    /** How strongly a degree of excess warmth subtracts from it. */
    const val WARM_DELAY = 0.05

    /**
     * Accumulated forcing corresponding to a fully senesced canopy.
     *
     * Calibrated so that a typical Vermont season reaches PEAK in the first
     * half of October, which is the published norm. This is the single number
     * that sets *when* peak lands, and it is guarded by a test.
     */
    const val FORCING_FULL = 62.0

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
    ): FoliageScore {
        val upToTarget = days.filter { !it.day.isAfter(target) }.sortedBy { it.day }

        var forcing = 0.0
        var chillDays = 0
        var frostDays = 0
        var hardFreeze = false

        for (d in upToTarget) {
            val photo = Photoperiod.senescenceForcing(cell.latitude, d.day, PHOTOPERIOD_THRESHOLD_H)
            if (photo <= 0.0) continue

            val chill = d.tminC?.let { (CHILL_THRESHOLD_C - it).coerceAtLeast(0.0) } ?: 0.0
            val warm = d.tmaxC?.let { (it - WARM_THRESHOLD_C).coerceAtLeast(0.0) } ?: 0.0

            if (chill > 0) chillDays++
            d.tminC?.let {
                if (it <= 0.0) frostDays++
                if (it <= HARD_FREEZE_C) hardFreeze = true
            }

            forcing += (photo * (1 + CHILL_GAIN * chill) - WARM_DELAY * warm).coerceAtLeast(0.0)
        }

        // Drought shortens and dulls the season, and brings it forward slightly.
        val observedPrecip = upToTarget.mapNotNull { it.precipMm }.sum()
        val droughtStress = normalPrecipMm
            ?.takeIf { it > 0 }
            ?.let { (1.0 - observedPrecip / it).coerceIn(0.0, 1.0) }
            ?: 0.0

        val progression = (100.0 * forcing / FORCING_FULL * (1 + 0.15 * droughtStress)).coerceIn(0.0, 100.0)

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
                    "Chilling", chillDays.toDouble(), if (chillDays > 0) "accelerates" else "neutral",
                    "$chillDays nights below ${CHILL_THRESHOLD_C.toInt()}°C, which speeds colour development.",
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
