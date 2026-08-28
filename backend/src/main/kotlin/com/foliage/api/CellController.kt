package com.foliage.api

import com.foliage.grid.H3Grid
import com.foliage.persistence.CellRepository
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
