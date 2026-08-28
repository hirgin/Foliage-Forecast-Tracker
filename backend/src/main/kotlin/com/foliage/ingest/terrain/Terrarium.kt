package com.foliage.ingest.terrain

import com.foliage.grid.LonLat
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

/** A slippy-map tile address. */
data class TileRef(val z: Int, val x: Int, val y: Int)

/** Where a point lands inside a tile, in pixels. */
data class TilePixel(val tile: TileRef, val px: Int, val py: Int)

/**
 * Web Mercator tiling and the Terrarium elevation encoding.
 *
 * Terrarium packs height into the RGB channels of an ordinary PNG:
 *
 *     height = (R * 256 + G + B / 256) - 32768
 *
 * The 32768 offset is what allows below-sea-level terrain -- Death Valley
 * decodes to about -81 m rather than clamping at zero.
 *
 * All of this is pure arithmetic, which is the point: it replaces a per-point
 * HTTP request with a tile fetch shared by every cell that falls inside it.
 */
object Terrarium {

    /**
     * Zoom level for sampling.
     *
     * At zoom 9 a pixel is roughly 300 m at mid-latitudes, comfortably finer
     * than the ~3 km hexagons it feeds, and CONUS needs under 4,000 tiles.
     * Each zoom step quadruples that: zoom 11 would be ~50 m but 60,000 tiles,
     * buying detail the grid cannot express -- the same lesson as 3DEP's 1 m
     * data matching a 90 m DEM to within a few metres.
     */
    const val ZOOM = 9

    const val TILE_SIZE = 256

    /** Terrarium cannot represent anything below this; treat it as no data. */
    private const val MIN_VALID_M = -12_000.0
    private const val MAX_VALID_M = 9_000.0

    /**
     * Mercator y is undefined at the poles, so latitude is clamped to the
     * standard Web Mercator limit rather than producing infinity.
     */
    private const val MAX_LAT = 85.05112878

    fun tileCount(z: Int): Int = 1 shl z

    fun locate(point: LonLat, z: Int = ZOOM): TilePixel {
        val n = tileCount(z).toDouble()
        val lat = point.lat.coerceIn(-MAX_LAT, MAX_LAT)
        val r = Math.toRadians(lat)

        val worldX = (point.lon + 180.0) / 360.0 * n
        val worldY = (1.0 - ln(tan(r) + 1.0 / cos(r)) / PI) / 2.0 * n

        val tx = floor(worldX).toInt().coerceIn(0, tileCount(z) - 1)
        val ty = floor(worldY).toInt().coerceIn(0, tileCount(z) - 1)

        // Pixel within the tile. Clamped because a point exactly on the far
        // edge would otherwise index 256 into a 256-wide image.
        val px = ((worldX - tx) * TILE_SIZE).toInt().coerceIn(0, TILE_SIZE - 1)
        val py = ((worldY - ty) * TILE_SIZE).toInt().coerceIn(0, TILE_SIZE - 1)

        return TilePixel(TileRef(z, tx, ty), px, py)
    }

    /** Decodes one pixel. Null where the value is not plausible terrain. */
    fun decode(rgb: Int): Double? {
        val r = (rgb shr 16) and 0xFF
        val g = (rgb shr 8) and 0xFF
        val b = rgb and 0xFF
        val height = (r * 256.0 + g + b / 256.0) - 32768.0
        return height.takeIf { it > MIN_VALID_M && it < MAX_VALID_M }
    }

    fun url(base: String, tile: TileRef): String = "$base/${tile.z}/${tile.x}/${tile.y}.png"

    internal fun approxEquals(a: Double, b: Double, tolerance: Double) = abs(a - b) <= tolerance
}
