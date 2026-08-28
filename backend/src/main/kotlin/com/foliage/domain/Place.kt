package com.foliage.domain

import com.foliage.ingest.places.PlaceKind

/** A town, park, mountain or notch, resolved to the hexagon that contains it. */
data class Place(
    val geonameId: Int,
    val name: String,
    val stateCode: String?,
    val latitude: Double,
    val longitude: Double,
    val population: Int,
    val kind: PlaceKind,
    val h3: Long,
)
