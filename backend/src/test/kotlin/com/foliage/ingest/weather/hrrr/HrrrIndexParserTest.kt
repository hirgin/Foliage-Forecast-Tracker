package com.foliage.ingest.weather.hrrr

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Driven by a real `.idx` captured from `noaa-hrrr-bdp-pds` on 2026-08-27.
 * See docs/testing.md for why fixtures rather than live calls.
 */
class HrrrIndexParserTest {

    private val fixture: String =
        checkNotNull(javaClass.getResourceAsStream("/fixtures/hrrr-t12z-f00.idx"))
            .bufferedReader().readText()

    private val records = HrrrIndexParser.parse(fixture)

    @Test
    fun `parses every record in a real HRRR index`() {
        assertEquals(170, records.size)
    }

    @Test
    fun `locates 2 metre temperature`() {
        val tmp = HrrrIndexParser.find(records, "TMP", "2 m above ground")
        assertNotNull(tmp)
        assertEquals(34911142L, tmp.startByte)
        // ~1.2 MB against a 133 MB file: the saving the whole approach rests on.
        val size = tmp.sizeBytes
        assertNotNull(size)
        assertTrue(size in 1_000_000..2_000_000, "expected ~1.2 MB, got $size")
    }

    @Test
    fun `byte range is inclusive and stops before the next record`() {
        val tmp = HrrrIndexParser.find(records, "TMP", "2 m above ground")!!
        val next = records.first { it.startByte > tmp.startByte }
        assertEquals("bytes=${tmp.startByte}-${next.startByte - 1}", tmp.byteRange)
    }

    @Test
    fun `the final record has an open-ended range`() {
        // The index lists start offsets only, so the last message runs to EOF.
        val last = records.maxBy { it.startByte }
        assertNull(last.endByte)
        assertEquals("bytes=${last.startByte}-", last.byteRange)
        assertNull(last.sizeBytes)
    }

    @Test
    fun `records are ordered by offset so no range is ever negative`() {
        val offsets = records.map { it.startByte }
        assertEquals(offsets.sorted(), offsets)
        records.forEach { r ->
            r.endByte?.let {
                assertTrue(it > r.startByte, "record ${r.number} has an inverted range")
            }
        }
    }

    @Test
    fun `distinguishes the same variable at different levels`() {
        // TMP appears at many pressure levels; taking the first match blindly
        // would silently sample 500 mb air instead of screen height.
        val screen = HrrrIndexParser.find(records, "TMP", "2 m above ground")!!
        val surface = HrrrIndexParser.find(records, "TMP", "surface")!!
        val upper = HrrrIndexParser.find(records, "TMP", "500 mb")!!
        assertEquals(3, setOf(screen.startByte, surface.startByte, upper.startByte).size)
    }

    @Test
    fun `an unknown variable is null rather than an exception`() {
        assertNull(HrrrIndexParser.find(records, "NOPE", "2 m above ground"))
        assertNull(HrrrIndexParser.find(records, "TMP", "37 m above ground"))
    }

    @Test
    fun `malformed lines are skipped, not fatal`() {
        val messy = """
            1:0:d=2026082712:REFC:entire atmosphere:anl:

            garbage
            not:a:number:TMP:surface:anl:
            2:275054:d=2026082712:RETOP:cloud top:anl:
        """.trimIndent()
        val parsed = HrrrIndexParser.parse(messy)
        assertEquals(2, parsed.size)
        assertEquals(listOf("REFC", "RETOP"), parsed.map { it.variable })
    }

    @Test
    fun `an empty index yields an empty list`() {
        assertTrue(HrrrIndexParser.parse("").isEmpty())
    }
}
