package com.foliage.validate

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * How far apart neighbouring hexagons peak, which is the property this map
 * exists to show.
 *
 * Peak *dates* can be right while the map is wrong. Three model versions have
 * been fitted to published windows, improved on date error, and flattened the
 * country into a smooth latitude ramp on the way -- the texture that elevation
 * and maritime effects put into a 3 km grid does not appear in a state median
 * at all, so nothing in the existing diagnostics could see it going. The
 * figures that caught it each time were measured by hand and then written into
 * a comment, which is why CLAUDE.md's benchmark cites a Vermont spread of 14
 * days that no code in this repository can reproduce.
 *
 * This is that measurement, given a name. Compare a candidate against a
 * baseline *measured the same way* rather than against the remembered number:
 * the absolute value depends on choices made here -- the disk radius, the
 * edge rule, the percentile -- and only a like-for-like comparison means
 * anything.
 */
data class LocalSpreadResult(
    val stateFips: String?,
    /** Disk radius in rings. 4 gives 61 cells, the "sixty neighbouring cells" of the benchmark. */
    val k: Int,
    val cellsWithPeak: Int,
    /** Cells whose neighbourhood was complete enough to measure. */
    val neighbourhoods: Int,
    val medianSpreadDays: Int,
    val p10SpreadDays: Int,
    val p90SpreadDays: Int,
    val earliestPeak: String?,
    val medianPeak: String?,
    val latestPeak: String?,
)

object LocalSpread {

    /**
     * Neighbours a cell needs before its spread is counted.
     *
     * A cell on a state border, a coastline or a lake shore sees only the part
     * of its disk that is forested and scored. Measured anyway, its spread is
     * the spread of whatever survived -- systematically narrower than the
     * interior, and enough of them to drag a median down. Excluding them costs
     * the rim of each state and keeps the number comparable between states of
     * different shapes.
     */
    const val MIN_NEIGHBOURS = 20

    /**
     * @param peaks the day each cell enters PEAK, from ForecastRepository.
     * @param disk neighbourhood lookup, H3Grid::disk in production.
     *
     * Pure, and separated from both of those deliberately: this is the half
     * worth testing, and it needs neither a database nor an H3 library to say
     * whether a flattened map reads as flat. See docs/testing.md.
     */
    fun of(
        peaks: Map<Long, LocalDate>,
        disk: (Long, Int) -> List<Long>,
        k: Int = 4,
        stateFips: String? = null,
    ): LocalSpreadResult {
        val spreads = mutableListOf<Long>()
        for (h3 in peaks.keys) {
            // Only cells that actually have a peak. A neighbour that never
            // turns is not a wider spread, it is a missing measurement, and
            // treating it as either would say something untrue.
            val dates = disk(h3, k).mapNotNull { peaks[it] }
            if (dates.size < MIN_NEIGHBOURS) continue
            spreads += ChronoUnit.DAYS.between(dates.min(), dates.max())
        }

        val dates = peaks.values.sorted()
        return LocalSpreadResult(
            stateFips = stateFips,
            k = k,
            cellsWithPeak = peaks.size,
            neighbourhoods = spreads.size,
            // The median rather than the mean: one hexagon whose weather is
            // thin peaks in December and would drag a mean up on its own.
            medianSpreadDays = percentile(spreads, 0.5),
            // Both tails, because the two failures look different. Flattening
            // pulls the whole distribution down; a data gap widens p90 while
            // leaving the median where it was.
            p10SpreadDays = percentile(spreads, 0.1),
            p90SpreadDays = percentile(spreads, 0.9),
            earliestPeak = dates.firstOrNull()?.toString(),
            medianPeak = dates.getOrNull(dates.size / 2)?.toString(),
            latestPeak = dates.lastOrNull()?.toString(),
        )
    }

    private fun percentile(values: List<Long>, p: Double): Int {
        if (values.isEmpty()) return -1
        val sorted = values.sorted()
        return sorted[Math.round((sorted.size - 1) * p).toInt()].toInt()
    }
}
