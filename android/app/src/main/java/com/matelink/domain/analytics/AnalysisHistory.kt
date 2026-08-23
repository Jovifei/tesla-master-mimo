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
    COLLECTING,
    INSUFFICIENT_COVERAGE,
    FILTER_EMPTY,
    SOURCE_UNAVAILABLE
}

/**
 * Classifies an empty history response without turning a provider state into
 * a misleading zero-valued metric.
 *
 * The availability strings are intentionally kept open because compatible
 * providers may add values before the app is updated. Unknown or missing
 * metadata keeps the legacy NO_RECORDS behavior.
 */
fun classifyEmptyHistory(
    driveCount: Int,
    chargeCount: Int,
    driveAvailability: String? = null,
    chargeAvailability: String? = null
): NoDataReason? {
    if (driveCount > 0 || chargeCount > 0) return null

    val availability = listOfNotNull(driveAvailability, chargeAvailability)
    return when {
        availability.any { it.equals("collecting", ignoreCase = true) } ->
            NoDataReason.COLLECTING
        availability.isNotEmpty() && availability.all {
            it.equals("unsupported", ignoreCase = true)
        } -> NoDataReason.SOURCE_UNAVAILABLE
        else -> NoDataReason.NO_RECORDS
    }
}

/**
 * Keeps provider-empty, filter-empty and field-incomplete states distinct.
 * A record count alone must not make an analysis metric look observed.
 */
fun classifyMetricNoData(
    historyReason: NoDataReason?,
    sourceRecordCount: Int,
    selectedRecordCount: Int,
    validSampleCount: Int
): NoDataReason? = when {
    validSampleCount > 0 -> null
    sourceRecordCount <= 0 -> historyReason ?: NoDataReason.NO_RECORDS
    selectedRecordCount <= 0 -> NoDataReason.FILTER_EMPTY
    else -> NoDataReason.INSUFFICIENT_COVERAGE
}

enum class HistoryFreshness {
    FRESH,
    STALE
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
