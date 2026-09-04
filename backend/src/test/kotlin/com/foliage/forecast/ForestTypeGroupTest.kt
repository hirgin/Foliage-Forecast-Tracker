package com.foliage.forecast

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The species term, which exists to remove the model's largest known residual.
 *
 * The values here are measured rather than chosen, so the tests guard the
 * properties that make the term safe to roll out over a grid that is only
 * partly sampled -- above all that an unsampled cell keeps scoring exactly as
 * it did before this existed.
 */
class ForestTypeGroupTest {

    @Test
    fun `the places that revealed the residual map to the groups that explain it`() {
        // These three codes were read from the USFS BIGMAP raster at Ely,
        // Stowe and Litchfield -- the same places whose errors are recorded in
        // docs/model.md, and a dataset the model has never seen.
        assertEquals(ForestTypeGroup.ASPEN_BIRCH, ForestTypeGroup.forCode(900))
        assertEquals(ForestTypeGroup.MAPLE_BEECH_BIRCH, ForestTypeGroup.forCode(800))
        assertEquals(ForestTypeGroup.OAK, ForestTypeGroup.forCode(500))
    }

    @Test
    fun `aspen turns earlier than maple`() {
        // The direction is the whole claim for aspen. Getting a magnitude
        // slightly wrong costs days; getting the sign wrong would make the map
        // worse than the maple-everywhere assumption it replaces.
        val aspen = ForestTypeGroup.ASPEN_BIRCH.sPeakMultiplier
        val maple = ForestTypeGroup.MAPLE_BEECH_BIRCH.sPeakMultiplier
        assertTrue(aspen < maple, "aspen-birch needs less cooling to turn")
    }

    @Test
    fun `maple-beech is exactly the baseline`() {
        // Not approximately. The model was fitted on maple-beech stands, so
        // introducing this term must leave them bit-for-bit unchanged --
        // otherwise the species work silently re-times the whole Northeast.
        assertEquals(1.0, ForestTypeGroup.MAPLE_BEECH_BIRCH.sPeakMultiplier)
        assertEquals(1.0, ForestTypeGroup.multiplierFor(800))
    }

    @Test
    fun `an unsampled cell scores exactly as it did before this existed`() {
        // The property that lets this ship against a partly sampled grid. 141k
        // cells cannot be sampled in one job, and a rollout that changed cells
        // nobody had measured would be impossible to attribute afterwards.
        assertEquals(1.0, ForestTypeGroup.multiplierFor(null))
        assertEquals(1.0, ForestTypeGroup.multiplierFor(0), "0 is the raster's no-data value")
        assertEquals(1.0, ForestTypeGroup.multiplierFor(999), "999 is non-stocked ground")
        assertEquals(1.0, ForestTypeGroup.multiplierFor(-1))
        assertNull(ForestTypeGroup.forCode(null))
        assertNull(ForestTypeGroup.forCode(0))
    }

    @Test
    fun `the aspen multiplier matches what the reference places measured`() {
        // Ely 0.57, Duluth 0.59, Marquette 0.67 -- the mean is 0.61, and this
        // pins it so that editing the constant without revisiting the
        // measurement fails the build.
        assertEquals(0.61, ForestTypeGroup.ASPEN_BIRCH.sPeakMultiplier, 1e-9)
    }

    @Test
    fun `oak sits at the baseline once latitude is modelled`() {
        // It shipped at 1.6, damped from 2.61 measured at Litchfield, because
        // oak really does hold its leaves later than maple. Refitting it
        // jointly with the photoperiod floor puts it at exactly 1.0.
        //
        // The multiplier had been standing in for something else. Litchfield
        // is oak country and also three degrees south of Stowe, and a model
        // with no way to say "further south turns later" had only the species
        // term to explain the gap. Holding oak above 1.0 now just makes oak
        // country late again.
        assertEquals(1.0, ForestTypeGroup.OAK.sPeakMultiplier)
    }

    @Test
    fun `individual forest types resolve to their group`() {
        // The raster returns forest *types*, not only groups. A national
        // survey came back holding 841, 402, 128 and some two hundred others,
        // and matching group codes alone left 8.5% of the grid reading as
        // "not a kind this forecast has measured".
        assertEquals(ForestTypeGroup.MAPLE_BEECH_BIRCH, ForestTypeGroup.forCode(841))
        assertEquals(ForestTypeGroup.OAK, ForestTypeGroup.forCode(402))
        assertEquals(ForestTypeGroup.OAK, ForestTypeGroup.forCode(510))
        assertEquals(ForestTypeGroup.ASPEN_BIRCH, ForestTypeGroup.forCode(904))
        assertEquals(ForestTypeGroup.CONIFER, ForestTypeGroup.forCode(128))
        assertEquals(ForestTypeGroup.ELM_ASH_COTTONWOOD, ForestTypeGroup.forCode(703))
    }

    @Test
    fun `pinyon and juniper are conifers`() {
        // 14,471 cells -- 6.7% of the surveyed grid, and the single largest
        // gap in the first mapping. A group code, not an exotic type code,
        // that was simply left out of the list.
        assertEquals(ForestTypeGroup.CONIFER, ForestTypeGroup.forCode(180))
        assertEquals(ForestTypeGroup.CONIFER, ForestTypeGroup.forCode(184))
        assertEquals(ForestTypeGroup.CONIFER, ForestTypeGroup.forCode(170))
    }

    @Test
    fun `a type code never resolves to a group above it`() {
        // FIA nests types inside the group whose code is at or below them.
        // Resolving upward would put an oak type in an aspen group and shift
        // its timing by weeks in the wrong direction.
        for (code in 100..998) {
            val group = ForestTypeGroup.groupCodeFor(code) ?: continue
            assertTrue(group <= code, "code $code resolved up to group $group")
        }
    }

    @Test
    fun `western hardwoods are recognised rather than treated as unsurveyed`() {
        // Real forest with no measured multiplier. It scores at the baseline
        // either way, but the map should say it was surveyed.
        assertEquals(ForestTypeGroup.OTHER_HARDWOOD, ForestTypeGroup.forCode(970))
        assertEquals(ForestTypeGroup.OTHER_HARDWOOD, ForestTypeGroup.forCode(940))
        assertEquals(1.0, ForestTypeGroup.multiplierFor(970))
    }

    @Test
    fun `no code is claimed by two groups`() {
        // A code matching two entries would make forCode order-dependent, and
        // the ordering that decided it would be the declaration order of an
        // enum -- which is not a phenological argument.
        val seen = mutableSetOf<Int>()
        for (group in ForestTypeGroup.entries) {
            for (code in group.codes) {
                assertTrue(seen.add(code), "code $code is claimed by more than one group")
            }
        }
    }

    @Test
    fun `conifers do not get an invented multiplier`() {
        // They produce no autumn colour, so any value here would be a claim
        // about a display that does not happen. Whether to grey them out is a
        // rendering decision, made elsewhere.
        assertEquals(1.0, ForestTypeGroup.CONIFER.sPeakMultiplier)
    }
}
