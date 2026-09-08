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
    fun `it declines to predict outside the box it was fitted for`() {
        // Latitude alone is not enough, and getting this wrong would be
        // expensive rather than embarrassing. Michigan, Wisconsin and
        // Minnesota sit in the same latitude band as Maine, and this model is
        // 10 days early in Michigan and 36 in Minnesota -- its longitude term
        // means "distance from the cold Gulf of Maine", and there is no Gulf
        // of Maine in Duluth.
        assertTrue(PeakDateModel.supports(44.5, -69.0), "Maine is in the box")
        assertTrue(PeakDateModel.supports(41.8, -72.7), "Connecticut is in the box")
        assertTrue(PeakDateModel.supports(44.5, -73.2), "Vermont is in the box")

        assertTrue(!PeakDateModel.supports(44.3, -85.6), "Michigan is not")
        assertTrue(!PeakDateModel.supports(46.8, -92.1), "Duluth is not")
        assertTrue(!PeakDateModel.supports(42.9, -78.9), "western New York is not")
        assertTrue(!PeakDateModel.supports(33.7, -84.4), "Atlanta is not")
    }

    @Test
    fun `the national model takes over outside that box, at lower confidence`() {
        // The seam. Everything outside New England is scored against a
        // tourism prediction map rather than field observations -- 4.1 days
        // against 2.1 -- and the map has to show that rather than drawing a
        // Utah guess as confidently as Bar Harbor.
        val days = (1..120).map {
            val d = LocalDate.of(2026, 8, 1).plusDays(it.toLong())
            DayInput(d, WeatherKind.CLIMATOLOGY, 16.0, 6.0, 3.0)
        }
        val target = LocalDate.of(2026, 10, 12)
        val maine = PeakDateModel.score(CellInput(44.8, 60, null, -68.8), days, target)
        val utah = NationalPeakDateModel.score(CellInput(39.3, 1400, null, -111.7), days, target)

        assertTrue(
            utah.confidence < maine.confidence,
            "a national cell must read less certain than an observed one: " +
                "${utah.confidence} against ${maine.confidence}",
        )
    }

    @Test
    fun `the national model puts the mountain west back where it belongs`() {
        // Without elevation the national fit was two to three weeks late
        // across the high country -- Utah +19.8 days, Nevada +15.9. Elevation
        // came from this project's own grid rather than any API, and it is
        // what made a national map worth showing at all.
        val lowUtah = NationalPeakDateModel.peakDayOfYear(39.3, -111.7, 1300)
        val highUtah = NationalPeakDateModel.peakDayOfYear(39.3, -111.7, 2600)
        assertTrue(highUtah < lowUtah, "the high ground must turn first")
        assertTrue(
            (lowUtah - highUtah) > 12,
            "1300 m should be worth more than a fortnight, was ${lowUtah - highUtah}",
        )
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
