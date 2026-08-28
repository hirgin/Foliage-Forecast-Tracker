package com.foliage.ingest.places

/**
 * One row of the GeoNames dump, reduced to what this project needs.
 *
 * The file is tab-separated with 19 columns and no header. Only a handful
 * matter here; the rest are alternate names, timezones and admin codes.
 */
data class GeoNamesRow(
    val geonameId: Int,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val featureClass: String,
    val featureCode: String,
    val countryCode: String,
    val admin1: String?,
    val population: Int,
)

/**
 * Parsing for the GeoNames tab-separated dump.
 *
 * Kept separate from the download so it can be driven by fixture rows; the
 * full US file is 68 MB, which is not something to commit for a test.
 */
object GeoNamesParser {

    private const val COLUMNS = 19

    private const val ID = 0
    private const val NAME = 1
    private const val LATITUDE = 4
    private const val LONGITUDE = 5
    private const val FEATURE_CLASS = 6
    private const val FEATURE_CODE = 7
    private const val COUNTRY = 8
    private const val ADMIN1 = 10
    private const val POPULATION = 14

    /**
     * Parses one line, or null if it is malformed.
     *
     * A 2.2 million row file will contain some damage. One bad row must not
     * abort an ingest, and silently skipping it is the right call — there is
     * nothing a caller could usefully do about a truncated line.
     */
    fun parseLine(line: String): GeoNamesRow? {
        if (line.isBlank()) return null
        val f = line.split('\t')
        if (f.size < COLUMNS) return null

        val id = f[ID].toIntOrNull() ?: return null
        val lat = f[LATITUDE].toDoubleOrNull() ?: return null
        val lon = f[LONGITUDE].toDoubleOrNull() ?: return null
        val name = f[NAME].takeIf { it.isNotBlank() } ?: return null

        // Coordinates outside these ranges are corrupt, not merely unusual,
        // and would place a hexagon lookup somewhere impossible.
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null

        return GeoNamesRow(
            geonameId = id,
            name = name,
            latitude = lat,
            longitude = lon,
            featureClass = f[FEATURE_CLASS],
            featureCode = f[FEATURE_CODE],
            countryCode = f[COUNTRY],
            admin1 = f[ADMIN1].takeIf { it.isNotBlank() },
            // Population is frequently blank or zero; that is information, not
            // an error. Killington and Grafton both record zero.
            population = f[POPULATION].toIntOrNull() ?: 0,
        )
    }

    /** Whether a row is a place this project cares about. */
    fun isWanted(row: GeoNamesRow): Boolean =
        row.countryCode == "US" && PlaceKind.fromFeatureCode(row.featureCode) != null
}
