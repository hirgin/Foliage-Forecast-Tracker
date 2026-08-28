package com.foliage.ingest

import com.foliage.grid.H3Grid
import com.foliage.grid.LonLat
import com.foliage.ingest.audit.IngestRunRecorder
import com.foliage.ingest.terrain.BoundarySource
import com.foliage.ingest.terrain.CanopySource
import com.foliage.ingest.terrain.ElevationSource
import com.foliage.ingest.terrain.StateBoundary
import com.foliage.persistence.CellRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.springframework.jdbc.core.JdbcTemplate

/**
 * What a resumed region bootstrap decides to skip.
 *
 * This guards a failure that already happened. Terrain sources degrade rather
 * than abort -- a tile that times out after its retries leaves its cells
 * unsampled and the run carries on -- so a state can be written, counted, and
 * still be full of holes. The first CONUS load finished with Oregon and
 * California each missing ~23% of their canopy samples, to a service that had
 * been under load for two hours.
 *
 * A resume check that only counts rows sees those states as done and skips
 * them, which makes the holes *permanent*: every later run skips them too.
 * These tests assert the distinction between "has rows" and "is complete".
 *
 * Fakes rather than mocks, matching the rest of the suite: the repository and
 * recorder are open because the Spring plugin opens them, and the JdbcTemplate
 * handed to them is never touched.
 */
class GridBootstrapResumeTest {

    /** Reaching a state's boundary means it was attempted rather than skipped. */
    private class Attempted(state: String) : RuntimeException("attempted $state")

    private class FakeCells(
        private val counts: Map<String, Long>,
        private val gaps: Map<String, Long>,
    ) : CellRepository(JdbcTemplate()) {
        override fun countByStateName(stateName: String) = counts[stateName] ?: 0L
        override fun countIncompleteByStateName(stateName: String) = gaps[stateName] ?: 0L
    }

    /** Records nothing; the audit trail is not what is under test here. */
    private class FakeAudit : IngestRunRecorder(JdbcTemplate()) {
        override fun start(source: String, job: String) = 1L
        override fun succeed(id: Long, rowsWritten: Long) = Unit
        override fun fail(id: Long, rowsWritten: Long, error: Throwable) = Unit
    }

    /**
     * Throws on every state, so a state that is *not* skipped lands in
     * `statesFailed`. That is how these tests tell "attempted" from "skipped"
     * without running a real bootstrap against the network.
     */
    private object NeverResolves : BoundarySource {
        override fun stateBoundary(name: String): StateBoundary = throw Attempted(name)
    }

    private object NoCanopy : CanopySource {
        override fun sample(points: List<LonLat>) = points.map { null }
    }

    private object NoElevation : ElevationSource {
        override fun elevation(points: List<LonLat>) = points.map { null }
    }

    private fun bootstrap(
        cellsByState: Map<String, Long> = emptyMap(),
        gapsByState: Map<String, Long> = emptyMap(),
    ) = GridBootstrap(
        grid = H3Grid(),
        boundaries = NeverResolves,
        canopy = NoCanopy,
        elevation = NoElevation,
        cells = FakeCells(cellsByState, gapsByState),
        audit = FakeAudit(),
        resolution = 6,
    )

    @Test
    fun `skips a state that is complete`() {
        val result = bootstrap(cellsByState = mapOf("Vermont" to 649L))
            .bootstrapRegion("new-england")

        assertTrue("Vermont" in result.statesSkipped)
        assertTrue("Vermont" !in result.statesFailed.keys, "a complete state must not be re-sampled")
    }

    @Test
    fun `re-samples a state that has rows but missing terrain`() {
        // The Oregon and California case: rows present, terrain incomplete.
        val result = bootstrap(
            cellsByState = mapOf("Maine" to 2366L),
            gapsByState = mapOf("Maine" to 544L),
        ).bootstrapRegion("new-england")

        assertTrue("Maine" !in result.statesSkipped, "a state with holes must not be skipped")
        assertTrue("Maine" in result.statesFailed.keys, "a state with holes must be attempted")
    }

    @Test
    fun `attempts every state when the grid is empty`() {
        val result = bootstrap().bootstrapRegion("new-england")

        assertEquals(emptyList(), result.statesSkipped)
        assertEquals(6, result.statesFailed.size)
    }

    @Test
    fun `force re-samples even a complete state`() {
        val result = bootstrap(cellsByState = mapOf("Vermont" to 649L))
            .bootstrapRegion("new-england", force = true)

        assertTrue("Vermont" !in result.statesSkipped)
        assertTrue("Vermont" in result.statesFailed.keys)
    }

    @Test
    fun `one failing state does not abandon the rest`() {
        // What makes a national bootstrap resumable rather than all-or-nothing:
        // every state is still attempted after the first one throws.
        val result = bootstrap().bootstrapRegion("new-england")

        assertEquals(6, result.statesFailed.size)
        assertEquals(0, result.statesBootstrapped)
    }
}
