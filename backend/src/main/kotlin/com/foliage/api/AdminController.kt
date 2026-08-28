package com.foliage.api

import com.foliage.ingest.GridBootstrap
import com.foliage.ingest.GridBootstrapResult
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * Manual triggers for pipeline jobs.
 *
 * Absent unless `foliage.admin.enabled` is true: these endpoints do real work
 * against third-party services and the database, and nothing about them should
 * be reachable in a deployed read-only site.
 */
@RestController
@RequestMapping("/api/v1/admin")
@ConditionalOnProperty("foliage.admin.enabled", havingValue = "true")
class AdminController(private val gridBootstrap: GridBootstrap) {

    @PostMapping("/bootstrap-grid")
    fun bootstrapGrid(@RequestParam state: String): GridBootstrapResult =
        gridBootstrap.bootstrapState(state)
}
