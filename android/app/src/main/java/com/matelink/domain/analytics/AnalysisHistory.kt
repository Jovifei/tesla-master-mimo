package com.matelink.domain.analytics

import java.time.LocalDate

/** A source record that can be de-duplicated and assigned to a calendar window. */
interface DatedSourceRecord {
    val id: Int
    val date: LocalDate
}

enum class AnalysisWindow {
    ALL_TIME,
    LAST_90_DAYS,
    SUMMER,
    WINTER,
    CUSTOM
}

enum class NoDataReason {
    NO_RECORDS,
    INSUFFICIENT_COVERAGE,
    SOURCE_UNAVAILABLE
}

data class HistoryCoverage(
    val driveCount: Int,
    val chargeCount: Int,
    val reason: NoDataReason? = null
) {
    val isEmpty: Boolean get() = driveCount == 0 && chargeCount == 0
}

fun <T : DatedSourceRecord> uniqueBySourceId(records: List<T>): List<T> =
    records.distinctBy { it.id }

fun <T : DatedSourceRecord> selectWindow(
    records: List<T>,
    window: AnalysisWindow,
    asOf: LocalDate,
    customStart: LocalDate? = null,
    customEnd: LocalDate? = null
): List<T> = records.filter { record ->
    when (window) {
        AnalysisWindow.ALL_TIME -> true
        AnalysisWindow.LAST_90_DAYS -> {
            val start = asOf.minusDays(89)
            !record.date.isBefore(start) && !record.date.isAfter(asOf)
        }
        AnalysisWindow.SUMMER -> record.date.monthValue in 6..8
        AnalysisWindow.WINTER -> record.date.monthValue == 12 || record.date.monthValue in 1..2
        AnalysisWindow.CUSTOM -> {
            val start = customStart ?: return@filter false
            val end = customEnd ?: return@filter false
            !record.date.isBefore(start) && !record.date.isAfter(end)
        }
    }
}
