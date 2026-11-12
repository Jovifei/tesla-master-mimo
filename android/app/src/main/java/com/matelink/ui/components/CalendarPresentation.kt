package com.matelink.ui.components

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Calendar slots are presentation only: an empty slot never asserts no vehicle activity. */
internal fun <T> calendarHistoryGroups(
    groups: List<HistoryDateGroup<T>>, start: LocalDate?, end: LocalDate?
): List<HistoryDateGroup<T>> {
    if (start == null || end == null || start.isAfter(end) || ChronoUnit.DAYS.between(start, end) > 365)
        return groups
    val byDate = groups.filter { it.date != null }.groupBy { it.date!! }
    val result = mutableListOf<HistoryDateGroup<T>>()
    var date: LocalDate = end
    while (!date.isBefore(start)) {
        result += HistoryDateGroup(date, date.toString(), byDate[date].orEmpty().flatMap { it.items })
        date = date.minusDays(1)
    }
    // Retain undated and out-of-window boundary rows; do not discard real records in presentation.
    result += groups.filter { it.date == null || it.date.isBefore(start) || it.date.isAfter(end) }
    return result
}

/** A fixed legend: 40 kWh always has the same shade, regardless of the other returned days. */
internal fun chargeEnergyShade(value: Double?): Float = when {
    value == null || !value.isFinite() || value <= 0.0 -> 0f
    else -> (value / 60.0).coerceIn(0.0, 1.0).toFloat()
}
