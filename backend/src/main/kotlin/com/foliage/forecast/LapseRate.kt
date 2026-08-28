package com.foliage.forecast

/**
 * Downscales res 5 weather onto a res 6 cell's own elevation.
 *
 * This is the whole reason hexagons beat county averages. Open-Meteo is
 * ~9–25 km native, so a single res 5 cell can span a valley floor and a ridge
 * line — in Vermont that is easily 700 m, which is about 4.5 °C, which is
 * one to two weeks of difference in when colour arrives.
 *
 * Retired in phase 6: HRRR is natively 3 km, so res 6 cells get their own
 * forecast and there is nothing left to downscale.
 */
object LapseRate {

    /** Environmental lapse rate, °C per 1000 m. The standard tropospheric mean. */
    const val C_PER_KM = 6.5

    /**
     * Temperature at [cellElevationM] given a value observed at
     * [referenceElevationM]. Returns the input unchanged when either
     * elevation is unknown, rather than guessing.
     */
    fun adjust(temperatureC: Double?, cellElevationM: Int?, referenceElevationM: Double?): Double? {
        if (temperatureC == null || cellElevationM == null || referenceElevationM == null) return temperatureC
        val deltaKm = (cellElevationM - referenceElevationM) / 1000.0
        return temperatureC - C_PER_KM * deltaKm
    }
}
