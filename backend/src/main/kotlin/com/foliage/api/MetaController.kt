package com.foliage.api

import com.foliage.config.DatabaseBootstrap
import com.foliage.config.DatabaseStatus
import org.springframework.beans.factory.annotation.Value
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Service metadata. The frontend calls this on load to decide whether it can
 * render a map at all, and to label the build it is talking to.
 */
@RestController
@RequestMapping("/api/v1")
class MetaController(
    private val databaseBootstrap: DatabaseBootstrap,
    @Value("\${foliage.model-version}") private val modelVersion: String,
    @Value("\${foliage.grid.resolution}") private val gridResolution: Int,
) {

    @GetMapping("/meta")
    fun meta(): MetaResponse = MetaResponse(
        service = "foliage-forecast",
        modelVersion = modelVersion,
        gridResolution = gridResolution,
        database = databaseBootstrap.status,
        // Populated in Phase 1+; null here means "no grid loaded yet".
        cellCount = null,
        seasonStart = null,
        seasonEnd = null,
        lastIngestAt = null,
    )
}

data class MetaResponse(
    val service: String,
    val modelVersion: String,
    val gridResolution: Int,
    val database: DatabaseStatus,
    val cellCount: Long?,
    val seasonStart: String?,
    val seasonEnd: String?,
    val lastIngestAt: String?,
)
