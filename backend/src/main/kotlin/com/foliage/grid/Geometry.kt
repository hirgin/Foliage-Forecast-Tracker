package com.foliage.grid

/**
 * Minimal geometry types for the bootstrap. Deliberately not JTS-flavoured:
 * the only operation the grid needs is "tile this ring", and H3 does the
 * containment work itself.
 */
data class LonLat(val lon: Double, val lat: Double)

data class SimplePolygon(
    val outer: List<LonLat>,
    val holes: List<List<LonLat>> = emptyList(),
)
