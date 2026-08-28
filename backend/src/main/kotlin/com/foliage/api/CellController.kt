package com.foliage.api

import com.foliage.grid.H3Grid
import com.foliage.persistence.CellRepository
import com.foliage.persistence.PlaceRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.concurrent.TimeUnit

@RestController
@RequestMapping("/api/v1")
class CellController(
    private val cells: CellRepository,
    private val places: PlaceRepository,
    private val grid: H3Grid,
    @Value("\${foliage.grid.min-canopy-pct}") private val defaultMinCanopy: Int,
) {

    /**
     * The grid for one state.
     *
     * H3 indexes are emitted as **hex strings, not numbers**. They are 64-bit,
     * and JavaScript's number type loses precision above 2^53 — sending them
     * as JSON numbers would silently corrupt roughly the low three digits of
     * every index and scatter hexagons across the map. The hex form is also
     * exactly what h3-js and deck.gl expect.
     */
    /**
     * Places inside the loaded grid, for search. Mirrors the static export so
     * development and the deployed site behave identically.
     */
    @GetMapping("/places")
    fun places(@RequestParam(defaultValue = "50") state: String): PlacesResponse {
        val order = cells.findByState(state, 0).sortedBy { it.h3 }.map { it.h3 }
        val indexOf = order.withIndex().associate { (i, h3) -> h3 to i }
        val rows = places.findInGrid().filter { indexOf.containsKey(it.h3) }
        return PlacesResponse(
            count = rows.size,
            name = rows.map { it.name },
            state = rows.map { it.stateCode },
            kind = rows.map { it.kind.name },
            population = rows.map { it.population },
            cell = rows.map { indexOf.getValue(it.h3) },
            lat = rows.map { it.latitude },
            lon = rows.map { it.longitude },
        )
    }

    @GetMapping("/cells")
    fun cells(
        @RequestParam(defaultValue = "50") state: String,
        @RequestParam(required = false) minCanopy: Int?,
    ): ResponseEntity<CellsResponse> {
        val threshold = minCanopy ?: defaultMinCanopy
        val rows = cells.findByState(state, threshold)

        val body = CellsResponse(
            stateFips = state,
            minCanopyPct = threshold,
            resolution = rows.firstOrNull()?.resolution ?: 6,
            count = rows.size,
            cells = rows.map {
                CellDto(
                    h3 = grid.toAddress(it.h3),
                    canopyPct = it.canopyPct,
                    elevationM = it.elevationM,
                )
            },
        )

        // The grid changes only when a bootstrap runs, so it caches hard.
        return ResponseEntity.ok()
            .cacheControl(CacheControl.maxAge(1, TimeUnit.HOURS).cachePublic())
            .body(body)
    }
}

/** Searchable places, in the same parallel-array shape as the static export. */
data class PlacesResponse(
    val count: Int,
    val name: List<String>,
    val state: List<String?>,
    val kind: List<String>,
    val population: List<Int>,
    val cell: List<Int>,
    val lat: List<Double>,
    val lon: List<Double>,
)

data class CellsResponse(
    val stateFips: String,
    val minCanopyPct: Int,
    val resolution: Int,
    val count: Int,
    val cells: List<CellDto>,
)

/** Deliberately terse field names: this array carries every hexagon on screen. */
data class CellDto(
    val h3: String,
    val canopyPct: Int?,
    val elevationM: Int?,
)
