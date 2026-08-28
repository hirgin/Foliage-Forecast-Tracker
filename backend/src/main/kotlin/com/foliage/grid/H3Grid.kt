package com.foliage.grid

import com.uber.h3core.H3Core
import com.uber.h3core.util.LatLng
import org.springframework.stereotype.Component

/**
 * H3 indexing. This is the project's spatial index -- there is no PostGIS and
 * no spatial column anywhere. Neighbours, ancestors and cell geometry are all
 * arithmetic on the 64-bit index. See ADR-0002.
 */
@Component
class H3Grid {

    private val h3: H3Core = H3Core.newInstance()

    /** Every cell of [resolution] whose centre falls inside [polygon]. */
    fun tile(polygon: SimplePolygon, resolution: Int): List<Long> =
        h3.polygonToCells(
            polygon.outer.map { LatLng(it.lat, it.lon) },
            polygon.holes.map { hole -> hole.map { LatLng(it.lat, it.lon) } },
            resolution,
        )

    fun centroid(h3Index: Long): LonLat =
        h3.cellToLatLng(h3Index).let { LonLat(lon = it.lng, lat = it.lat) }

    /** Boundary vertices, for sampling terrain across a cell rather than at a point. */
    fun boundary(h3Index: Long): List<LonLat> =
        h3.cellToBoundary(h3Index).map { LonLat(lon = it.lng, lat = it.lat) }

    fun parent(h3Index: Long, resolution: Int): Long = h3.cellToParent(h3Index, resolution)

    fun resolution(h3Index: Long): Int = h3.getResolution(h3Index)

    /** Debug/logging helper -- the canonical hex string form, e.g. "862baac7fffffff". */
    fun toAddress(h3Index: Long): String = h3.h3ToString(h3Index)
}
