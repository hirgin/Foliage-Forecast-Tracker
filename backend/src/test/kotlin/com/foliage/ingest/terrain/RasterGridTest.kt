package com.foliage.ingest.terrain

import com.foliage.grid.LonLat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tiling arithmetic for the canopy raster.
 *
 * Pure maths, no network, and the failure modes are silent ones: a flipped
 * latitude axis or an off-by-one at a tile edge attaches a real canopy value
 * to the wrong ground, which then quietly redraws the forest mask.
 */
class RasterGridTest {

    @Test
    fun `names a tile by its south-west corner`() {
        // Vermont: negative longitude must floor away from zero, so -72.69
        // belongs to the tile starting at -73 and not at -72.
        val tile = RasterGrid.tileOf(LonLat(-72.6874, 44.4654))
        assertEquals(RasterTile(-73, 44), tile)
        assertEquals(-72, tile.east)
        assertEquals(45, tile.north)
    }

    @Test
    fun `a tile owns its south-west corner and not its north-east one`() {
        // Exactly one tile claims any given point. Were the boundary inclusive
        // at both ends, points on a shared edge would be fetched twice and,
        // worse, resolve differently depending on rounding.
        assertEquals(RasterTile(-73, 44), RasterGrid.tileOf(LonLat(-73.0, 44.0)))
        assertEquals(RasterTile(-72, 45), RasterGrid.tileOf(LonLat(-72.0, 45.0)))
    }

    @Test
    fun `builds the bbox in the order ArcGIS expects`() {
        assertEquals("-73,44,-72,45", RasterTile(-73, 44).bbox())
    }

    @Test
    fun `north is row zero`() {
        // Raster rows run north to south while latitude runs south to north.
        // Inverting this mirrors every tile and is invisible in aggregate --
        // mean canopy would be unchanged while every cell got another cell's value.
        val north = RasterGrid.locate(LonLat(-72.5, 44.99))
        val south = RasterGrid.locate(LonLat(-72.5, 44.01))
        assertEquals(north.tile, south.tile)
        assertTrue(north.py < south.py, "py ${north.py} should be above ${south.py}")
    }

    @Test
    fun `east is column increasing`() {
        val west = RasterGrid.locate(LonLat(-72.99, 44.5))
        val east = RasterGrid.locate(LonLat(-72.01, 44.5))
        assertTrue(west.px < east.px)
    }

    @Test
    fun `corners map to opposite corners of the image`() {
        // Both corners are probed just inside the tile. A tile owns its
        // south-west corner and not its north-east one, because flooring puts
        // latitude 45.0 in the tile above -- so the exact corner coordinates
        // belong to neighbours and would test the wrong tile.
        val topLeft = RasterGrid.locate(LonLat(-72.9999, 44.9999))
        assertEquals(RasterTile(-73, 44), topLeft.tile)
        assertEquals(0, topLeft.px)
        assertEquals(0, topLeft.py)

        val bottomRight = RasterGrid.locate(LonLat(-72.0001, 44.0001))
        assertEquals(RasterTile(-73, 44), bottomRight.tile)
        assertEquals(RasterGrid.PIXELS - 1, bottomRight.px)
        assertEquals(RasterGrid.PIXELS - 1, bottomRight.py)
    }

    @Test
    fun `pixels stay inside the image everywhere`() {
        // One out-of-range index throws at sample time and takes a whole
        // CONUS bootstrap with it.
        var lat = 24.0
        while (lat <= 49.0) {
            var lon = -125.0
            while (lon <= -66.0) {
                val at = RasterGrid.locate(LonLat(lon, lat))
                assertTrue(at.px in 0 until RasterGrid.PIXELS, "px ${at.px} at $lon,$lat")
                assertTrue(at.py in 0 until RasterGrid.PIXELS, "py ${at.py} at $lon,$lat")
                lon += 0.37
            }
            lat += 0.31
        }
    }

    @Test
    fun `a hexagon worth of samples shares one tile`() {
        // The saving depends on this. A degree tile is ~80 km across at this
        // latitude, so the seven samples CellSampling takes for one 3 km cell
        // land together and cost a single fetch.
        val around = listOf(
            LonLat(-72.6874, 44.4654),
            LonLat(-72.70, 44.48),
            LonLat(-72.67, 44.45),
            LonLat(-72.66, 44.47),
        )
        assertEquals(1, around.map { RasterGrid.tileOf(it) }.distinct().size)
    }

    @Test
    fun `CONUS needs a tractable number of tiles`() {
        // The claim that replaces ten hours with minutes. This is an upper
        // bound: the bounding box includes ocean and desert no forest cell
        // sits on.
        val tiles = buildSet {
            var lat = 24.0
            while (lat <= 49.0) {
                var lon = -125.0
                while (lon <= -66.0) {
                    add(RasterGrid.tileOf(LonLat(lon, lat)))
                    lon += 0.25
                }
                lat += 0.25
            }
        }
        assertTrue(tiles.size < 1_800, "CONUS took ${tiles.size} tiles")
    }
}
