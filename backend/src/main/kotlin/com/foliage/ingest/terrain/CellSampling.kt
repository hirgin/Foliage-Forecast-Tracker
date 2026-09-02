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
    fun landElevation(samples: List<Int?>, hasCanopy: Boolean = false): Int? {
        val present = samples.filterNotNull()
        if (present.isEmpty()) return null
        val land = present.filter { it >= 0 }
        val mean = Math.round((if (land.isNotEmpty()) land else present).average()).toInt()

        // Preferring land samples halves the problem but does not end it: at
        // ~300 m per pixel a shoreline pixel averages land and sea together, so
        // genuinely forested cells still read slightly negative, and a few sit
        // mostly over water. 370 cells across the Atlantic states survived the
        // first fix, some at 74% canopy and -74 m.
        //
        // Canopy settles it. NLCD derives tree cover from land imagery, so a
        // cell it says is forested has ground above water whatever a smoothed
        // bathymetry pixel reports. Trees are the evidence; the floor is zero.
        //
        // This deliberately does not apply to cells with no canopy, which is
        // what keeps Death Valley and the Salton Sea honest -- nothing grows
        // there, nothing is clamped, and they stay below sea level.
        return if (hasCanopy && mean < 0) 0 else mean
    }

    /**
     * The forest type group a cell is mostly made of.
     *
     * **A mode, not a mean, and the distinction is the whole point.** FIA group
     * codes are identifiers: 800 is maple-beech and 900 is aspen-birch, and
     * their average of 850 is not a forest at all. Averaging categorical codes
     * is the kind of mistake that produces plausible numbers and silent
     * nonsense, so this counts votes instead.
     *
     * Only real FIA group codes are counted; see [isForestCode]. 0 is the
     * raster's no-data -- water, cloud, or off-raster -- and every probe of
     * this dataset returned some, because a 30 m pixel in Minnesota lake
     * country is quite often a lake. 999 is non-stocked ground, a statement
     * that there is no forest rather than a kind of forest. Counting either
     * would let a cell's lakes outvote its trees.
     *
     * Null when nothing is left, meaning the cell has no forest to classify.
     * The model reads that as the maple-beech baseline, so such a cell scores
     * exactly as it did before this term existed.
     */
    fun dominantType(samples: List<Int?>): Int? {
        val votes = samples.filterNotNull().filter { isForestCode(it) }
        if (votes.isEmpty()) return null

        // Ties are ordinary at seven samples -- a cell split evenly between two
        // forests is a real thing, not an error -- so the tie-break has to be
        // deterministic or the same cell would classify differently on
        // successive runs and nobody could reproduce a reading. The lowest code
        // wins. That is arbitrary phenologically, and is documented as
        // arbitrary rather than dressed up as a preference.
        return votes.groupingBy { it }.eachCount().entries
            .sortedWith(compareByDescending<Map.Entry<Int, Int>> { it.value }.thenBy { it.key })
            .first().key
    }

    /** The forest type raster's no-data value: water, cloud, or off-raster. */
    const val NO_DATA = 0

    /** FIA's code for ground carrying no forest, which is not a forest type. */
    const val NON_STOCKED = 999

    /** Lowest and highest real FIA forest type group codes. */
    private val FOREST_CODES = 100..990

    /**
     * Whether a raster value is a forest type group at all.
     *
     * Checked against the code domain rather than trusted, because this raster
     * returns values that are not codes. Tiles overlapping Canada and the
     * Great Lakes come back containing 63693 -- not a forest, not FIA's
     * no-data, and larger than the column that stores it. It surfaced as a
     * database truncation error rather than a wrong forest, which was luck: a
     * value inside the column's range would have been stored as a plausible
     * forest type and been much harder to notice.
     *
     * So the domain is stated here explicitly. Codes this project does not map
     * to a group -- the western and tropical hardwoods, for instance -- are
     * still valid and still stored; they simply score at the baseline until
     * someone measures them.
     */
    fun isForestCode(value: Int): Boolean = value in FOREST_CODES && value != NON_STOCKED

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
