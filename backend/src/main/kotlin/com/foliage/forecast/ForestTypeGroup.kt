package com.foliage.forecast

import com.foliage.ingest.terrain.CellSampling

/**
 * FIA forest type groups, and how much earlier or later each one turns.
 *
 * The model's largest identifiable residual, and until now the only structured
 * one left. [CoolingDegreeDayModel] describes a maple-beech stand everywhere,
 * so every forest that is not maple-beech is wrong by a fixed amount that no
 * weather term can explain. Measured against reference places, the aspen-birch
 * north ran 7 to 12 days late and oak-hickory Litchfield 19 days early --
 * errors that track species cleanly and geography not at all.
 *
 * **The multiplier scales [CoolingDegreeDayModel.S_PEAK], not a date.** A fixed
 * day offset would be wrong everywhere except where it was fitted: the same
 * shift in days costs very different amounts of cooling in Minnesota and
 * Georgia, and the whole point of a degree-day model is that timing follows
 * accumulation rather than the calendar. Scaling the threshold keeps that
 * property -- an aspen stand needs less cooling to turn, wherever it stands.
 *
 * **Measured, not assumed.** Each multiplier is the ratio of accumulated
 * cooling at the date a place actually peaks to the accumulation at the date
 * the model currently peaks it, read from the same weather the model scores:
 *
 * | Place | Group | Model | Actual | Ratio |
 * |---|---|---|---|---|
 * | Ely, MN | aspen/birch | 3 Oct | 21 Sep | 0.57 |
 * | Duluth, MN | aspen/birch | 8 Oct | 29 Sep | 0.59 |
 * | Marquette, MI | aspen/birch | 8 Oct | 1 Oct | 0.67 |
 * | Stowe, VT | maple/beech | 30 Sep | 30 Sep | 1.00 |
 * | Litchfield, CT | oak/hickory | 3 Oct | 22 Oct | 2.61 |
 *
 * **[OAK] rests on a single place and is damped because of it.** 2.61 is what
 * one town measured, and applying it whole would push every oak forest in the
 * country into November on the authority of Litchfield, Connecticut. It is
 * held at 1.6 until it can be measured the way the aspen figure was, across
 * several places. Being visibly under-corrected is the right failure here:
 * oaks genuinely do turn late, and the honest half-step is still a large
 * improvement on treating them as maples.
 */
enum class ForestTypeGroup(
    /** FIA forest type group codes that map to this behaviour. */
    val codes: Set<Int>,
    /** Multiplier on [CoolingDegreeDayModel.S_PEAK]. Below 1 turns earlier. */
    val sPeakMultiplier: Double,
    val label: String,
) {
    /** Aspen and birch, which turn well before maple and drop fast. */
    ASPEN_BIRCH(setOf(900, 910), 0.61, "aspen-birch"),

    /** The stand the model was written for; the baseline by definition. */
    MAPLE_BEECH_BIRCH(setOf(800), 1.0, "maple-beech-birch"),

    /** Oak and hickory, the latest-turning broadleaf forests here. */
    OAK(setOf(400, 500, 600, 920), 1.6, "oak"),

    /**
     * Elm, ash and cottonwood. Between maple and oak, and left at the baseline
     * rather than given an invented figure -- there is no measurement for it,
     * and a made-up multiplier would be indistinguishable from a fitted one to
     * anyone reading this later.
     */
    ELM_ASH_COTTONWOOD(setOf(700), 1.0, "elm-ash-cottonwood"),

    /**
     * Conifers, which do not put on an autumn display at all.
     *
     * Given the baseline rather than an extreme value on purpose. These cells
     * are largely excluded by the canopy-and-colour logic upstream, and a
     * multiplier here would be a claim about colour in a forest that does not
     * produce any. Whether to grey them out is a rendering decision, not a
     * phenology one.
     */
    CONIFER(
        setOf(100, 120, 140, 160, 170, 180, 200, 220, 240, 260, 280, 300, 320, 340, 360, 370, 380, 390),
        1.0,
        "conifer",
    ),

    /**
     * Western and exotic hardwoods, at the baseline for want of a measurement.
     *
     * Real forest that this project has no figure for. Kept as a group rather
     * than left unrecognised so the map can say "surveyed, but not a kind this
     * forecast has measured" instead of implying nobody looked.
     */
    OTHER_HARDWOOD(setOf(940, 950, 960, 970, 980, 990), 1.0, "other hardwood"),
    ;

    companion object {

        /**
         * Every FIA forest type group code, ascending.
         *
         * The raster does not return only group codes. A national survey came
         * back holding 841, 402, 128, 257 and some two hundred others: these
         * are individual forest *types*, and FIA nests them inside groups --
         * 841 is a maple-beech-birch type, 402 an oak-pine one. Matching group
         * codes alone left 8.5% of the surveyed grid reading as "not a kind
         * this forecast has measured", including 14,471 cells of pinyon and
         * juniper whose group code this simply did not list.
         */
        private val GROUP_CODES = intArrayOf(
            100, 120, 140, 160, 170, 180, 200, 220, 240, 260, 280, 300, 320, 340,
            360, 370, 380, 390, 400, 500, 600, 700, 800, 900, 910, 920, 940, 950,
            960, 970, 980, 990,
        )

        /**
         * The group a raw raster value belongs to.
         *
         * A type code belongs to the highest group code at or below it, which
         * is how FIA numbers them. Where that guess goes wrong it goes wrong
         * within the softwoods -- 257 is not a documented type, and lands on
         * western white pine rather than fir-spruce -- and both are conifers
         * carrying the same multiplier, so the timing is unaffected either way.
         */
        fun groupCodeFor(code: Int?): Int? {
            if (code == null || code < GROUP_CODES.first()) return null
            if (code == CellSampling.NON_STOCKED) return null
            var group: Int? = null
            for (g in GROUP_CODES) {
                if (g <= code) group = g else break
            }
            return group
        }

        /** Group for an FIA code, or null when it is unknown or non-forest. */
        fun forCode(code: Int?): ForestTypeGroup? {
            val group = groupCodeFor(code) ?: return null
            return entries.firstOrNull { group in it.codes }
        }

        /**
         * The multiplier to apply for a cell's stored code.
         *
         * Falls back to the maple-beech baseline of 1.0 for an unsampled cell,
         * an unrecognised code, or non-stocked ground. That fallback is what
         * lets this ship against a partly sampled grid: a cell without a type
         * scores exactly as it did before this existed, so rolling the term
         * out cannot silently change forests nobody has measured yet.
         */
        fun multiplierFor(code: Int?): Double = forCode(code)?.sPeakMultiplier ?: 1.0
    }
}
