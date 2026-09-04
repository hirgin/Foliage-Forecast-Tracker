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
 * and is recorded as such in docs/model.md. Measured against ten reference
 * places from Maine to Virginia to Minnesota it gives a mean absolute error
 * of 7.1 days measured end to end.
 *
 * Against 46,424 USA-NPN leaf-colour observations -- the first check that is
 * not this model marking its own homework -- rank agreement is 0.55 for the
 * maples it represents. The raw signed error of +27 points is dominated by a
 * scale mismatch rather than by timing, and must not be fitted against;
 * docs/model.md records the three fits that tried and what each broke.
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
     * Minimum daily progress once days are short enough, in cooling-degree-day
     * equivalents.
     *
     * **Deliberately the smallest value that does the job, not the best fit.**
     *
     * Fitting floor and [S_PEAK] together minimises error over the reference
     * places -- 4.8 days against 12.2 -- by raising [S_PEAK] from 100 to 260.
     * That was shipped and it flattened the map. Local differences in
     * accumulation are what make one hexagon differ from its neighbour, and
     * they are read against the threshold, so multiplying the threshold by 2.6
     * divides every one of them by 2.6. Vermont's spread of peak dates across
     * sixty neighbouring cells fell from 10 days to 7, and the country became a
     * smooth latitude ramp with the terrain washed out of it. Elevation and
     * maritime effects are why this map is drawn on 3 km hexagons at all.
     *
     * So the floor is kept small and [S_PEAK] as low as will still carry a
     * southern autumn to its end. At 150 with a floor of 1.5 every reference
     * place finishes, error over them is 4.9 days -- better than the 12.2 of
     * the model before any of this, and as good as the flattening fit that
     * cost three quarters of the terrain detail. Holding [S_PEAK] at its
     * original 100 was tried and does not work: Florida runs past the end of
     * the season again.
     *
     * Accuracy at eleven towns is not worth the texture of 141,274 hexagons,
     * and at this point it does not have to be paid for with it.
     *
     * Fitted against the day a cell *enters* the peak band, progression 75,
     * because that is the date the rest of the system reports as its peak. A
     * first fit targeted the band's midpoint at 82 instead, which is 80.8% of
     * the way in at this shape -- so every prediction was late by that gap, and
     * end-to-end scoring put Louisiana's median on 29 October against a target
     * of 27 November. The offline fit and the thing it is fitting have to agree
     * on what a peak date means.
     */
    /**
     * Day length below which the floor applies, in hours.
     *
     * A second, shorter threshold than [PHOTOPERIOD_GATE_H], and the reason is
     * astronomy running the wrong way. Low latitudes swing less over the year,
     * so they cross 13 hours *earlier*: 26 August at 30.5 N against 9 September
     * at 47 N. Hanging the floor on the same gate therefore gave the south a
     * fortnight's head start, which is backwards, and it showed -- the Gulf
     * states were tinting by 4 September and by 17 October the whole country
     * was near peak at once, with no north-to-south march left in it.
     *
     * Below about 11.5 hours the order reverses and matches the season: the
     * north crosses it in early October and the Gulf coast in mid-October. So
     * cooling still counts from the 13-hour gate, and the floor -- the part
     * that carries a stand where cold never arrives -- waits for the shorter
     * day.
     */
    const val PHOTOPERIOD_FLOOR_GATE_H = 11.5

    const val PHOTOPERIOD_FLOOR = 1.5

    /**
     * Accumulated cooling degree days at which a stand is at peak colour.
     *
     * The one fitted parameter. Everything else here is chosen from physical
     * reasoning; this sets absolute timing, while the spread between places
     * comes from their cooling rates and is 26-29 days at any value tried.
     *
     * **Refitted from 185 after the climatology changed.** 185 was fitted
     * against normals built from five years of archive sampled at res 5. The
     * weather pipeline now uses three years sampled at res 4 -- a deliberate
     * trade for load speed -- which shifts every cooling total, and a
     * parameter calibrated against the old totals no longer means what it
     * did. Left alone it put the whole country 6 to 9 days late, which is the
     * kind of error that looks like a modelling failure and is really a stale
     * constant. Refitting against ten reference places spanning Maine to
     * Virginia to Minnesota takes mean absolute error from 12.4 days to 6.0.
     *
     * The residuals that remain are not noise and are not fixable here: the
     * aspen-birch north (Duluth, Ely, Marquette) still runs 8 to 14 days late
     * and oak-heavy Litchfield 15 days early. Both are species composition,
     * which this model does not represent at all. See docs/model.md.
     *
     * The lesson worth keeping: a fitted constant is coupled to the data it
     * was fitted against. Changing the ingest changed the model.
     */
    const val S_PEAK = 150.0

    /** Where peak sits on the 0-100 progression scale, at the middle of the PEAK band. */
    const val PEAK_PROGRESSION = 82.0

    /**
     * Shape of accumulation into visible colour.
     *
     * At 1 this is a plain exponential approach to fully turned: senescence as
     * first-order decay of the chlorophyll still left, fastest when there is
     * most to lose. That is the conventional kinetics and it needs no
     * justification beyond itself.
     *
     * **Lowered from 1.5 when [S_PEAK] was refitted, and the two are coupled.**
     * The peak band is a fixed *fraction* of [S_PEAK] wide -- 0.35 of it at
     * shape 1.5 -- so cutting the constant from 185 to 100 also halved how
     * long peak lasts, from 7.1 days to 4.9. That is a real loss: peak holding
     * for about a week is a property this model was deliberately given, and
     * timing should not have been bought with it. Shape 1.0 widens the band
     * back to 0.53 of [S_PEAK] and restores peak to 7.1 days with the season
     * slightly longer at 27, while mean absolute error stays at 6.0 days
     * against 5.9 for the nominally best fit.
     *
     * **That "free" was wrong, and 46,424 leaf observations say so.** The
     * argument was that the slow start shape above 1 provided is not needed,
     * because the photoperiod gate already suppresses early progress and
     * mid-September weather sits near [T_BASE_C] and accumulates almost
     * nothing. It was checked only against peak *dates* at ten reference
     * towns, a metric structurally blind to the shape of the curve between
     * them. Measured against USA-NPN records, this curve says 61% of the
     * canopy has turned in late September when sugar and red maples are
     * observed at 29%. Early September is near-exact; the error appears
     * entirely in the climb.
     *
     * It is left at 1.0 anyway, deliberately. Raising it fixes late September
     * and makes October worse, the residual floors well short of the
     * observations at any shape, and the peak band -- 53% of [S_PEAK] here --
     * collapses to 18% by shape 2.9, which is peak lasting two days. A single
     * global shape cannot be right for maple, aspen and oak at once, and the
     * fix is the species term rather than a better compromise constant.
     *
     * See the validation section of docs/model.md for the fits that were run
     * and why each was rejected.
     *
     * It does not move peak: [SCALE] is derived so [PEAK_PROGRESSION] lands
     * exactly at [S_PEAK] whatever the shape.
     */
    const val SHAPE = 1.0

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
        /**
         * First day of the season, for walking the calendar rather than the
         * weather. Defaults to the first day weather exists for, which is the
         * same thing whenever coverage is complete.
         */
        seasonFirstDay: LocalDate? = null,
    ): FoliageScore {
        val upToTarget = days.filter { !it.day.isAfter(target) }.sortedBy { it.day }

        var cooling = 0.0
        var gatedDays = 0
        var frostDays = 0
        var hardFreeze = false
        var climatologyDays = 0

        // Walked by calendar day, not by the days weather happens to exist for.
        //
        // Daylight is astronomy: it is known for every date whether or not
        // anybody fetched a forecast. The floor below therefore applies to a
        // day with no weather too, and that matters because weather coverage is
        // deliberately uneven -- a state that has already peaked is not sent
        // another month of it, since more cooling cannot change a stand that is
        // finished.
        //
        // Skipping those days instead froze every cell in such a state on the
        // last day it had weather for. Cells sitting mid-peak on 15 November
        // stayed mid-peak through December: 334 hexagons were still showing
        // peak on 15 December, along the Carolina coast and in Florida, having
        // entered the band weeks earlier and never been allowed to leave.
        val byDay = upToTarget.associateBy { it.day }
        var cursor = seasonFirstDay ?: upToTarget.firstOrNull()?.day
        while (cursor != null && !cursor.isAfter(target)) {
            val d = byDay[cursor]
            val day = cursor
            cursor = cursor.plusDays(1)

            if (d != null && d.kind == WeatherKind.CLIMATOLOGY) climatologyDays++
            d?.tminC?.let {
                if (it <= 0.0) frostDays++
                if (it <= PhenologyModel.HARD_FREEZE_C) hardFreeze = true
            }

            // The gate. Nothing accumulates while days are still long.
            if (Photoperiod.hours(cell.latitude, day) > PHOTOPERIOD_GATE_H) continue
            gatedDays++

            // Mean of the day's extremes. Null when either is missing, rather
            // than guessing from one -- a day with only a maximum says nothing
            // useful about how much the night cooled. A day with no weather at
            // all still gets the floor, because its daylight is not in doubt.
            val hi = d?.tmaxC
            val lo = d?.tminC
            // The floor only applies once days are genuinely short; see
            // PHOTOPERIOD_FLOOR_GATE_H.
            val floorApplies = Photoperiod.hours(cell.latitude, day) <= PHOTOPERIOD_FLOOR_GATE_H

            if (hi == null || lo == null) {
                if (floorApplies) cooling += PHOTOPERIOD_FLOOR
                continue
            }
            val mean = (hi + lo) / 2.0
            val cool = maxOf(T_BASE_C - mean, 0.0)

            // Cold sets the pace, but short days set a floor under it.
            //
            // Without the floor this model cannot finish an autumn anywhere
            // the weather does not cooperate. On the Gulf coast the daily mean
            // sits at or above [T_BASE_C] well into November, so a stand there
            // accumulated nothing for weeks and either peaked in December or
            // never peaked at all -- 2,366 Florida cells and 2,058 Alabama
            // cells froze part-way through autumn and held that colour to the
            // end of the season. Those forests do turn; they just do not do it
            // because of the cold.
            //
            // Senescence is triggered by day length and *paced* by temperature,
            // which is what the photoperiod gate above already says. The floor
            // finishes the thought: past the gate a stand makes progress on
            // shortening days alone, and cold merely hurries it.
            //
            // A floor rather than an addition, deliberately. Adding a term
            // everywhere would speed the north up too -- northern days shorten
            // further and faster, so a daylight-proportional term lands
            // hardest where it is least needed, and the fit had to raise
            // [S_PEAK] to compensate and pushed Maine and Vermont late. Under a
            // floor a cold day is unchanged, because its own cooling already
            // exceeds it.
            // Added to the day's own cooling, not substituted for it.
            //
            // Taking the larger of the two -- max(cool, floor) -- was the first
            // attempt and it flattened the map. Wherever the floor won, every
            // cell accumulated at exactly the same rate no matter what its own
            // weather did, so local differences were clipped away and the whole
            // south became a smooth latitude ramp: 52 cells around Baton Rouge
            // shared a four-day spread of peak dates, against fourteen days
            // across the same span in Georgia. Elevation and maritime effects
            // are the reason this map is drawn on 3 km hexagons at all, and
            // clipping is precisely how to lose them.
            //
            // Adding keeps them. A southern cell 1.5 C warmer than its
            // neighbour now peaks two days later instead of the same day.
            cooling += cool + (if (floorApplies) PHOTOPERIOD_FLOOR else 0.0)
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

        // Species. Aspen and birch turn on less cooling than maple, oak on
        // more, and until now this model said all three were maple.
        //
        // Applied to the threshold rather than as a shift in days, because a
        // day is worth a different amount of cooling in Minnesota than in
        // Georgia; scaling the threshold keeps timing a function of
        // accumulation, which is the point of a degree-day model. An
        // unsampled cell gets 1.0 and is untouched.
        val species = ForestTypeGroup.forCode(cell.forestTypeGroup)
        val speciesMultiplier = ForestTypeGroup.multiplierFor(cell.forestTypeGroup)
        val speciesScale = SCALE * speciesMultiplier

        // What this cell's forest actually needs, so the explanation below
        // reports progress toward *its* peak rather than a maple's. Without
        // this an aspen stand is told it is 61% of the way to full colour on
        // the day it peaks.
        val peakThreshold = S_PEAK * speciesMultiplier

        // Saturating, so peak colour holds for about a week instead of a
        // couple of days. Approaches 100 without reaching it, which is the
        // honest shape: a stand is never more than fully turned.
        val turning = (100.0 * (1 - Math.exp(-Math.pow(effective / speciesScale, SHAPE))))
            .coerceIn(0.0, 100.0)

        // An evergreen forest stays green, so it is scored as staying green.
        //
        // Not a special case so much as the plain fact the model was missing.
        // Cooling accumulates over a spruce stand exactly as it does over a
        // maple, and with nothing to stop it the map gave Prescott, Arizona a
        // peak on 8 November while the cell's own explanation read "mostly
        // evergreens, which do not put on an autumn display". Those were the
        // scattered hexagons turning at odd times in places nobody visits for
        // the leaves.
        //
        // Zeroed rather than dropped from the grid. A conifer hexagon is still
        // forest and still belongs on a forest map; what it is not is a
        // hexagon that ever changes colour. Dropping it would also have thrown
        // away every mixed stand the per-cell mode happens to call conifer --
        // 45% spruce and 40% aspen classifies as spruce, and that aspen is
        // real.
        //
        // Western larch is deliberately not included: it is a conifer that
        // drops its needles and goes gold, and western Montana's larch season
        // is one people travel for.
        val progression = if (species == ForestTypeGroup.CONIFER) 0.0 else turning

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
            // A display that never happens cannot be a vivid one.
            .let { if (species == ForestTypeGroup.CONIFER) 0.0 else it }
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
                        val pct = (100.0 * cooling / peakThreshold).coerceAtMost(999.0)
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
                    "Forest type",
                    speciesMultiplier,
                    when {
                        cell.forestTypeGroup == null -> "not surveyed"
                        cell.forestTypeGroup == 0 -> "little forest"
                        speciesMultiplier < 1.0 -> "turns early"
                        speciesMultiplier > 1.0 -> "turns late"
                        else -> "the usual timing"
                    },
                    when {
                        // Said plainly, and said at all -- this was the
                        // model's largest known error for most of its life and
                        // the map gave no hint of it.
                        // Surveyed-and-empty is a different statement from
                        // not-surveyed, and conflating them tells someone
                        // standing in a Minneapolis suburb that nobody has
                        // looked, when in fact somebody looked and found
                        // parkland. Both score at the baseline; only one of
                        // them is a gap in the data.
                        cell.forestTypeGroup == null ->
                            "The trees here have not been surveyed, so this assumes a maple and beech " +
                                "wood, which is the commonest kind in the Northeast."
                        cell.forestTypeGroup == 0 ->
                            "Not much continuous forest here -- open ground, farmland, water or town. " +
                                "Any colour will come from scattered trees rather than a hillside."
                        species == ForestTypeGroup.CONIFER ->
                            "Mostly evergreens, which do not put on an autumn display."
                        // A surveyed code this project has no group for -- the
                        // western and tropical hardwoods. Real forest, no
                        // measured multiplier, so it scores at the baseline and
                        // says so rather than claiming to know.
                        species == null ->
                            "The trees here were surveyed but are not a kind this forecast has " +
                                "measured, so it assumes the usual maple and beech timing."
                        speciesMultiplier < 1.0 ->
                            "Mostly ${species.label}. These turn earlier than maples and drop their " +
                                "leaves quickly once they do."
                        speciesMultiplier > 1.0 ->
                            "Mostly ${species.label}. These hold their leaves later than maples, " +
                                "often well into the autumn."
                        else ->
                            "Mostly ${species.label}, which is the timing this forecast is built around."
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
