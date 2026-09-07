package com.foliage.forecast

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.foliage.domain.WeatherKind
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The model against twelve seasons of observed Maine foliage.
 *
 * The model this replaces was tested against published *forecast* windows 9 to
 * 17 days wide, and a window that wide is satisfied by landing at its early
 * edge -- which it did, at every reference town, for its whole life, while
 * scoring 15 of 18 and running a week early on the map. These targets are
 * single dates from field observations, so a week of error is a week of error.
 */
class PeakDateModelTest {

    private data class Zone(
        val region: String,
        val years: Int,
        val medianPeak: String,
        val earliest: String,
        val latest: String,
    )

    private data class Observed(val zones: Map<String, Zone>)

    // The fixture carries provenance fields the test does not need; it is a
    // record for people as much as an input for code.
    private val observed: Observed = jacksonObjectMapper()
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .readValue(javaClass.getResourceAsStream("/fixtures/maine-observed-peaks.json")!!)

    /** A representative place in each Maine zone: latitude, longitude, elevation. */
    private val places = listOf(
        Triple("7", Triple(47.25, -68.59, 158), "Fort Kent"),
        Triple("6", Triple(46.68, -68.02, 134), "Presque Isle"),
        Triple("5", Triple(45.46, -69.59, 318), "Greenville"),
        Triple("3", Triple(44.80, -68.77, 3), "Bangor"),
        Triple("2", Triple(44.39, -68.20, 0), "Bar Harbor"),
        Triple("1", Triple(43.66, -70.26, 8), "Portland"),
    )

    private fun predicted(p: Triple<Double, Double, Int>): LocalDate =
        LocalDate.ofYearDay(2026, PeakDateModel.peakDayOfYear(p.first, p.second, p.third).toInt())

    @Test
    fun `predicted peak dates match what Maine observed`() {
        val errors = places.map { (zone, latElev, name) ->
            val target = LocalDate.parse(observed.zones.getValue(zone).medianPeak)
            name to ChronoUnit.DAYS.between(target, predicted(latElev)).toInt()
        }
        // Three days, not one. The fit now spans Maine and New Hampshire,
        // whose records disagree by eight days at the same latitude, so it
        // splits the difference -- and three days is inside the resolution of
        // the targets, which are weekly reports and five-day windows.
        val mae = errors.sumOf { abs(it.second) }.toDouble() / errors.size
        assertTrue(mae < 4.0, "mean absolute error against observed medians is $mae days: $errors")

        // No single zone badly wrong either. Bar Harbor is the known weak
        // point -- the coast peaks later than latitude and elevation can
        // explain, and a maritime term is the obvious next improvement.
        val worst = errors.maxBy { abs(it.second) }
        assertTrue(abs(worst.second) <= 7, "${worst.first} is off by ${worst.second} days")
    }

    @Test
    fun `the north peaks before the coast`() {
        // The ordering is the thing a map lives or dies by, and it survives
        // independently of whether the absolute dates are right.
        val fortKent = predicted(Triple(47.25, -68.59, 158))
        val bangor = predicted(Triple(44.80, -68.77, 3))
        val portland = predicted(Triple(43.66, -70.26, 8))
        assertTrue(fortKent < bangor, "Fort Kent $fortKent should precede Bangor $bangor")
        assertTrue(bangor < portland, "Bangor $bangor should precede Portland $portland")
    }

    @Test
    fun `higher ground turns earlier`() {
        // Same latitude, 500 m apart. This is where the map's texture comes
        // from, and no published source has the resolution to check it.
        val valley = PeakDateModel.peakDayOfYear(44.5, -71.0, 100)
        val ridge = PeakDateModel.peakDayOfYear(44.5, -71.0, 600)
        assertTrue(ridge < valley, "ridge $ridge should peak before valley $valley")
        assertEquals(10, (valley - ridge).toInt(), "500 m should be about 10 days")
    }

    @Test
    fun `Downeast Maine peaks after the southern coast, as its foresters record`() {
        // The property a distance-to-coast term could not express and this one
        // exists for. Maine records Zone 2 (Downeast) at 18 October and Zone 1
        // (south coast) at the 15th -- the *northern* coast peaks last, which
        // latitude alone must get backwards.
        val barHarbor = PeakDateModel.peakDayOfYear(44.39, -68.20, 0)
        val portland = PeakDateModel.peakDayOfYear(43.66, -70.26, 8)
        assertTrue(
            barHarbor > portland,
            "Bar Harbor $barHarbor should peak after Portland $portland, " +
                "despite sitting 0.7 degrees further north",
        )
    }

    @Test
    fun `the coast holds its colour later than inland`() {
        // Latitude and elevation alone put Portsmouth and Bar Harbor almost
        // six days early, every one of the worst residuals sitting on the
        // water. The sea keeps autumn nights warmer for weeks.
        val downeast = PeakDateModel.peakDayOfYear(44.0, -68.3, 10)
        val inland = PeakDateModel.peakDayOfYear(44.0, -70.6, 10)
        assertTrue(downeast > inland, "Downeast $downeast should peak after inland $inland")
    }

    @Test
    fun `peak lasts about ten days`() {
        // A property the old model produced by accident, at anywhere from 5 to
        // 11 days depending on constants fitted for other purposes. Here it is
        // set by WIDTH_DAYS and can be asserted.
        val peak = PeakDateModel.peakDayOfYear(44.5, -71.0, 200)
        val days = (1..365).map { LocalDate.ofYearDay(2026, it) }
        val inPeak = days.count { d ->
            val p = PeakDateModel.progressionOn(d, peak)
            p >= 75.0 && p < PhenologyModel.PAST_PEAK_PROGRESSION
        }
        assertTrue(inPeak in 8..13, "peak lasted $inPeak days")
    }

    @Test
    fun `it declines to predict outside the latitudes it was fitted for`() {
        // Fitted on nine Maine places. Georgia is not one of them, and the
        // model this replaces had no such limit -- it scored Florida from
        // constants fitted in Vermont and nobody noticed for a month.
        assertTrue(PeakDateModel.supports(44.5), "Maine is in range")
        assertTrue(PeakDateModel.supports(41.8), "Connecticut is in range")
        assertTrue(!PeakDateModel.supports(33.7), "Atlanta is not")
        assertTrue(!PeakDateModel.supports(29.2), "Florida is not")
    }

    @Test
    fun `weather changes how vivid it looks, never when it turns`() {
        // The whole point of the rebuild. In the old model a warm autumn moved
        // the date, and a small rate error compounded into a fortnight by
        // October. Here the date is an input and weather cannot touch it.
        val cell = CellInput(44.5, 200, null, -71.0)
        val target = LocalDate.of(2026, 10, 10)
        fun season(meanC: Double, spreadC: Double) = (1..120).map {
            val d = LocalDate.of(2026, 8, 1).plusDays(it.toLong())
            DayInput(d, WeatherKind.CLIMATOLOGY, meanC + spreadC / 2, meanC - spreadC / 2, 3.0)
        }

        // Sixteen degrees of difference in the seasonal mean moves the date by
        // nothing at all. Under the cooling model this was the entire
        // mechanism, and the reason a mild autumn drifted two weeks early.
        val cold = PeakDateModel.score(cell, season(4.0, 10.0), target)
        val warm = PeakDateModel.score(cell, season(20.0, 10.0), target)
        assertEquals(cold.progression, warm.progression, "timing must not move with weather")

        // Vividness still responds, to the day-night spread that actually
        // drives it -- not to the mean, which is why the two seasons above
        // look identical and these two do not.
        val flat = PeakDateModel.score(cell, season(12.0, 4.0), target)
        val sharp = PeakDateModel.score(cell, season(12.0, 16.0), target)
        assertEquals(flat.progression, sharp.progression, "still the same timing")
        assertTrue(
            sharp.intensity > flat.intensity,
            "a wide day-night spread should look more vivid: " +
                "${sharp.intensity} against ${flat.intensity}",
        )
    }
}
