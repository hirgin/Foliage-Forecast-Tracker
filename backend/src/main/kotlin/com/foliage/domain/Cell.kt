package com.foliage.domain

/** One hexagon of the forecast grid. */
data class Cell(
    val h3: Long,
    val resolution: Int,
    val parentRes5: Long,
    val parentRes4: Long,
    val parentRes3: Long,
    val centroidLat: Double,
    val centroidLon: Double,
    val elevationM: Int?,
    val canopyPct: Int?,
    val stateFips: String,
    val stateName: String?,
    /** FIA forest type group code; null until the cell has been sampled. */
    val forestTypeGroup: Int? = null,
)
