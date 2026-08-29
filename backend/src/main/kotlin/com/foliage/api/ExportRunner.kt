package com.foliage.api

import com.foliage.config.DatabaseBootstrap
import com.foliage.forecast.ForecastService
import com.foliage.ingest.weather.WeatherIngest
import org.slf4j.LoggerFactory
import org.springframework.boot.ExitCodeGenerator
import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.ApplicationContext
import org.springframework.context.event.EventListener
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import java.nio.file.Path
import java.time.Duration

/**
 * Runs the static export and exits.
 *
 * Present only when `foliage.export.path` is set, which is how CI invokes the
 * pipeline: boot, migrate, export, stop. Nothing here runs in a normal server
 * process.
 *
 * The exit code matters -- a failed export must fail the CI job rather than
 * quietly publishing a stale or half-written site.
 */
@Component
@ConditionalOnProperty("foliage.export.path")
class ExportRunner(
    private val exporter: StaticExporter,
    private val database: DatabaseBootstrap,
    private val weatherIngest: WeatherIngest,
    private val weatherBackfill: com.foliage.ingest.WeatherBackfill,
    private val forecastService: ForecastService,
    private val context: ApplicationContext,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    // Ordered last, after DatabaseBootstrap has migrated. Without an explicit
    // order these two listeners race, and this one won on CI -- reporting the
    // database as "starting" and exporting nothing.
    @Order(Ordered.LOWEST_PRECEDENCE)
    @EventListener(ApplicationReadyEvent::class)
    fun run() {
        val env = context.environment
        val path = env.getProperty("foliage.export.path")!!
        // Absent or blank means the whole grid. It defaulted to "50" -- Vermont
        // -- from when that was the only state loaded, which meant a deploy
        // published one state no matter how much of the country was in the
        // database. Null is the value both the exporter and the forecast
        // service already understand as "national".
        val state = env.getProperty("foliage.export.state")?.takeIf { it.isNotBlank() }

        // What to *refresh* is deliberately separate from what to export.
        // Exporting is a local read and costs nothing extra as it widens;
        // refreshing pulls weather for every res 5 parent in scope, which
        // nationally is 19,904 cells against an API metered by request weight.
        // Tying the two together would have turned the nightly deploy into a
        // national ingest the moment the export was unpinned -- inside a job
        // with a six hour ceiling. This widens as weather actually lands.
        val refreshState = env.getProperty("foliage.export.refresh-state")?.takeIf { it.isNotBlank() }
            ?: state

        val backfill = env.getProperty("foliage.export.backfill", "false").toBoolean()
        val backfillStates = env.getProperty("foliage.export.backfill-states", "6").toInt()
        val backfillMinutes = env.getProperty("foliage.export.backfill-minutes", "90").toLong()

        log.info(
            "exporting {}, refresh scope {}",
            state?.let { "state $it" } ?: "the whole grid",
            refreshState?.let { "state $it" } ?: "the whole grid",
        )

        // On a scheduled run the data is refreshed first: pull the latest
        // observations and forecast, rescore the season, then export. On a
        // deploy triggered by a code push the database is already current, so
        // the refresh is skipped and the export takes seconds.
        val refresh = env.getProperty("foliage.export.refresh", "false").toBoolean()

        // DatabaseBootstrap degrades rather than aborting startup, which is
        // right for a dev server and wrong here: a batch job with no database
        // must fail loudly and say why, not fall through to an empty export
        // that reports "no cells".
        if (database.status.state != "connected") {
            log.error(
                "database is {} -- cannot export. Underlying error: {}",
                database.status.state,
                database.status.error ?: "none reported",
            )
            SpringApplication.exit(context, ExitCodeGenerator { 2 })
            Runtime.getRuntime().halt(2)
            return
        }
        log.info("database connected, schema {}", database.status.schemaVersion)

        val code = try {
            // Load the next few unfinished states before exporting. The free
            // Open-Meteo allowance covers roughly 650 res 5 parents a day and
            // the country needs 19,904, so this is a month of nightly runs
            // rather than one long job. It stops cleanly when the allowance or
            // the budget runs out and resumes here tomorrow.
            if (backfill) {
                val filled = weatherBackfill.run(backfillStates, Duration.ofMinutes(backfillMinutes))
                log.info(
                    "backfill: loaded {}, {} still untouched, stoppedOnQuota={}",
                    filled.statesLoaded, filled.statesUntouched, filled.stoppedOnQuota,
                )
            }

            if (refresh) {
                val ingest = weatherIngest.refreshForecast(refreshState)
                log.info("refreshed weather: {} rows, kinds {}", ingest.rowsWritten, ingest.byKind)
                val scored = forecastService.computeState(refreshState)
                log.info("rescored: {} rows in {} ms", scored.rowsWritten, scored.scoringMs)
            }
            val result = exporter.export(Path.of(path), state)
            log.info(
                "export complete: {} files, {} KB, {} days x {} cells",
                result.files, result.bytes / 1024, result.days, result.cells,
            )
            0
        } catch (e: Exception) {
            log.error("export failed", e)
            1
        }

        SpringApplication.exit(context, ExitCodeGenerator { code })
        // SpringApplication.exit returns the code without stopping the JVM when
        // a web server is running, so make the exit explicit.
        Runtime.getRuntime().halt(code)
    }
}
