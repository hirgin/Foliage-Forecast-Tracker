package com.foliage.validate

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The metric that decides whether the model's timing is right.
 *
 * It exists because the obvious comparison is misleading: modelled progression
 * reaches 100 at a fully turned stand, while NPN's "percent of canopy coloured"
 * plateaus near 75, so a model with perfect timing still shows a large and
 * growing signed error late in the season. Three least-squares fits against
 * that error all "improved" the model by pushing peak into late October, which
 * is flatly wrong for New England.
 *
 * These tests pin the one property that makes the metric worth trusting: it is
 * blind to scale, and not blind to order.
 */
class RankCorrelationTest {

    @Test
    fun `a constant offset does not count as error`() {
        // The whole point. This is exactly the model-versus-NPN situation: the
        // same season, read on two different scales.
        val observed = listOf(10.0, 25.0, 40.0, 60.0, 75.0)
        val modelled = observed.map { it + 20.0 }
        assertEquals(1.0, RankCorrelation.spearman(observed, modelled)!!, 1e-9)
    }

    @Test
    fun `compression at the top does not count as error either`() {
        // Observations plateau while progression runs on to 100. Order is
        // preserved, so agreement should be total.
        val observed = listOf(10.0, 25.0, 40.0, 60.0, 72.0, 74.0)
        val modelled = listOf(13.0, 47.0, 74.0, 95.0, 99.0, 100.0)
        assertEquals(1.0, RankCorrelation.spearman(observed, modelled)!!, 1e-9)
    }

    @Test
    fun `a model that orders the season backwards is caught`() {
        val observed = listOf(10.0, 25.0, 40.0, 60.0, 75.0)
        val modelled = listOf(90.0, 70.0, 50.0, 30.0, 10.0)
        assertEquals(-1.0, RankCorrelation.spearman(observed, modelled)!!, 1e-9)
    }

    @Test
    fun `getting the middle of the season out of order costs something`() {
        val observed = listOf(10.0, 25.0, 40.0, 60.0, 75.0)
        val jumbled = listOf(10.0, 60.0, 25.0, 40.0, 75.0)
        val rho = RankCorrelation.spearman(observed, jumbled)!!
        assertTrue(rho < 1.0, "a real ordering mistake must not score perfectly")
        assertTrue(rho > 0.0, "but it is still broadly the right shape")
    }

    @Test
    fun `tied observations share a rank rather than input order`() {
        // NPN reports six buckets, so ties are the common case, not the edge
        // case. Ranking them by the order they happened to arrive in would add
        // noise in proportion to how coarse the source is.
        val ranks = RankCorrelation.averagedRanks(listOf(5.0, 5.0, 5.0, 9.0))
        assertEquals(2.0, ranks[0], 1e-9)
        assertEquals(2.0, ranks[1], 1e-9)
        assertEquals(2.0, ranks[2], 1e-9)
        assertEquals(4.0, ranks[3], 1e-9)

        // And order of arrival must not change the answer.
        val a = RankCorrelation.spearman(listOf(5.0, 5.0, 9.0), listOf(1.0, 2.0, 3.0))!!
        val b = RankCorrelation.spearman(listOf(5.0, 5.0, 9.0), listOf(2.0, 1.0, 3.0))!!
        assertEquals(a, b, 1e-9)
    }

    @Test
    fun `no ordering to agree about is null, not a number`() {
        // Every value tied. Reporting 0.0 here would read as "the model is
        // uncorrelated with reality", which is a finding; the truth is that
        // nothing was measured.
        assertNull(RankCorrelation.spearman(listOf(5.0, 5.0, 5.0), listOf(1.0, 2.0, 3.0)))
        assertNull(RankCorrelation.spearman(listOf(1.0), listOf(1.0)))
        assertNull(RankCorrelation.spearman(listOf(1.0, 2.0), listOf(1.0)))
    }
}
