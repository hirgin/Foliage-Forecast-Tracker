package com.foliage.ingest.places

/**
 * What sort of place this is, mapped from GeoNames feature codes.
 *
 * Selecting by feature code rather than by population is deliberate. Foliage
 * destinations are small: Killington and Grafton record a population of zero,
 * Manchester 740, Woodstock 871. Any population threshold high enough to keep
 * the list tidy would drop exactly the places people drive to see leaves, and
 * leave Burlington.
 *
 * Natural features earn their place for the same reason — Mount Mansfield,
 * Camel's Hump and Smugglers' Notch are foliage destinations in a way that
 * most towns are not.
 */
enum class PlaceKind {
    TOWN,
    PARK,
    FOREST,
    MOUNTAIN,
    NOTCH,
    ;

    companion object {
        /**
         * GeoNames feature code to kind. Anything unlisted is skipped — the
         * full US file is 2.24 million features, most of them buildings,
         * streams and survey marks.
         */
        private val BY_CODE: Map<String, PlaceKind> = buildMap {
            // Populated places, including the administrative variants that
            // county seats and state capitals carry.
            listOf("PPL", "PPLA", "PPLA2", "PPLA3", "PPLA4", "PPLA5", "PPLC", "PPLG")
                .forEach { put(it, TOWN) }
            put("PRK", PARK)
            put("FRST", FOREST)
            put("RESF", FOREST)
            put("MT", MOUNTAIN)
            put("PK", MOUNTAIN)
            put("GAP", NOTCH)
        }

        fun fromFeatureCode(code: String): PlaceKind? = BY_CODE[code]

        /**
         * Tie-break weight for search ranking, highest first.
         *
         * A town is usually what someone means when they type a name, but a
         * named mountain outranks a hamlet of forty people — and both outrank
         * the thousand-odd unnamed summits the mountain code also captures.
         */
        val PlaceKind.rank: Int
            get() = when (this) {
                TOWN -> 5
                PARK -> 4
                MOUNTAIN -> 3
                NOTCH -> 2
                FOREST -> 1
            }
    }
}
