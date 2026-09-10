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

    /**
     * When peak arrives, one byte per cell.
     *
     * The daily files answer "what is this cell doing on this date". This is
     * the inverse, and the question people actually arrive with: when should I
     * go here. A byte holds the offset in days from the season's first day,
     * which a 106-day season fits with room to spare, and [NO_DATA] means the
     * cell never reaches peak inside it.
     */
    const val MAGIC_PEAK = "FFPK"

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
     * Retired, and reserved rather than freed. **Do not reuse this byte.**
     *
     * It marked an evergreen forest: surveyed, scored, and never going to
     * change colour, drawn in a slate blue of its own. The category is gone
     * because the model outgrew it -- conifers turn on the same curve as
     * everything else at about a third the vividness (see
     * PeakDateModel.CONIFER_VIVIDNESS), which is a quiet autumn rather than a
     * class apart. This value's own comment predicted the end: "once every
     * state is rescored no cell matches this".
     *
     * What it still caught was worse than nothing. Read out of the timeline
     * shards, which never applied it, all 32 cells carrying it had no forecast
     * for any of the 106 days of the season. The map was calling them "known,
     * and known to stay green" when they were holes. They write [NO_DATA] now.
     *
     * Kept as a name because published exports carry the byte until the next
     * export replaces them, and the client must go on decoding it as "no
     * reading" -- read as an ordinary value it halves to 127 and draws as past
     * peak. Reusing 254 for anything else would resurrect that.
     */
    @Suppress("unused")
    const val RETIRED_EVERGREEN = 254

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
