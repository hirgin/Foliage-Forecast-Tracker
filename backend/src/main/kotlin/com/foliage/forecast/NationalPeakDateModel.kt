package com.foliage.forecast

import java.time.LocalDate

/**
 * The rest of the country, at arm's length.
 *
 * [PeakDateModel] is fitted against field observations and covers six New
 * England states. This covers the other forty-two, and it is deliberately a
 * separate object rather than a widening of that one, because the two are not
 * the same kind of claim. New England rests on twelve seasons of Maine Forest
 * Service reports and five years of New Hampshire sightings. This rests on a
 * tourism site's county prediction map -- nine distinct dates for the whole
 * country, and four to six days early against Maine's own foresters wherever
 * the two can be compared.
 *
 * Keeping them apart is the point. A single model covering both would have one
 * accuracy figure, and it would be the average of a good one and a weak one,
 * which describes neither. Here New England is 2.1 days on the coast and this
 * is 4.1 nationally, and the map can say so.
 *
 * **Every cell it scores is worse than every cell PeakDateModel scores**, and
 * [CONFIDENCE_FACTOR] carries that to the map rather than leaving it in a
 * comment.
 */
object NationalPeakDateModel {

    /**
     * Fitted over 2,820 counties: mean absolute error 4.1 days, R^2 0.85,
     * and 53% landing inside the reference's own weekly resolution.
     *
     * Elevation is fitted here rather than pinned as it is in [PeakDateModel],
     * and it survives: nationally there is enough spread between latitude,
     * longitude and height that the three do not collapse into each other the
     * way they do in New England, where the coast runs diagonally. The
     * coefficient lands at -0.0130 days per metre against the -0.0206 physics
     * gives, which is the right sign and the right order.
     *
     * Adding it is what made this usable. Without elevation the mountain west
     * was two to three weeks late -- Utah +19.8 days, Nevada +15.9, New
     * Mexico +14.6 -- because those states are high and nothing represented
     * it. With it: New Mexico +0.2, Wyoming -1.6, Montana -0.6.
     */
    const val INTERCEPT = 342.13248
    const val LAT_DAYS_PER_DEGREE = -2.28771
    const val ELEV_DAYS_PER_METRE = -0.01302

    /**
     * Days per degree of longitude, and the term this model is worst at.
     *
     * In [PeakDateModel] longitude is a maritime proxy that works because the
     * model is confined to New England, where east means the cold Gulf of
     * Maine. Nationally it cannot mean that: east is the Atlantic on one side
     * of the continent and away from the Pacific on the other, and one
     * coefficient cannot be both.
     *
     * So it splits the difference at -0.578 and serves neither coast well.
     * Oregon runs +9.7 days and Washington +7.5 -- the Pacific maritime effect
     * that Downeast Maine gets right and this gets backwards. Regional
     * maritime terms are the fix and are not done.
     */
    const val LON_DAYS_PER_DEGREE = -0.57752

    /**
     * What a score from this model is worth, against one from [PeakDateModel].
     *
     * Confidence already carries how much of a cell's weather is measured
     * rather than climatological. This multiplies that by how much the
     * *model* is worth where the cell sits, which is a different question and
     * was not represented at all: a Utah hexagon and a Bar Harbor hexagon drew
     * at the same strength while one was fitted against foresters' reports and
     * the other against a prediction map.
     *
     * 0.6 is a judgement, not a measurement, and is written as such. It is
     * roughly the ratio of the two mean errors -- 2.1 days against 4.1 -- and
     * the map draws it as lower alpha, so the six observed states read solid
     * and everything else visibly provisional.
     */
    const val CONFIDENCE_FACTOR = 0.6

    /** Day of year this cell reaches peak colour, before species. */
    fun peakDayOfYear(latitude: Double, longitude: Double, elevationM: Int?): Double =
        INTERCEPT + LAT_DAYS_PER_DEGREE * latitude + LON_DAYS_PER_DEGREE * longitude +
            ELEV_DAYS_PER_METRE * (elevationM?.toDouble() ?: 0.0)

    /** Species-adjusted, on the same terms as [PeakDateModel]. */
    fun peakDayOfYear(cell: CellInput): Double =
        peakDayOfYear(cell.latitude, cell.longitude, cell.elevationM) +
            if (ForestTypeGroup.forCode(cell.forestTypeGroup) == ForestTypeGroup.ASPEN_BIRCH) {
                PeakDateModel.ASPEN_BIRCH_SHIFT_DAYS
            } else {
                0.0
            }

    /**
     * Scored exactly as [PeakDateModel] does, differing only in the date and
     * the confidence. The curve, the species shift, the intensity terms and
     * the explanation are shared deliberately -- two models that drew
     * differently as well as timing differently would make the seam between
     * New England and everywhere else a visual one, and there is enough of a
     * seam already.
     */
    fun score(
        cell: CellInput,
        days: List<DayInput>,
        target: LocalDate,
        normalPrecipMm: Double? = null,
        precipFrom: LocalDate? = null,
    ): FoliageScore {
        val base = PeakDateModel.scoreAtPeak(
            cell, days, target, peakDayOfYear(cell), normalPrecipMm, precipFrom,
        )
        return base.copy(confidence = base.confidence * CONFIDENCE_FACTOR)
    }
}
