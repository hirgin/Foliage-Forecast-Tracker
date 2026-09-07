package com.foliage.forecast

import java.time.LocalDate

/**
 * Peak date predicted directly, then a curve drawn around it.
 *
 * **This replaces the cooling-degree-day model, and inverts its stance.** That
 * model simulated senescence -- accumulate cooling past a photoperiod gate,
 * cross a threshold, call that peak -- so the date was an emergent property
 * nobody could set. Every calibration worked by nudging a rate constant and
 * hoping the date moved, and the error compounded: a slightly fast rate is a
 * day early in September and a fortnight early by late October. Northern New
 * England ran a week early for that model's entire life, and Maine showed
 * near-peak in mid-September.
 *
 * Here the peak date is the *input*. It comes from a fit against observations,
 * and the progression curve is drawn around it. Nothing accumulates, so
 * nothing compounds: being wrong about a cell is being wrong once, by a stated
 * number of days, rather than by an error that grows all season.
 *
 * **Fitted against observations, not forecasts.** The targets are the Maine
 * Forest Service's weekly zone reports, 2014-2025 -- field observers recording
 * what the trees actually did, twelve seasons of it. See
 * fixtures/maine-observed-peaks.json. Prediction maps were tried first and
 * rejected: they contradict each other by up to 16 days for the same town, and
 * the most-cited one runs 4 to 6 days early against Maine's own observers.
 * docs/published-peak-windows.md records that.
 *
 * **New England only.** The fit spans nine Maine places from Fort Kent to
 * Portland. It has no business predicting Georgia, and [supports] says so
 * rather than letting it try.
 */
object PeakDateModel {

    /**
     * Peak day-of-year = [INTERCEPT] + [LAT_DAYS_PER_DEGREE] x latitude +
     * [ELEV_DAYS_PER_METRE] x elevation.
     *
     * Least squares over eighteen places in Maine and New Hampshire, all with
     * observation-derived targets: mean absolute error 3.0 days.
     *
     * **The two states' records disagree, and this fit splits the difference.**
     * At about 45 N Maine's foresters record peak on 8 October while New
     * Hampshire's map says 30 September for the Great North Woods -- eight
     * days apart at the same latitude. Maine's is twelve seasons of explicit
     * weekly peak calls; New Hampshire's is a five-year summary published as
     * ranges, which may describe the colour season rather than the peak. They
     * are measuring differently and no linear model satisfies both, so the
     * error against either is around three days, which is inside the
     * resolution of the targets themselves.
     *
     * A Maine-only fit reaches 1.2 days and is the better *number*, but it
     * puts New Hampshire's north nine days late, and two thirds of this map
     * is not Maine.
     *
     * **Latitude and elevation, deliberately, and not temperature.** Adding
     * autumn temperature takes the error to 0.5 days and is a trap: with nine
     * points and four parameters the fit is near-interpolation, and it buys
     * that accuracy with a coefficient of -13.2 days per degree Celsius --
     * warmer weather producing an *earlier* peak, which is backwards.
     * Latitude and temperature are collinear here and the fit simply lets them
     * cancel. Temperature with elevation is no better: the elevation
     * coefficient turns positive, putting mountains later than the valleys
     * below them.
     *
     * Both coefficients here have the sign physics requires. That mattered
     * more than the extra 0.7 days, because a model whose terms are backwards
     * cannot be reasoned about when it is wrong -- and the one this replaces
     * was wrong for a month with nobody able to say why.
     */
    const val INTERCEPT = 392.6202
    const val LAT_DAYS_PER_DEGREE = -2.3258
    const val ELEV_DAYS_PER_METRE = -0.01893

    /**
     * Days per kilometre from the Atlantic. Negative: inland turns earlier,
     * the coast holds on.
     *
     * Added because latitude and elevation could not express maritime
     * moderation, and the residuals said so plainly -- Portsmouth 5.9 days
     * early, Concord 5.4, Bar Harbor 3.0, every one of them on or near the
     * water. The sea keeps autumn nights warmer for weeks, and a Downeast
     * headland peaks a fortnight after inland ground at the same latitude.
     */
    const val COAST_DAYS_PER_KM = -0.02101

    /**
     * Width of the logistic, in days. Sets how long the season and the peak
     * band last, and unlike the shape constant it replaces it says so
     * directly: peak lasts about 1.65 times this, the visible season about
     * 4.4 times.
     *
     * 6.0 gives a peak of about 10 days and a season of about 26. Both are
     * choices, and this is the first model here in which they *are* choices --
     * the old curve produced a 5 to 11 day peak as a side effect of constants
     * fitted for something else, which is how three separate fixes each
     * shortened New England's autumn without meaning to.
     */
    const val WIDTH_DAYS = 6.0

    /** Progression at which a cell enters PEAK; the logistic is anchored here. */
    const val PEAK_ENTRY = 75.0

    /** How far past the curve's midpoint progression reaches [PEAK_ENTRY]. */
    private val PEAK_OFFSET = -Math.log(100.0 / PEAK_ENTRY - 1.0)

    /**
     * Aspen and birch turn about a week before the maples.
     *
     * Expressed in days, not as a multiplier, because here the date is the
     * quantity that exists. The old model scaled a cooling threshold by 0.61,
     * which is a different number of days in Minnesota than in Maine -- and
     * nobody could say how many in either.
     */
    const val ASPEN_BIRCH_SHIFT_DAYS = -7.0

    /**
     * The latitudes this was fitted across, plus a margin.
     *
     * Outside them the fit is extrapolation and the model declines rather than
     * guessing. The model this replaces had no such limit and scored Florida
     * from constants fitted in Vermont.
     */
    const val MIN_LATITUDE = 40.5
    const val MAX_LATITUDE = 48.0

    fun supports(latitude: Double): Boolean = latitude in MIN_LATITUDE..MAX_LATITUDE

    /**
     * The Atlantic coastline of New England, coarsely. Distance to the nearest
     * of these is the maritime term's input.
     *
     * Twelve points rather than a real coastline because the term is worth
     * about a day per 50 km and the fit that produced it used exactly this
     * set. A more faithful shoreline would change the numbers it was fitted
     * against, which is a refit, not an improvement.
     */
    private val COASTLINE = listOf(
        44.90 to -66.99, 44.72 to -67.46, 44.39 to -68.20, 44.10 to -69.11,
        43.66 to -70.25, 43.07 to -70.76, 42.61 to -70.66, 42.36 to -71.05,
        41.96 to -70.67, 41.82 to -71.41, 41.35 to -72.10, 41.18 to -73.19,
    )

    /** Great-circle kilometres to the nearest coastline point. */
    fun coastDistanceKm(latitude: Double, longitude: Double): Double =
        COASTLINE.minOf { (cLat, cLon) ->
            val dLat = Math.toRadians(cLat - latitude)
            val dLon = Math.toRadians(cLon - longitude)
            val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(latitude)) * Math.cos(Math.toRadians(cLat)) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2)
            6371.0 * 2 * Math.asin(Math.sqrt(a))
        }

    /** Day of year this cell reaches peak colour, before species. */
    fun peakDayOfYear(latitude: Double, longitude: Double, elevationM: Int?): Double =
        INTERCEPT + LAT_DAYS_PER_DEGREE * latitude +
            ELEV_DAYS_PER_METRE * (elevationM?.toDouble() ?: 0.0) +
            COAST_DAYS_PER_KM * coastDistanceKm(latitude, longitude)

    /** Species-adjusted peak day of year. */
    fun peakDayOfYear(cell: CellInput): Double =
        peakDayOfYear(cell.latitude, cell.longitude, cell.elevationM) +
            if (ForestTypeGroup.forCode(cell.forestTypeGroup) == ForestTypeGroup.ASPEN_BIRCH) {
                ASPEN_BIRCH_SHIFT_DAYS
            } else {
                0.0
            }

    /** Progression 0-100 on [day], for a cell peaking at [peakDoy]. */
    fun progressionOn(day: LocalDate, peakDoy: Double): Double {
        val t50 = peakDoy - PEAK_OFFSET * WIDTH_DAYS
        val x = (day.dayOfYear - t50) / WIDTH_DAYS
        return (100.0 / (1.0 + Math.exp(-x))).coerceIn(0.0, 100.0)
    }

    fun score(
        cell: CellInput,
        days: List<DayInput>,
        target: LocalDate,
        normalPrecipMm: Double? = null,
        precipFrom: LocalDate? = null,
    ): FoliageScore {
        val peakDoy = peakDayOfYear(cell)
        val progression = progressionOn(target, peakDoy)

        // Weather no longer sets timing. It still sets how good the display
        // looks, which is a separate question and the one it was always
        // better at answering.
        val upTo = days.filter { !it.day.isAfter(target) }
        val recent = upTo.takeLast(14)
        val diurnal = recent.mapNotNull { d ->
            val hi = d.tmaxC
            val lo = d.tminC
            if (hi != null && lo != null) hi - lo else null
        }
        val meanDiurnal = if (diurnal.isEmpty()) 10.0 else diurnal.average()
        val hardFreeze = upTo.any { (it.tminC ?: 99.0) <= PhenologyModel.HARD_FREEZE_C }
        val species = ForestTypeGroup.forCode(cell.forestTypeGroup)

        val observedPrecip = upTo
            .filter { precipFrom == null || !it.day.isBefore(precipFrom) }
            .mapNotNull { it.precipMm }
            .sum()
        val droughtStress = normalPrecipMm
            ?.takeIf { it > 0 }
            ?.let { (1.0 - observedPrecip / it).coerceIn(0.0, 1.0) }
            ?: 0.0

        val intensity = (50.0 + 4.0 * (meanDiurnal.coerceAtMost(PhenologyModel.DIURNAL_CAP_C) - 8.0))
            .let { it * (1 - 0.4 * droughtStress) }
            .let { if (hardFreeze) it * PhenologyModel.HARD_FREEZE_INTENSITY_FACTOR else it }
            .let {
                if (species == ForestTypeGroup.CONIFER) it * CONIFER_VIVIDNESS else it
            }
            .coerceIn(0.0, 100.0)

        val peakDate = LocalDate.ofYearDay(target.year, peakDoy.toInt().coerceIn(1, 365))
        val month = peakDate.month.name.lowercase().replaceFirstChar { it.uppercase() }

        return FoliageScore(
            progression = progression,
            stage = PhenologyModel.stageOf(progression),
            intensity = intensity,
            confidence = PhenologyModel.confidenceOf(upTo),
            factors = listOf(
                Factor(
                    "Peak colour", peakDoy, "around ${peakDate.dayOfMonth} $month",
                    "This spot is expected to be at its best around " +
                        "${peakDate.dayOfMonth} $month, going on how far north it is and " +
                        "how high it sits.",
                ),
                Factor(
                    "Height", (cell.elevationM ?: 0).toDouble(), "brings it forward",
                    "About ${cell.elevationM ?: 0} m up. Higher ground turns " +
                        "earlier -- roughly a day for every 110 metres you climb.",
                ),
                Factor(
                    "Forest type",
                    if (species == ForestTypeGroup.ASPEN_BIRCH) ASPEN_BIRCH_SHIFT_DAYS else 0.0,
                    // Surveyed-and-empty is a different statement from
                    // not-surveyed, and conflating them tells someone standing
                    // in a suburb that nobody has looked, when somebody looked
                    // and found parkland. Both score at the baseline; only one
                    // is a gap in the data.
                    when {
                        cell.forestTypeGroup == null -> "not surveyed"
                        cell.forestTypeGroup == 0 -> "little forest"
                        species == ForestTypeGroup.ASPEN_BIRCH -> "turns early"
                        else -> "the usual timing"
                    },
                    when {
                        cell.forestTypeGroup == null ->
                            "The trees here have not been surveyed, so this assumes a maple and " +
                                "beech wood, which is the commonest kind in New England."
                        cell.forestTypeGroup == 0 ->
                            "Not much continuous forest here -- open ground, farmland, water or " +
                                "town. Any colour will come from scattered trees rather than a " +
                                "hillside."
                        species == ForestTypeGroup.CONIFER ->
                            "Mostly evergreens, which do not put on much of an autumn display."
                        species == ForestTypeGroup.ASPEN_BIRCH ->
                            "Mostly ${species.label}. These turn about a week before the maples " +
                                "and drop their leaves quickly once they do."
                        species == null ->
                            "The trees here were surveyed but are not a kind this forecast has " +
                                "measured, so it assumes the usual maple and beech timing."
                        else ->
                            "Mostly ${species.label}, which is the timing this forecast is " +
                                "built around."
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

    /**
     * How vivid a mostly-evergreen stand gets, against a broadleaf one.
     * Carried over unchanged; it was never part of what was wrong.
     */
    const val CONIFER_VIVIDNESS = 0.35
}
