package com.foliage.ingest.weather.hrrr

import com.foliage.grid.LonLat
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import ucar.nc2.dt.grid.GridDataset
import java.nio.file.Path

/**
 * Reads values out of a GRIB2 file at given points.
 *
 * HRRR is on a Lambert Conformal grid of 1059 x 1799 cells covering CONUS at
 * 3 km — which is why this phase exists: that is H3 resolution 6's own scale,
 * so cells get their own forecast instead of a lapse-rate downscale from
 * resolution 5.
 */
@Component
class Grib2Sampler {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Samples the first grid in [file] at each point, in input order.
     * `null` where a point falls outside the model domain.
     *
     * [file] **must be absolute**: NetCDF-Java writes a collection index into
     * its own cache directory and then resolves the data file relative to
     * that, so a relative path fails with a FileNotFoundException naming a
     * path inside the cache.
     */
    fun sample(file: Path, points: List<LonLat>): List<Double?> {
        require(file.isAbsolute) { "GRIB2 path must be absolute, got $file" }
        if (points.isEmpty()) return emptyList()

        return GridDataset.open(file.toString()).use { dataset ->
            val grid = dataset.grids.firstOrNull()
                ?: return List(points.size) { null }.also {
                    log.warn("no grids in {}", file.fileName)
                }

            val coords = grid.coordinateSystem
            // readVolumeData returns [z, y, x] even when z has length one, so a
            // two-index lookup throws. reduce() drops the degenerate axes.
            val data = grid.readVolumeData(0).reduce()
            val shape = data.shape
            val ny = shape[shape.size - 2]
            val nx = shape[shape.size - 1]
            val index = data.index

            points.map { p ->
                val xy = coords.findXYindexFromLatLon(p.lat, p.lon, null)
                val x = xy[0]
                val y = xy[1]
                if (x < 0 || y < 0 || x >= nx || y >= ny) {
                    null
                } else {
                    data.getDouble(index.set(y, x)).takeUnless { it.isNaN() }
                }
            }
        }
    }

    /** Kelvin to Celsius, preserving null. */
    fun kelvinToCelsius(k: Double?): Double? = k?.minus(273.15)
}
