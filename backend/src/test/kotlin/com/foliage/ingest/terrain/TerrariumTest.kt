package com.foliage.ingest.terrain

import com.foliage.grid.LonLat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tiling and Terrarium decoding.
 *
 * All of this is pure arithmetic with no network, which is the point: the
 * failure modes here are silent. A sign error in the height offset or an
 * inverted Mercator y produces plausible-looking numbers attached to the wrong
 * ground, exactly the class of bug that put Vermont's foliage in the wrong
 * order once already.
 */
class TerrariumTest {

    private fun rgb(r: Int, g: Int, b: Int) = (r shl 16) or (g shl 8) or b

    // --- the encoding -----------------------------------------------------

    @Test
    fun `decodes sea level`() {
        // 32768 = 128 * 256, so R=128 with no remainder is exactly zero.
        assertEquals(0.0, Terrarium.decode(rgb(128, 0, 0))!!, 1e-9)
    }

    @Test
    fun `decodes below sea level rather than clamping`() {
        // The 32768 offset exists for this. Death Valley decoding to a
        // positive number would mean the offset had been dropped.
        val death = Terrarium.decode(rgb(127, 175, 0))!!
        assertTrue(death < 0.0, "expected negative elevation, got $death")
        assertEquals(-81.0, death, 1.0)
    }

    @Test
    fun `blue channel carries the fractional metre`() {
        val whole = Terrarium.decode(rgb(133, 53, 0))!!
        val fraction = Terrarium.decode(rgb(133, 53, 128))!!
        assertEquals(0.5, fraction - whole, 1e-9)
    }

    @Test
    fun `decodes a summit to its published height`() {
        // Mount Mansfield, 1339 m. R=133 G=53 -> 133*256 + 53 - 32768 = 1333.
        assertEquals(1333.0, Terrarium.decode(rgb(133, 53, 0))!!, 1.0)
    }

    @Test
    fun `rejects values that cannot be terrain`() {
        // Ocean tiles and padding decode to the extremes of the range. Treating
        // those as ground would drag whole cells to implausible elevations.
        assertNull(Terrarium.decode(rgb(0, 0, 0)))
        assertNull(Terrarium.decode(rgb(255, 255, 255)))
    }

    // --- tiling -----------------------------------------------------------

    @Test
    fun `places a known point on its known tile`() {
        // Mount Mansfield at zoom 9. Cross-checked against the standard slippy
        // formula rather than against this implementation.
        val at = Terrarium.locate(LonLat(-72.8148, 44.5438), z = 9)
        assertEquals(9, at.tile.z)
        assertEquals(152, at.tile.x)
        assertEquals(185, at.tile.y)
    }

    @Test
    fun `latitude increases as tile y decreases`() {
        // Mercator y grows southward. Getting this backwards would mirror the
        // country north-to-south and quietly invert every elevation gradient.
        val north = Terrarium.locate(LonLat(-72.0, 47.0))
        val south = Terrarium.locate(LonLat(-72.0, 30.0))
        assertTrue(north.tile.y < south.tile.y, "north tile y ${north.tile.y} should be less than ${south.tile.y}")
    }

    @Test
    fun `longitude increases as tile x increases`() {
        val west = Terrarium.locate(LonLat(-124.0, 40.0))
        val east = Terrarium.locate(LonLat(-70.0, 40.0))
        assertTrue(west.tile.x < east.tile.x)
    }

    @Test
    fun `pixels stay inside the tile`() {
        // A point exactly on a tile's far edge would otherwise index 256 into a
        // 256-wide image and throw at sample time.
        for (lat in -85..85 step 5) {
            for (lon in -180..179 step 5) {
                val at = Terrarium.locate(LonLat(lon.toDouble(), lat.toDouble()))
                assertTrue(at.px in 0 until Terrarium.TILE_SIZE, "px ${at.px} at $lon,$lat")
                assertTrue(at.py in 0 until Terrarium.TILE_SIZE, "py ${at.py} at $lon,$lat")
                assertTrue(at.tile.x in 0 until Terrarium.tileCount(Terrarium.ZOOM))
                assertTrue(at.tile.y in 0 until Terrarium.tileCount(Terrarium.ZOOM))
            }
        }
    }

    @Test
    fun `survives the poles where Mercator is undefined`() {
        // tan(90 degrees) is infinite; without the clamp this produces a tile
        // index of NaN and the whole ingest dies on one bad coordinate.
        assertNotNull(Terrarium.locate(LonLat(0.0, 90.0)))
        assertNotNull(Terrarium.locate(LonLat(0.0, -90.0)))
    }

    @Test
    fun `neighbouring points share a tile`() {
        // This is the entire performance argument: adjacent cells must collapse
        // onto one fetch. At zoom 9 a tile spans roughly 60 km, so two points
        // 3 km apart -- one hexagon -- almost always coincide.
        val a = Terrarium.locate(LonLat(-72.80, 44.54))
        val b = Terrarium.locate(LonLat(-72.78, 44.55))
        assertEquals(a.tile, b.tile)
    }

    @Test
    fun `CONUS needs a tractable number of tiles`() {
        // The claim that replaces 71 hours with minutes. Sampled on a grid
        // across the lower 48, which is ~3,900 tiles -- a bound, since the
        // bounding box includes ocean and unforested ground no cell sits on.
        // If this ever balloons, the zoom is wrong: each step up quadruples it.
        val tiles = buildSet {
            var lat = 24.5
            while (lat <= 49.0) {
                var lon = -125.0
                while (lon <= -66.0) {
                    add(Terrarium.locate(LonLat(lon, lat)).tile)
                    lon += 0.1
                }
                lat += 0.1
            }
        }
        assertTrue(tiles.size < 4_500, "CONUS took ${tiles.size} tiles")
    }

    @Test
    fun `builds a tile URL`() {
        assertEquals(
            "https://example.test/9/152/185.png",
            Terrarium.url("https://example.test", TileRef(9, 152, 185)),
        )
    }
}
