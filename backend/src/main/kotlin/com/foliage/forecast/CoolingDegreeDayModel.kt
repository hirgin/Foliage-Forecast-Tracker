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
     * Shape of accumulation into visible colour.
     *
     * Above 1, so colour comes on slowly at first rather than the moment the
     * photoperiod gate opens: a forest does not start turning the day the
     * trigger fires.
     *
     * This was a plain power curve, which is convex — steepest exactly at
     * peak — so a stand tore through the peak band in 2.4 to 5.2 days. Real
     * peak colour holds for something closer to a week. A Weibull shape starts
     * slowly *and* saturates, so the top of the curve flattens and peak lasts.
     *
     * It does not move peak: [SCALE] is derived so [PEAK_PROGRESSION] lands
     * exactly at [S_PEAK] whatever the shape, which keeps the calibration and
     * every measured peak date intact.
     */
    const val SHAPE = 1.5

    /**
     * Scale of the accumulation curve, derived rather than fitted so [S_PEAK]
     * keeps meaning exactly what it was calibrated to.
     */
    val SCALE: Double = S_PEAK / Math.pow(-Math.log(1 - PEAK_PROGRESSION / 100.0), 1.0 / SHAPE)

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
        // Weibull. Slow to start, then saturating, so peak colour holds for
        // about a week instead of a couple of days. Approaches 100 without
        // reaching it, which is the honest shape: a stand is never more than
        // fully turned.
        val progression = (100.0 * (1 - Math.exp(-Math.pow(effective / SCALE, SHAPE))))
            .coerceIn(0.0, 100.0)

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
            // Written for someone standing in a forest, not for someone
            // reading the source. The quantities are the same; "cooling degree
            // days below 20 C since days shortened past 13 hours" is not a
            // sentence anyone should have to parse to find out why a hexagon
            // is orange.
            factors = listOf(
                Factor(
                    "Cool weather", cooling, "sets the pace",
                    buildString {
                        val pct = (100.0 * cooling / S_PEAK).coerceAtMost(999.0)
                        if (cooling <= 0.0) {
                            append("Still too warm for the leaves to turn")
                        } else {
                            append("It has been cool enough to get about ${"%.0f".format(pct)}% ")
                            append("of the way to full colour")
                        }
                        if (climatologyDays > 0) {
                            append(", though most of that is a typical year rather than measured weather")
                        }
                        append(".")
                    },
                ),
                Factor(
                    "Shorter days", gatedDays.toDouble(),
                    if (gatedDays > 0) "has started" else "not started",
                    if (gatedDays > 0) {
                        "Daylight dropped under ${PHOTOPERIOD_GATE_H.toInt()} hours $gatedDays days " +
                            "ago. That is the signal for trees to start shutting down; how cool it " +
                            "gets from then on decides how fast."
                    } else {
                        "Days are still too long. Trees have not started shutting down yet."
                    },
                ),
                Factor(
                    "Frost", frostDays.toDouble(),
                    if (hardFreeze) "damaging" else if (frostDays > 0) "speeds it up" else "none yet",
                    when {
                        hardFreeze -> "A hard freeze has hit, which knocks leaves down rather than colouring them."
                        frostDays > 0 -> "$frostDays frosty nights, which brings colour on faster."
                        else -> "No frosty nights yet."
                    },
                ),
                Factor(
                    "Rain", droughtStress * 100,
                    if (droughtStress > 0.2) "dulls the colour" else "nothing unusual",
                    buildString {
                        append("${"%.0f".format(observedPrecip)} mm of rain so far")
                        if (normalPrecipMm != null) {
                            append(", against ${"%.0f".format(normalPrecipMm)} mm in a normal year")
                            if (droughtStress > 0.2) {
                                append(". A dry autumn makes for duller colour that does not last as long")
                            }
                        }
                        append(".")
                    },
                ),
                Factor(
                    "Warm days, cool nights", meanDiurnal, "makes it brighter",
                    "About ${"%.0f".format(meanDiurnal)}°C between the day's high and low " +
                        "lately. The bigger that gap, the brighter the colour.",
                ),
            ),
        )
    }
}
