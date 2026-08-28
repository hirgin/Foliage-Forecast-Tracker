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
     * Representative ground elevation for a cell.
     *
     * **Terrain tiles carry bathymetry, not just land.** A coastal cell whose
     * centroid happens to fall offshore reads the seabed: Massachusetts had 92
     * forested cells -- some at 78% canopy -- sitting at up to -74 m, and Maine
     * 83 more. Those are real forest on islands and peninsulas, not water, and
     * a -60 m reading gives the lapse-rate downscale a 0.4 C warm bias that
     * pushes their peak a day or two late along every coastline.
     *
     * Sampling the same seven points as canopy fixes it, because a cell that is
     * mostly land has land samples even when its centre is not. Where samples
     * disagree, land wins.
     *
     * Cells with *no* land sample keep their negative value rather than being
     * clamped: Death Valley and the Salton Sea really are below sea level, and
     * a blanket clamp would quietly invent ground there.
     */
    fun landElevation(samples: List<Int?>): Int? {
        val present = samples.filterNotNull()
        if (present.isEmpty()) return null
        val land = present.filter { it >= 0 }
        return Math.round((if (land.isNotEmpty()) land else present).average()).toInt()
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
