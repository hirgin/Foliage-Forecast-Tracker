package com.foliage.ingest.terrain

import com.foliage.grid.LonLat
import kotlin.math.floor

/** A whole-degree tile of the canopy raster, named by its south-west corner. */
data class RasterTile(val west: Int, val south: Int) {
    val east: Int get() = west + RasterGrid.DEGREES
    val north: Int get() = south + RasterGrid.DEGREES

    /** The bbox parameter ArcGIS expects: minx,miny,maxx,maxy. */
    fun bbox(): String = "$west,$south,$east,$north"
}

/** Where a point lands inside a raster tile, in pixels. */
data class RasterPixel(val tile: RasterTile, val px: Int, val py: Int)

/**
 * Degree-aligned tiling of the canopy raster.
 *
 * The ArcGIS ImageServer exposes exportImage, which returns raw pixels for a
 * bounding box rather than values for a list of points. That is the whole
 * saving: sampling 1.56 million points (223,650 cells x 7 samples each) costs
 * 6,264 batched requests and about ten hours, while the same ground is roughly
 * 1,500 one-degree tiles fetched once each.
 *
 * Tiles are aligned to whole degrees rather than to the request, so that two
 * calls covering overlapping ground ask for byte-identical extents and the
 * service cache can serve the second one.
 *
 * **Unprojected on purpose.** The tiles are square in degrees, so a northern
 * tile covers less ground east-west than a southern one. That costs some
 * wasted pixels at high latitude and nothing else, and it keeps the pixel
 * mapping to plain linear arithmetic -- worth more here than uniform tiles,
 * because every reprojection is somewhere for a half-pixel offset to hide.
 */
object RasterGrid {

    /** Tile edge in degrees. */
    const val DEGREES = 1

    /**
     * Pixels per tile edge.
     *
     * 3,700 across a degree of latitude is ~30 m, the native resolution of the
     * raster. It is not arbitrary: measured over 962 points on a 3 km lattice
     * across Vermont, coarser averaged rasters agree on *mean* canopy exactly
     * (65% at every resolution tried) but disagree on the mask decision, which
     * is what actually matters -- 5.5% of cells cross the forest threshold at
     * 90 m and 12.6% at 250 m. Sampling natively keeps this implementation a
     * drop-in for the point service it replaces instead of silently redrawing
     * the forest.
     *
     * The cost is ~13 MB per tile, streamed and discarded, never stored.
     */
    const val PIXELS = 3_700

    fun tileOf(point: LonLat): RasterTile =
        RasterTile(floor(point.lon).toInt(), floor(point.lat).toInt())

    fun locate(point: LonLat): RasterPixel {
        val tile = tileOf(point)
        // Linear because the tile is a plain degree box. North is row zero, so
        // latitude is inverted; getting that backwards would mirror every tile.
        val fx = (point.lon - tile.west) / DEGREES
        val fy = (tile.north - point.lat) / DEGREES
        return RasterPixel(
            tile,
            (fx * PIXELS).toInt().coerceIn(0, PIXELS - 1),
            (fy * PIXELS).toInt().coerceIn(0, PIXELS - 1),
        )
    }
}
