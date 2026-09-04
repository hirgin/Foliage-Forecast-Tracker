package com.foliage.api

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Binary encoding for the static export.
 *
 * JSON does not survive the jump to CONUS. Repeating `{"h3":"862...","progression":71.2,...}`
 * for every cell on every day costs about 95 bytes per cell-day; across 76,041
 * forested cells and a 76-day season that is 1.16 GB and 76,041 separate
 * timeline files, over what GitHub Pages will host.
 *
 * The cell identifiers never change between days, so they are written **once**
 * into an index and every daily file becomes three parallel byte arrays in
 * that same order. A cell-day drops from ~95 bytes to **3**.
 *
 *     Vermont, one day:  61,791 bytes of JSON  ->  1,955 bytes packed
 *     CONUS, one day:       7.2 MB             ->    228 KB
 *     CONUS, whole season:  1.16 GB            ->   ~17 MB
 *
 * Precision is deliberately coarse. Progression, intensity and confidence are
 * quantised to a single byte because the map draws a colour ramp: a tenth of a
 * progression point is not a distinguishable colour, and stage is recomputed
 * from progression client-side rather than stored.
 *
 * Everything is little-endian, which is what `DataView` defaults to reading
 * cheaply in a browser.
 */
object PackedFormat {

    /** Daily grid: magic, count, then progression/intensity/confidence. */
    const val MAGIC_DAY = "FFD1"

    /** Timeline shard: magic, counts, cell indices, then per-cell series. */
    const val MAGIC_TIMELINE = "FFT1"

    const val HEADER_BYTES = 8
    const val CHANNELS = 3

    /**
     * Quantises a 0–100 value into one byte, clamping rather than wrapping.
     *
     * A value of 255 is reserved as "no data", so genuine readings top out at
     * 200 — the scale is doubled to keep half-point resolution, which is
     * enough to keep a ramp smooth without a second byte.
     */
    fun quantise(value: Double?): Int {
        if (value == null || value.isNaN()) return NO_DATA
        return Math.round(value.coerceIn(0.0, 100.0) * 2).toInt()
    }

    const val NO_DATA = 255

    /**
     * An evergreen forest: surveyed, scored, and never going to change colour.
     *
     * A separate value from [NO_DATA] because they are different claims and
     * the map has to say so. Drawn as no-data, an evergreen hexagon reads as a
     * hole in the forecast -- "not forecast yet" in the legend -- when the
     * truth is the opposite: it is known, and known to stay green. Scored as
     * zero progression instead, it reads as a forest still waiting to turn,
     * which is how a December map grew pockets of green.
     *
     * Neither is right, so evergreens get their own value and their own colour.
     * Genuine readings top out at 200, so 254 is free.
     */
    const val EVERGREEN = 254

    /** Confidence arrives as 0–1 rather than 0–100. */
    fun quantiseUnit(value: Double?): Int = quantise(value?.times(100))

    fun buffer(bytes: Int): ByteBuffer =
        ByteBuffer.allocate(bytes).order(ByteOrder.LITTLE_ENDIAN)

    fun ByteBuffer.putMagic(magic: String): ByteBuffer {
        require(magic.length == 4) { "magic must be 4 bytes" }
        put(magic.toByteArray(Charsets.US_ASCII))
        return this
    }
}
