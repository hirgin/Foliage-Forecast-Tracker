package com.foliage.validate

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Reading NPN's intensity buckets, which is the whole hinge of the validation:
 * every accuracy figure this project can claim depends on turning "50-74%"
 * into a number the model can be compared against.
 */
class ColoredLeavesTest {

    @Test
    fun `each bucket becomes its midpoint`() {
        // A midpoint because it is the least-wrong single number for a range.
        // Taking either edge would build a systematic bias into every
        // comparison, in one direction or the other, and that bias would then
        // be indistinguishable from model error.
        assertEquals(14.5, ColoredLeaves.percentColored("5-24%"))
        assertEquals(37.0, ColoredLeaves.percentColored("25-49%"))
        assertEquals(62.0, ColoredLeaves.percentColored("50-74%"))
        assertEquals(84.5, ColoredLeaves.percentColored("75-94%"))
    }

    @Test
    fun `the open-ended buckets do not claim more than the observer did`() {
        // "Less than 5%" is not zero: the volunteer saw colour. "95% or more"
        // is not 100: a canopy is rarely completely turned, and the bucket
        // makes no such claim.
        val least = ColoredLeaves.percentColored("Less than 5%")!!
        val most = ColoredLeaves.percentColored("95% or more")!!
        assertTrue(least > 0.0, "some colour was seen, so it cannot be zero")
        assertTrue(least < 5.0)
        assertTrue(most > 95.0)
        assertTrue(most < 100.0, "a bucket that starts at 95 does not assert a bare canopy")
    }

    @Test
    fun `punctuation drift does not lose an observation`() {
        // NPN's labels have varied over the years. Dropping observations over a
        // stray space would quietly shrink the only ground truth this project
        // has, and nothing would report that it had happened.
        assertEquals(37.0, ColoredLeaves.percentColored("25 - 49 %"))
        assertEquals(62.0, ColoredLeaves.percentColored("  50-74%  "))
    }

    @Test
    fun `an unusable intensity is null, never a guess`() {
        // An observation without an intensity still records that colour was
        // present, which is a weaker claim than a percentage. Promoting it to
        // one would be inventing data in the one place this project is
        // measuring itself.
        assertNull(ColoredLeaves.percentColored(null))
        assertNull(ColoredLeaves.percentColored(""))
        assertNull(ColoredLeaves.percentColored("lots"))
    }

    @Test
    fun `error is signed so bias is visible, not just size`() {
        // The distinction that mattered when the model was refitted: mean
        // absolute error fell only slightly while the signed error went from
        // "late everywhere" to centred. A magnitude alone hides that.
        assertEquals(23.0, ColoredLeaves.error(60.0, "25-49%")!!, 1e-9)
        assertEquals(-23.0, ColoredLeaves.error(14.0, "25-49%")!!, 1e-9)
    }

    @Test
    fun `no comparison is possible without both halves`() {
        assertNull(ColoredLeaves.error(null, "25-49%"))
        assertNull(ColoredLeaves.error(50.0, null))
    }
}
