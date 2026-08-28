package com.foliage.forecast

import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * Day length, by the Forsythe et al. (1995) model.
 *
 * Photoperiod is the primary trigger of autumn senescence and the only driver
 * in this model that is fully deterministic: it depends on latitude and date
 * alone, so it is known exactly for every day of the season regardless of how
 * far past the forecast horizon that day is. That matters — it is what keeps
 * an October estimate meaningful in August (ADR-0005).
 */
object Photoperiod {

    /** Daylight coefficient: 0.833° accounts for refraction and the solar disc. */
    private const val DAYLIGHT_COEFF = 0.833
    private const val DEG = Math.PI / 180.0

    /** Hours between sunrise and sunset. */
    fun hours(latitude: Double, date: LocalDate): Double {
        val j = date.dayOfYear.toDouble()

        val revolutionAngle = 0.2163108 + 2 * atan(0.9671396 * tan(0.00860 * (j - 186)))
        val declination = asin(0.39795 * cos(revolutionAngle))

        val numerator = sin(DAYLIGHT_COEFF * DEG) + sin(latitude * DEG) * sin(declination)
        val denominator = cos(latitude * DEG) * cos(declination)

        // Inside the polar circles the sun may not rise or set at all, and the
        // ratio leaves acos's domain. daylength = 24 - (24/pi) * acos(ratio),
        // so ratio >= 1 gives acos = 0 and a 24 h day (midnight sun), while
        // ratio <= -1 gives acos = pi and no day at all (polar night).
        val ratio = numerator / denominator
        if (ratio >= 1.0) return 24.0
        if (ratio <= -1.0) return 0.0

        return 24.0 - (24.0 / Math.PI) * acos(ratio)
    }

    /**
     * How far day length has fallen below the threshold at which temperate
     * broadleaf senescence is generally considered to begin, in hours.
     * Zero before the threshold is crossed.
     */
    fun senescenceForcing(latitude: Double, date: LocalDate, thresholdHours: Double = 13.0): Double =
        (thresholdHours - hours(latitude, date)).coerceAtLeast(0.0)

    /** Change in day length across a day, in minutes. Negative while shortening. */
    fun dailyChangeMinutes(latitude: Double, date: LocalDate): Double =
        (hours(latitude, date.plusDays(1)) - hours(latitude, date.minusDays(1))) * 60 / 2

    internal fun approxEquals(a: Double, b: Double, tolerance: Double) = abs(a - b) <= tolerance
}
