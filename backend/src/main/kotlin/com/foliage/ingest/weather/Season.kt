package com.foliage.ingest.weather

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.time.MonthDay

/** The modelled foliage season, as concrete dates for a given year. */
@Component
class Season(
    @Value("\${foliage.season.start}") startMonthDay: String,
    @Value("\${foliage.season.end}") endMonthDay: String,
) {
    private val startMd: MonthDay = MonthDay.parse("--$startMonthDay")
    private val endMd: MonthDay = MonthDay.parse("--$endMonthDay")

    fun start(year: Int): LocalDate = startMd.atYear(year)
    fun end(year: Int): LocalDate = endMd.atYear(year)

    fun days(year: Int): List<LocalDate> =
        generateSequence(start(year)) { it.plusDays(1) }
            .takeWhile { !it.isAfter(end(year)) }
            .toList()
}
