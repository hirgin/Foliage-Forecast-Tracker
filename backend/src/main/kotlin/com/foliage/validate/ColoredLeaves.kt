package com.foliage.validate

/**
 * Reading USA-NPN's leaf-colour observations as a number this model can be
 * measured against.
 *
 * **Why this exists.** Everything else in this project checks the model against
 * itself. `docs/model.md` says so plainly: "It has not been validated. There is
 * no ground-truth dataset of actual peak dates, so accuracy is unknown and
 * unclaimed." The one fitted parameter is calibrated against published tourism
 * windows, which is agreement with somebody else's estimate rather than with a
 * tree.
 *
 * The USA National Phenology Network is the exception: volunteers record what a
 * named plant at known coordinates actually looked like on a given day. Its
 * "Colored leaves" phenophase carries an intensity bucket -- how much of the
 * canopy had turned -- and that is directly comparable to this model's
 * progression, on the same day, in the same hexagon.
 *
 * **What it cannot be.** These are individual plants, not stands: one red maple
 * in a Vermont front garden against a 3 km hexagon averaging a whole
 * neighbourhood of forest. Expect scatter that is real disagreement between a
 * plant and its landscape rather than model error, and read the aggregate
 * rather than any single pair.
 */
object ColoredLeaves {

    /** The phenophase that carries a colour intensity. See getPhenophases. */
    const val PHENOPHASE_COLORED_LEAVES = 498

    /**
     * Buckets NPN volunteers choose between, as the midpoint of each range.
     *
     * A midpoint, because that is the least-wrong single number for a bucket
     * and because the alternative -- treating "50-74%" as 50 or as 74 -- builds
     * a systematic bias into every comparison in one direction or the other.
     *
     * The open-ended buckets are the two that need a judgement. "Less than 5%"
     * becomes 2.5 rather than 0, since the observer saw *some* colour and zero
     * would say they saw none. "95% or more" becomes 97.5 rather than 100, for
     * the same reason in reverse: a canopy is rarely completely turned, and
     * 100 is a claim the bucket does not make.
     */
    private val BUCKETS = mapOf(
        "less than 5%" to 2.5,
        "5-24%" to 14.5,
        "25-49%" to 37.0,
        "50-74%" to 62.0,
        "75-94%" to 84.5,
        "95% or more" to 97.5,
    )

    /**
     * The share of the canopy coloured, from NPN's bucket label.
     *
     * Null rather than a guess when the label is unrecognised or absent. An
     * observation without an intensity still records that colour was present,
     * which is a weaker claim than a percentage and should not be silently
     * promoted into one.
     */
    fun percentColored(intensityValue: String?): Double? {
        val key = intensityValue?.trim()?.lowercase() ?: return null
        BUCKETS[key]?.let { return it }
        // NPN has varied the punctuation over the years ("5-24%" against
        // "5-24 %"), so fall back to matching on the digits rather than
        // dropping observations over a space.
        val digits = Regex("""(\d+)\s*-\s*(\d+)""").find(key)
        if (digits != null) {
            val lo = digits.groupValues[1].toDouble()
            val hi = digits.groupValues[2].toDouble()
            return (lo + hi) / 2
        }
        return null
    }

    /**
     * How far a modelled progression is from what was observed, in percentage
     * points, or null when the observation carries no usable intensity.
     */
    fun error(modelled: Double?, intensityValue: String?): Double? {
        val observed = percentColored(intensityValue) ?: return null
        if (modelled == null) return null
        return modelled - observed
    }
}
