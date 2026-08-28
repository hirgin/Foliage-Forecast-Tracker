package com.foliage.ingest.terrain

import com.foliage.grid.H3Grid
import com.foliage.grid.LonLat

/**
 * Chooses where inside a hexagon to sample terrain.
 *
 * A res 6 cell is ~36 km2, which is around 40,000 pixels of the 30 m canopy
 * raster. Sampling only the centroid would be badly noisy: a densely forested
 * cell with a lake or a town at its centre would read as bare ground and be
 * masked out of the map entirely.
 *
 * So each cell is sampled at its centroid plus points drawn part-way toward
 * each of its six vertices, and the results averaged. The vertices themselves
 * are deliberately not used -- they sit exactly on the boundary and are shared
 * with neighbouring cells, which would blur the edges of forest regions.
 */
object CellSampling {

    /** Fraction of the way from centroid to vertex. Keeps samples well inside the cell. */
    private const val VERTEX_REACH = 0.6

    fun points(grid: H3Grid, h3Index: Long): List<LonLat> {
        val c = grid.centroid(h3Index)
        val toward = grid.boundary(h3Index).map { v ->
            LonLat(
                lon = c.lon + (v.lon - c.lon) * VERTEX_REACH,
                lat = c.lat + (v.lat - c.lat) * VERTEX_REACH,
            )
        }
        return listOf(c) + toward
    }

    /**
     * Mean of the samples that came back, ignoring gaps. Null only when every
     * sample for the cell was missing, which means the cell is genuinely off
     * the raster rather than merely unforested.
     */
    fun average(samples: List<Int?>): Int? {
        val present = samples.filterNotNull()
        return if (present.isEmpty()) null else present.average().let { Math.round(it).toInt() }
    }
}
