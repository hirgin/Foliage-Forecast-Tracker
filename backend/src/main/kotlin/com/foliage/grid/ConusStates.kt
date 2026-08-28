package com.foliage.grid

/**
 * The states the grid covers.
 *
 * CONUS only, deliberately. Alaska and Hawaii are excluded because the HRRR
 * domain, the NLCD canopy raster and the 3DEP coverage used here are all
 * conterminous-US products, and because the forecast horizon work in ADR-0005
 * assumes a single contiguous season.
 *
 * Names match the `NAME` field in Census TIGERweb, which is what the boundary
 * source queries on.
 */
object ConusStates {

    /** Ordered north-east to south-west, so a partial run covers foliage country first. */
    val NEW_ENGLAND = listOf(
        "Maine", "New Hampshire", "Vermont", "Massachusetts",
        "Rhode Island", "Connecticut",
    )

    val MID_ATLANTIC = listOf(
        "New York", "Pennsylvania", "New Jersey", "Delaware", "Maryland",
        "District of Columbia", "Virginia", "West Virginia",
    )

    val GREAT_LAKES = listOf("Michigan", "Wisconsin", "Minnesota", "Ohio", "Indiana", "Illinois")

    val APPALACHIAN = listOf("Kentucky", "Tennessee", "North Carolina", "South Carolina", "Georgia")

    val SOUTH = listOf("Alabama", "Mississippi", "Louisiana", "Arkansas", "Florida", "Texas", "Oklahoma")

    val PLAINS = listOf("Missouri", "Iowa", "Kansas", "Nebraska", "South Dakota", "North Dakota")

    val MOUNTAIN = listOf(
        "Montana", "Wyoming", "Colorado", "New Mexico", "Idaho", "Utah", "Arizona", "Nevada",
    )

    val PACIFIC = listOf("Washington", "Oregon", "California")

    /**
     * All 48 contiguous states plus DC, in foliage-first order.
     *
     * The ordering matters operationally rather than technically: a full
     * bootstrap is many hours of third-party sampling, and if it is
     * interrupted the states people actually visit for foliage are already
     * done.
     */
    val ALL: List<String> =
        NEW_ENGLAND + MID_ATLANTIC + GREAT_LAKES + APPALACHIAN + PLAINS + SOUTH + MOUNTAIN + PACIFIC

    /** Named subsets a caller can bootstrap without listing states by hand. */
    val REGIONS: Map<String, List<String>> = mapOf(
        "new-england" to NEW_ENGLAND,
        "mid-atlantic" to MID_ATLANTIC,
        "great-lakes" to GREAT_LAKES,
        "appalachian" to APPALACHIAN,
        "plains" to PLAINS,
        "south" to SOUTH,
        "mountain" to MOUNTAIN,
        "pacific" to PACIFIC,
        "conus" to ALL,
    )

    fun resolve(region: String): List<String> =
        REGIONS[region.lowercase()]
            ?: error("unknown region '$region'; expected one of ${REGIONS.keys.sorted()}")
}
