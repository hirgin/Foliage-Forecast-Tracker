package com.foliage.config

import org.yaml.snakeyaml.Yaml
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Guards the one config property whose misconfiguration is silent and
 * expensive.
 *
 * `foliage.grid.states` scopes both scoring and export. Written as a YAML list
 * -- `["09", "23"]` -- Spring stores it as indexed properties, `states[0]` and
 * `states[1]`, so the placeholder `${foliage.grid.states}` resolves to nothing,
 * `@Value` falls through to its empty default, and an empty list means *every
 * state*. Nothing fails. The application starts, the export runs, the deploy
 * reports success, and 141,274 cells are published instead of 4,782.
 *
 * That is exactly what happened: the first attempt used a YAML list and
 * published all 48 states under a New England model, which is the seamed map
 * the scoping existed to prevent.
 *
 * Reading the file rather than standing up a context, deliberately -- the
 * failure is in how the value is *written*, and a context test needs a
 * database this project does not have locally.
 */
class CoveredStatesConfigTest {

    // loadAll, not load: the file carries a second document after `---` for
    // the profile block, and snakeyaml refuses a multi-document stream
    // otherwise. Only the first document holds foliage.grid.
    @Suppress("UNCHECKED_CAST")
    private val yaml: Map<String, Any> =
        Yaml().loadAll(javaClass.getResourceAsStream("/application.yml")!!)
            .first() as Map<String, Any>

    @Suppress("UNCHECKED_CAST")
    private val states: Any? =
        ((yaml["foliage"] as? Map<String, Any>)?.get("grid") as? Map<String, Any>)?.get("states")

    @Test
    fun `the covered states are a comma-separated string, not a YAML list`() {
        assertTrue(states != null, "foliage.grid.states is missing entirely")
        assertTrue(
            states !is List<*>,
            "foliage.grid.states is a YAML list, which @Value cannot read -- it will " +
                "silently bind empty and publish every state. Write it as \"09,23,25\".",
        )
        assertTrue(states is String, "expected a String, was ${states!!::class.simpleName}")
    }

    @Test
    fun `it names states explicitly, always including New England`() {
        val fips = (states as String).split(",").map { it.trim() }
        // Connecticut, Maine, Massachusetts, New Hampshire, Rhode Island,
        // Vermont -- the six with observations behind them. Leading zeros
        // matter: Connecticut is "09", and an unquoted YAML number would make
        // it 9 and silently drop the state.
        assertTrue(
            fips.containsAll(listOf("09", "23", "25", "33", "44", "50")),
            "the observed states must always be covered, got $fips",
        )
        // Explicit rather than empty. Empty means every state *and* is what a
        // failed binding produces, so the two would be indistinguishable.
        assertTrue(fips.size >= 6, "expected an explicit list, got $fips")
        assertTrue(fips.all { it.length == 2 }, "FIPS codes are two characters: $fips")
    }
}
