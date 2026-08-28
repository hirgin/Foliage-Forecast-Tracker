package com.foliage.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PackedFormatTest {

    @Test
    fun `quantises the full range into a byte`() {
        assertEquals(0, PackedFormat.quantise(0.0))
        assertEquals(100, PackedFormat.quantise(50.0))
        assertEquals(200, PackedFormat.quantise(100.0))
    }

    @Test
    fun `keeps half-point resolution`() {
        // The scale is doubled precisely so 71.0 and 71.5 stay distinct; a
        // plain 0-100 byte would collapse them and visibly band the ramp.
        assertTrue(PackedFormat.quantise(71.0) != PackedFormat.quantise(71.5))
        assertEquals(142, PackedFormat.quantise(71.0))
        assertEquals(143, PackedFormat.quantise(71.5))
    }

    @Test
    fun `clamps rather than wrapping`() {
        // Wrapping would turn an out-of-range 101 into near-zero, painting a
        // fully turned cell as untouched green.
        assertEquals(200, PackedFormat.quantise(140.0))
        assertEquals(0, PackedFormat.quantise(-20.0))
    }

    @Test
    fun `never collides a real value with the no-data sentinel`() {
        // 255 means missing. The doubled 0-100 scale tops out at 200, so a
        // genuine reading can never be mistaken for absent data.
        assertTrue(PackedFormat.quantise(100.0) < PackedFormat.NO_DATA)
        assertEquals(PackedFormat.NO_DATA, PackedFormat.quantise(null))
        assertEquals(PackedFormat.NO_DATA, PackedFormat.quantise(Double.NaN))
    }

    @Test
    fun `scales confidence from its 0 to 1 range`() {
        assertEquals(200, PackedFormat.quantiseUnit(1.0))
        assertEquals(170, PackedFormat.quantiseUnit(0.85))
        assertEquals(0, PackedFormat.quantiseUnit(0.0))
        assertEquals(PackedFormat.NO_DATA, PackedFormat.quantiseUnit(null))
    }

    @Test
    fun `round-trips within half a point`() {
        // The decode side divides by two, so error must stay under 0.25 --
        // far below anything the colour ramp can express.
        listOf(0.0, 12.3, 49.9, 71.2, 88.8, 100.0).forEach { v ->
            val back = PackedFormat.quantise(v) / 2.0
            assertTrue(Math.abs(back - v) <= 0.25, "$v round-tripped to $back")
        }
    }

    @Test
    fun `every quantised value fits an unsigned byte`() {
        var v = 0.0
        while (v <= 100.0) {
            val q = PackedFormat.quantise(v)
            assertTrue(q in 0..255, "$v quantised to $q")
            v += 0.1
        }
    }
}
