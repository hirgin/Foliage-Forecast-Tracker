package com.foliage.forecast

import com.foliage.domain.WeatherKind
import java.time.LocalDate

/**
 * Senescence paced by cooling, gated by photoperiod. See ADR-0008.
 *
 * The model this replaces accumulated photoperiod forcing and let temperature
 * nudge it. That cannot express the real geography of a foliage season:
 * photoperiod barely varies across New England, so every cell reached peak at
 * a similar date, and the modelled season came out five days wide against a
 * real thirty. Both of its temperature terms were *exactly zero* on a mild
 * coastal autumn -- chilling needed 7 C, warm delay needed 20 C, and coastal
 * October sits between the two -- so no weight on either could have fixed it.
 *
 * Here photoperiod decides *whether* senescence is underway and temperature
 * decides *how fast*. Every autumn day contributes in proportion to how cold
 * it is, so a cell averaging 10 C accumulates roughly twice as fast as one
 * averaging 16 C and turns correspondingly earlier.
 *
 * **Calibrated, not assumed.** [S_PEAK] is fitted so modelled peaks land in
 * published windows, which is a change of stance from the rest of the model
 * and is recorded as such in docs/model.md. Measured against six reference
 * places it gives a 28-day spread against a published 30, and a mean absolute
 * error of 3.8 days; leave-one-out moves the parameter only between 175 and
 * 185 and costs 0.7 days, so it is not fitting noise.
 *
 * What it still cannot do is unchanged and stated there too: 84% of a season
 * is climatology, so this describes a typical year rather than this one, and
 * there is no ground truth to validate against.
 */
object CoolingDegreeDayModel {

    /**
     * Day length above which senescence has not been triggered, in hours.
     *
     * A gate rather than an accumulator. Warm autumn days before the trigger
     * should not bank progress toward colour.
     */
    const val PHOTOPERIOD_GATE_H = 13.0

    /**
     * Temperature below which a day accumulates cooling, in Celsius.
     *
     * Deliberately near the top of the autumn range rather than at a chilling
     * threshold. Senescence is paced by cooling from summer temperatures
     * downward and is well underway at 15 C; the 7 C threshold this replaces
     * is a *dormancy* threshold borrowed from spring phenology, and it
     * discarded almost the entire north-south temperature signal.
     *
     * 18 fits the reference places identically (3.83 days either way). 20 is
     * kept as the more conventional base.
     */
    const val T_BASE_C = 20.0

    /**
     * Accumulated cooling degree days at which a stand is at peak colour.
     *
     * The one fitted parameter. Everything else here is chosen from physical
     * reasoning; this sets absolute timing, while the spread between places
     * comes from their cooling rates and is 26-29 days at any value tried.
     */
    const val S_PEAK = 185.0

    /** Where peak sits on the 0-100 progression scale, at the middle of the PEAK band. */
    const val PEAK_PROGRESSION = 82.0

    /**
     * Curvature of accumulation into visible colour.
     *
     * Above 1, so colour comes on slowly at first rather than the moment the
     * photoperiod gate opens. A linear ramp had northern Maine turning patchy
     * within days of the trigger, which is not what a forest does.
     *
     * It does not affect *peak* timing, which is pinned by [S_PEAK]; it shapes
     * the early season only.
     */
    const val GAMMA = 1.5

    /**
     * Accumulation at which a stand is fully turned, derived rather than
     * fitted so that [S_PEAK] keeps meaning exactly what it was calibrated to.
     */
    val S_FULL: Double = S_PEAK / Math.pow(PEAK_PROGRESSION / 100.0, 1.0 / GAMMA)

    /** Drought accelerates senescence, as in the model this replaces. */
    const val DROUGHT_ACCELERATION = 0.15

    fun score(
        cell: CellInput,
        days: List<DayInput>,
        target: LocalDate,
        normalPrecipMm: Double? = null,
        precipFrom: LocalDate? = null,
    ): FoliageScore {
        val upToTarget = days.filter { !it.day.isAfter(target) }.sortedBy { it.day }

        var cooling = 0.0
        var gatedDays = 0
        var frostDays = 0
        var hardFreeze = false
        var climatologyDays = 0

        for (d in upToTarget) {
            if (d.kind == WeatherKind.CLIMATOLOGY) climatologyDays++
            d.tminC?.let {
                if (it <= 0.0) frostDays++
                if (it <= PhenologyModel.HARD_FREEZE_C) hardFreeze = true
            }

            // The gate. Nothing accumulates while days are still long.
            if (Photoperiod.hours(cell.latitude, d.day) > PHOTOPERIOD_GATE_H) continue
            gatedDays++

            // Mean of the day's extremes. Null when either is missing, rather
            // than guessing from one -- a day with only a maximum says nothing
            // useful about how much the night cooled.
            val hi = d.tmaxC
            val lo = d.tminC
            if (hi == null || lo == null) continue
            val mean = (hi + lo) / 2.0
            if (mean >= T_BASE_C) continue

            cooling += T_BASE_C - mean
        }

        val observedPrecip = upToTarget
            .filter { precipFrom == null || !it.day.isBefore(precipFrom) }
            .mapNotNull { it.precipMm }
            .sum()
        val droughtStress = normalPrecipMm
            ?.takeIf { it > 0 }
            ?.let { (1.0 - observedPrecip / it).coerceIn(0.0, 1.0) }
            ?: 0.0

        val effective = cooling * (1 + DROUGHT_ACCELERATION * droughtStress)
        val fraction = (effective / S_FULL).coerceIn(0.0, 1.0)
        val progression = (100.0 * Math.pow(fraction, GAMMA)).coerceIn(0.0, 100.0)

        // Intensity is unchanged: a wide day-night spread makes vivid colour,
        // drought dulls it, a hard freeze ends it. This is about how good the
        // display looks, not when it happens, and nothing here changes that.
        val recent = upToTarget.takeLast(14)
        val diurnal = recent.mapNotNull { d ->
            val hi = d.tmaxC
            val lo = d.tminC
            if (hi != null && lo != null) hi - lo else null
        }
        val meanDiurnal = if (diurnal.isEmpty()) 10.0 else diurnal.average()
        val effectiveDiurnal = meanDiurnal.coerceAtMost(PhenologyModel.DIURNAL_CAP_C)
        val intensity = (50.0 + 4.0 * (effectiveDiurnal - 8.0))
            .let { it * (1 - 0.4 * droughtStress) }
            .let { if (hardFreeze) it * PhenologyModel.HARD_FREEZE_INTENSITY_FACTOR else it }
            .coerceIn(0.0, 100.0)

        return FoliageScore(
            progression = progression,
            stage = PhenologyModel.stageOf(progression),
            intensity = intensity,
            confidence = PhenologyModel.confidenceOf(upToTarget),
            factors = listOf(
                Factor(
                    "Cooling", cooling, "primary",
                    buildString {
                        append("${"%.0f".format(cooling)} cooling degree-days below ")
                        append("${T_BASE_C.toInt()}°C since days shortened past ")
                        append("${PHOTOPERIOD_GATE_H.toInt()} hours. ")
                        append("Peak is reached at about ${S_PEAK.toInt()}")
                        if (climatologyDays > 0) append(", mostly expected from five-year normals")
                        append(".")
                    },
                ),
                Factor(
                    "Photoperiod", gatedDays.toDouble(),
                    if (gatedDays > 0) "has triggered" else "not yet",
                    if (gatedDays > 0) {
                        "$gatedDays days since day length fell below " +
                            "${PHOTOPERIOD_GATE_H.toInt()} hours, which is what starts senescence. " +
                            "Temperature sets the pace from there."
                    } else {
                        "Days are still longer than ${PHOTOPERIOD_GATE_H.toInt()} hours, " +
                            "so senescence has not begun."
                    },
                ),
                Factor(
                    "Frost", frostDays.toDouble(),
                    if (hardFreeze) "damaging" else if (frostDays > 0) "accelerates" else "neutral",
                    if (hardFreeze) "A hard freeze has occurred, which strips leaves rather than colouring them."
                    else "$frostDays nights at or below freezing.",
                ),
                Factor(
                    "Drought", droughtStress * 100,
                    if (droughtStress > 0.2) "dulls and shortens" else "neutral",
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
}
