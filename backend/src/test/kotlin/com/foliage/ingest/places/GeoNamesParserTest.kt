package com.foliage.ingest.places

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Driven by real rows lifted from the GeoNames US dump, plus two deliberately
 * damaged ones. The full file is 68 MB and 2.24 million rows, which is not
 * something to commit for a test.
 */
class GeoNamesParserTest {

    private val lines: List<String> =
        checkNotNull(javaClass.getResourceAsStream("/fixtures/geonames-vt-sample.tsv"))
            .bufferedReader().readLines()

    private val parsed = lines.mapNotNull { GeoNamesParser.parseLine(it) }
    private fun byName(name: String) = parsed.first { it.name == name }

    @Test
    fun `parses real rows and skips damaged ones`() {
        // Eight lines in, six valid: one has a non-numeric id, one is truncated.
        assertEquals(6, parsed.size)
        assertTrue(parsed.none { it.name == "Broken" })
    }

    @Test
    fun `reads the fields that matter`() {
        val stowe = byName("Stowe")
        assertEquals("US", stowe.countryCode)
        assertEquals("VT", stowe.admin1)
        assertEquals("PPL", stowe.featureCode)
        assertEquals(4314, stowe.population)
        assertTrue(stowe.latitude in 44.0..45.0 && stowe.longitude in -73.0..-72.0)
    }

    @Test
    fun `keeps places recording zero population`() {
        // The whole reason for filtering by feature code. Killington and
        // Grafton report zero and are exactly what this site is for.
        assertEquals(0, byName("Killington").population)
        assertEquals(0, byName("Grafton").population)
        assertTrue(GeoNamesParser.isWanted(byName("Killington")))
        assertTrue(GeoNamesParser.isWanted(byName("Grafton")))
    }

    @Test
    fun `keeps natural features, not just towns`() {
        assertTrue(GeoNamesParser.isWanted(byName("Mount Mansfield")))
        assertTrue(GeoNamesParser.isWanted(byName("Smugglers Notch")))
        assertTrue(GeoNamesParser.isWanted(byName("Green Mountain National Forest")))
    }

    @Test
    fun `classifies each feature code`() {
        assertEquals(PlaceKind.TOWN, PlaceKind.fromFeatureCode(byName("Stowe").featureCode))
        assertEquals(PlaceKind.MOUNTAIN, PlaceKind.fromFeatureCode(byName("Mount Mansfield").featureCode))
        assertEquals(PlaceKind.NOTCH, PlaceKind.fromFeatureCode(byName("Smugglers Notch").featureCode))
        assertEquals(
            PlaceKind.FOREST,
            PlaceKind.fromFeatureCode(byName("Green Mountain National Forest").featureCode),
        )
    }

    @Test
    fun `rejects the noise that dominates the file`() {
        // 1.1 million of the 2.24 million US features are buildings, farms and
        // survey marks. None of them are places anyone visits for leaves.
        listOf("CH", "SCH", "BLDG", "STM", "MNMT", "PO").forEach {
            assertNull(PlaceKind.fromFeatureCode(it), "$it should not be kept")
        }
    }

    @Test
    fun `rejects rows outside the United States`() {
        val foreign = byName("Stowe").copy(countryCode = "GB")
        assertTrue(!GeoNamesParser.isWanted(foreign))
    }

    @Test
    fun `rejects impossible coordinates`() {
        // Corrupt coordinates would resolve to a hexagon somewhere absurd
        // rather than failing, so they are caught at the parse boundary.
        val base = lines.first { it.contains("\tStowe\t") }.split('\t').toMutableList()
        base[4] = "412.0"
        assertNull(GeoNamesParser.parseLine(base.joinToString("\t")))

        base[4] = "44.4"
        base[5] = "-999.0"
        assertNull(GeoNamesParser.parseLine(base.joinToString("\t")))
    }

    @Test
    fun `blank and short lines are skipped, not fatal`() {
        assertNull(GeoNamesParser.parseLine(""))
        assertNull(GeoNamesParser.parseLine("   "))
        assertNull(GeoNamesParser.parseLine("1\tName"))
    }

    @Test
    fun `ranks towns above hamlets and unnamed summits`() {
        with(PlaceKind) {
            assertTrue(PlaceKind.TOWN.rank > PlaceKind.MOUNTAIN.rank)
            assertTrue(PlaceKind.PARK.rank > PlaceKind.FOREST.rank)
        }
    }

    @Test
    fun `every kept row has a kind`() {
        parsed.filter { GeoNamesParser.isWanted(it) }.forEach {
            assertNotNull(PlaceKind.fromFeatureCode(it.featureCode), "${it.name} has no kind")
        }
    }
}
