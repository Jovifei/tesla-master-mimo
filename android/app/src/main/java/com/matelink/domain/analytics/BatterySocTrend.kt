package com.matelink.domain.analytics

import com.matelink.data.local.entity.DriveSummary
import com.matelink.util.parseIsoInstant
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Daily battery-level trend for the dashboard.
 *
 * The dashboard shows the recorded battery history in place; it is not a
 * statistics screen. One point per day is the ending SOC of the last completed
 * drive that day. Days without a recorded drive stay empty — the live status
 * is never used to fabricate history.
 */
data class SocTrendPoint(
    val at: Instant,
    val day: LocalDate,
    val soc: Int
)

enum class SocTrendUnavailable {
    NO_HISTORY_IN_WINDOW,
    NO_QUALIFIED_SOC_SAMPLES
}

data class SocDailyTrendResult(
    val points: List<SocTrendPoint>,
    val drivesInWindow: Int,
    val change: Int?,
    val latestSoc: Int?,
    val unavailableReason: SocTrendUnavailable?
)

internal fun qualifiedDailySoc(value: Int): Int? = value.takeIf { it in 1..100 }

fun dailySocTrend(
    drives: List<DriveSummary>,
    windowDays: Int,
    now: Instant,
    zone: ZoneId = ZoneId.systemDefault()
): SocDailyTrendResult {
    val effectiveDays = if (windowDays == 30) 30 else 7
    val from = now.minus(Duration.ofDays(effectiveDays.toLong()))
    var drivesInWindow = 0
    val latestByDay = LinkedHashMap<LocalDate, SocTrendPoint>()

    for (drive in drives) {
        if (drive.qualityState == "quarantined") continue
        val at = parseIsoInstant(drive.endDate)
            ?: parseIsoInstant(drive.startDate)
            ?: continue
        if (at.isBefore(from) || at.isAfter(now)) continue
        drivesInWindow++
        val soc = qualifiedDailySoc(drive.endBatteryLevel)
            ?: qualifiedDailySoc(drive.startBatteryLevel)
            ?: continue
        val day = at.atZone(zone).toLocalDate()
        val previous = latestByDay[day]
        if (previous == null || previous.at.isBefore(at)) {
            latestByDay[day] = SocTrendPoint(at = at, day = day, soc = soc)
        }
    }

    val points = latestByDay.values.sortedBy { it.at }
    val change = if (points.size >= 2) points.last().soc - points.first().soc else null
    return SocDailyTrendResult(
        points = points,
        drivesInWindow = drivesInWindow,
        change = change,
        latestSoc = points.lastOrNull()?.soc,
        unavailableReason = when {
            points.isNotEmpty() -> null
            drivesInWindow == 0 -> SocTrendUnavailable.NO_HISTORY_IN_WINDOW
            else -> SocTrendUnavailable.NO_QUALIFIED_SOC_SAMPLES
        }
    )
}