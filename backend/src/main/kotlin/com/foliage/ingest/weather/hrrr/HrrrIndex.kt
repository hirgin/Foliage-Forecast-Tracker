package com.foliage.ingest.weather.hrrr

/**
 * One GRIB2 message within an HRRR file, located by byte range.
 *
 * [endByte] is null for the final record: the index gives start offsets only,
 * so the last record simply runs to the end of the file. An HTTP range request
 * with an open end handles that.
 */
data class GribRecord(
    val number: Int,
    val startByte: Long,
    val endByte: Long?,
    val variable: String,
    val level: String,
) {
    /** HTTP Range header value, e.g. "bytes=34911142-36125417". */
    val byteRange: String
        get() = if (endByte == null) "bytes=$startByte-" else "bytes=$startByte-${endByte - 1}"

    val sizeBytes: Long? get() = endByte?.let { it - startByte }
}

/**
 * Parses the `.idx` sidecar published beside every HRRR GRIB2 file.
 *
 * This is what makes the whole approach viable: a full HRRR surface file is
 * ~133 MB, but one variable is ~1.2 MB. The index gives the byte offset of
 * every message, so a single HTTP range request fetches just the wanted one --
 * a 112x saving, measured. See ADR-0006.
 *
 * Format is colon-delimited, one record per line:
 *
 *     71:34911142:d=2026082712:TMP:2 m above ground:anl:
 *     ^  ^        ^            ^   ^                ^
 *     |  offset   date         var level            type
 *     record number
 */
object HrrrIndexParser {

    fun parse(text: String): List<GribRecord> {
        val rows = text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { line ->
                val parts = line.split(":")
                // A well-formed row has at least: number, offset, date, var, level.
                if (parts.size < 5) return@mapNotNull null
                val number = parts[0].toIntOrNull() ?: return@mapNotNull null
                val offset = parts[1].toLongOrNull() ?: return@mapNotNull null
                Triple(number, offset, parts)
            }
            .toList()

        // A record's end is the next record's start. Sort defensively rather
        // than trusting file order -- a negative byte range is a silent,
        // confusing failure at the HTTP layer.
        val sorted = rows.sortedBy { it.second }

        return sorted.mapIndexed { i, (number, offset, parts) ->
            GribRecord(
                number = number,
                startByte = offset,
                endByte = sorted.getOrNull(i + 1)?.second,
                variable = parts[3],
                level = parts[4],
            )
        }
    }

    /** Finds one message by variable and level, e.g. `TMP` at `2 m above ground`. */
    fun find(records: List<GribRecord>, variable: String, level: String): GribRecord? =
        records.firstOrNull { it.variable == variable && it.level == level }
}
