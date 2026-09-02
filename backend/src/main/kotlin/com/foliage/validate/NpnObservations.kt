package com.foliage.validate

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import java.time.LocalDate
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient

/** One volunteer's record of what a named plant looked like on a given day. */
data class LeafColourObservation(
    val date: LocalDate,
    val latitude: Double,
    val longitude: Double,
    /** Share of the canopy coloured, from NPN's intensity bucket. */
    val percentColored: Double,
    val commonName: String,
    val genus: String?,
    val species: String?,
) {
    /** "red maple" reads better in a report than "Acer rubrum". */
    val label: String get() = commonName.ifBlank { listOfNotNull(genus, species).joinToString(" ") }
}

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class NpnRecord(
    val observation_date: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val phenophase_id: Int? = null,
    val phenophase_status: Int? = null,
    val intensity_value: String? = null,
    val common_name: String? = null,
    val genus: String? = null,
    val species: String? = null,
)

/**
 * Fetches leaf-colour observations from the USA National Phenology Network.
 *
 * **Filtered here, not there.** The portal accepts a `phenophase_id` parameter
 * and returns nothing at all when it is set, so this asks for a state and a
 * date range and selects the leaf-colour records itself. That sounds wasteful
 * and is not: a whole season of every phenophase in Vermont -- birds, insects,
 * flowering, everything -- is 1,383 records, of which 68 are leaf colour.
 * Fighting an undocumented filter to save a few hundred kilobytes would have
 * cost more than it saved, and would break silently the day the parameter
 * starts working.
 *
 * The host matters too. `www.usanpn.org/npn_portal/...` is what the
 * documentation points at and it serves a 404 page with a 200-shaped body;
 * the working host is `services.usanpn.org`.
 */
@Component
class NpnObservations(
    private val restClient: RestClient,
    @Value("\${foliage.validation.npn-url}") private val baseUrl: String,
    @Value("\${foliage.validation.request-src}") private val requestSrc: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun forState(state: String, from: LocalDate, to: LocalDate): List<LeafColourObservation> {
        // Built as a URI, not handed over as a string.
        //
        // RestClient treats a String uri as a template and encodes it again, so
        // the `state[0]` parameter arrives as `state%255B0%255D` -- a name the
        // portal does not recognise. It does not reject the request for that;
        // it ignores the filter and returns the whole country. The first run
        // reported 144,862 leaf-colour observations "for Vermont", which is
        // roughly seven hundred times what Vermont has, and the only thing that
        // gave it away was the size of the number.
        val url = java.net.URI(
            "$baseUrl/observations/getObservations.json" +
                "?request_src=$requestSrc&start_date=$from&end_date=$to&state[0]=$state",
        )

        val raw = try {
            restClient.get().uri(url).retrieve().body(Array<NpnRecord>::class.java)
        } catch (e: Exception) {
            // One state failing should not abandon the run. A validation pass
            // that covers 44 states is still a validation pass; one that throws
            // on the first timeout tells you nothing at all.
            log.warn("NPN fetch failed for {}: {}", state, e.message)
            return emptyList()
        } ?: return emptyList()

        return raw.mapNotNull { r ->
            if (r.phenophase_id != ColoredLeaves.PHENOPHASE_COLORED_LEAVES) return@mapNotNull null
            // status 1 is "yes, this was happening". 0 is an equally careful
            // observation that it was not, and -1 is "did not look" -- neither
            // says anything about how much colour there was.
            if (r.phenophase_status != 1) return@mapNotNull null
            val pct = ColoredLeaves.percentColored(r.intensity_value) ?: return@mapNotNull null
            val date = r.observation_date?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                ?: return@mapNotNull null
            val lat = r.latitude ?: return@mapNotNull null
            val lon = r.longitude ?: return@mapNotNull null

            LeafColourObservation(
                date = date,
                latitude = lat,
                longitude = lon,
                percentColored = pct,
                commonName = r.common_name.orEmpty(),
                genus = r.genus,
                species = r.species,
            )
        }
    }
}
