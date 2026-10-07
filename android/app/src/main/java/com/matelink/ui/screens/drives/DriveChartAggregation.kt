package com.matelink.ui.screens.drives

import com.matelink.data.api.models.DriveData
import com.matelink.util.formatMonthYear
import com.matelink.util.formatShortNoYear
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.temporal.WeekFields
import java.util.Locale

enum class DriveChartGranularity { DAILY, WEEKLY, MONTHLY }

data class DriveChartData(
    val label: String,
    val count: Int,
    val totalDistance: Double,
    val totalDurationMin: Int,
    val maxSpeed: Int?,
    val sortKey: Long
)

/**
 * Group already eligible, distance-filtered records by their start day in the API request zone.
 * The selected end day is inclusive. A partial first week is retained, without admitting rows
 * outside the selected days. API distance/speed units and existing minute precision are preserved.
 */
internal fun calculateDriveChartData(
    drives: List<DriveData>,
    granularity: DriveChartGranularity,
    startDate: LocalDate?,
    endDate: LocalDate?,
    today: LocalDate,
    zone: ZoneId,
    locale: Locale,
    weekLabel: (Int) -> String
): List<DriveChartData> {
    val dated = drives.mapNotNull { drive ->
        val text = drive.startDate ?: return@mapNotNull null
        val day = runCatching { OffsetDateTime.parse(text).atZoneSameInstant(zone).toLocalDate() }
            .getOrElse { runCatching { LocalDateTime.parse(text).toLocalDate() }.getOrNull() }
        day?.let { it to drive }
    }
    if (dated.isEmpty()) return emptyList()
    val start = startDate ?: dated.minOf { it.first }
    val end = endDate ?: today
    if (start > end) return emptyList()
    val weekFields = WeekFields.of(locale)
    fun bucket(day: LocalDate): LocalDate = when (granularity) {
        DriveChartGranularity.DAILY -> day
        DriveChartGranularity.WEEKLY -> day.with(weekFields.dayOfWeek(), 1)
        DriveChartGranularity.MONTHLY -> day.withDayOfMonth(1)
    }
    val grouped = dated.filter { (day, _) -> day >= start && day <= end }
        .groupBy({ bucket(it.first) }, { it.second })
    val result = mutableListOf<DriveChartData>()
    var current = bucket(start)
    val last = bucket(end)
    while (current <= last) {
        val records = grouped[current].orEmpty()
        val label = when (granularity) {
            DriveChartGranularity.DAILY -> current.formatShortNoYear(locale)
            DriveChartGranularity.WEEKLY -> weekLabel(current.get(weekFields.weekOfYear()))
            DriveChartGranularity.MONTHLY -> current.formatMonthYear(locale, includeYear = start.year != end.year)
        }
        result += DriveChartData(
            label = label,
            count = records.size,
            totalDistance = records.sumOf { it.distance ?: 0.0 },
            totalDurationMin = records.sumOf { it.durationMin ?: 0 },
            maxSpeed = records.mapNotNull { it.speedMax?.takeIf { value -> value >= 0 } }.maxOrNull(),
            sortKey = current.toEpochDay()
        )
        if (current == last) break
        current = when (granularity) {
            DriveChartGranularity.DAILY -> current.plusDays(1)
            DriveChartGranularity.WEEKLY -> current.plusWeeks(1)
            DriveChartGranularity.MONTHLY -> current.plusMonths(1)
        }
    }
    return result
}
