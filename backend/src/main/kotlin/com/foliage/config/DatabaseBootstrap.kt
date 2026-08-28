package com.foliage.config

import org.flywaydb.core.Flyway
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import javax.sql.DataSource

/**
 * Runs Flyway migrations manually rather than via Spring Boot's auto-configuration.
 *
 * The point is that an unreachable database degrades the app instead of killing it:
 * the process still starts, serves /api/v1/meta, and reports exactly what is wrong.
 * A dev with no database running should see a clear status page, not a stack trace.
 */
@Component
class DatabaseBootstrap(private val dataSource: DataSource) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Volatile
    final var status: DatabaseStatus = DatabaseStatus(state = "starting")
        private set

    // Ordered first: other ApplicationReadyEvent listeners -- notably the
    // static export runner -- must not observe the database before migrations
    // have run. Spring gives no ordering guarantee without this, and the
    // resulting race passed locally and failed on a CI runner.
    @Order(Ordered.HIGHEST_PRECEDENCE)
    @EventListener(ApplicationReadyEvent::class)
    fun migrate() {
        status = try {
            val flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
            val result = flyway.migrate()
            // targetSchemaVersion is null when nothing ran this boot, which is
            // the normal steady state. Report where the schema actually *is*,
            // not what this particular run happened to move it to.
            val current = flyway.info().current()?.version?.version ?: "none"
            log.info("Migrations applied: {} (schema at {})", result.migrationsExecuted, current)
            DatabaseStatus(
                state = "connected",
                schemaVersion = current,
                migrationsApplied = result.migrationsExecuted,
            )
        } catch (e: Exception) {
            log.warn("Database unavailable -- running in degraded mode: {}", e.message)
            DatabaseStatus(state = "unavailable", error = e.message?.lines()?.firstOrNull())
        }
    }
}

data class DatabaseStatus(
    val state: String,
    val schemaVersion: String? = null,
    val migrationsApplied: Int? = null,
    val error: String? = null,
)
