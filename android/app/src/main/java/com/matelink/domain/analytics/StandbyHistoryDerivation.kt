package com.matelink.domain.analytics

import com.matelink.data.api.models.ChargeData
import com.matelink.data.api.models.DriveData
import com.matelink.data.api.models.StandbyWindowData
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime

/**
 * Builds conservative standby candidates from history already held by the device.
 * Energy and power stay unknown because drive history does not measure parked load.
 */
fun buildLocalStandbyWindows(
    drives: List<DriveData>,
    charges: List<ChargeData>
): List<StandbyWindowData> {
    val timedDrives = drives.mapNotNull { drive ->
        val start = parseInstant(drive.startDate)
        val end = parseInstant(drive.endDate)
        if (start == null || end == null || !end.isAfter(start)) null else TimedDrive(drive, start, end)
    }.sortedWith(compareBy({ it.end }, { it.start }, { it.drive.driveId }))

    return timedDrives.zipWithNext().mapNotNull { (previous, next) ->
        val start = previous.end
        val end = next.start
        if (!end.isAfter(start) || charges.any { it.overlaps(start, end) }) return@mapNotNull null

        val startBattery = previous.drive.endBatteryLevel
        val endBattery = next.drive.startBatteryLevel
        StandbyWindowData(
            startDate = previous.drive.endDate ?: return@mapNotNull null,
            endDate = next.drive.startDate ?: return@mapNotNull null,
            address = previous.drive.endAddress ?: next.drive.startAddress,
            durationSeconds = Duration.between(start, end).seconds,
            startBatteryLevel = startBattery,
            endBatteryLevel = endBattery,
            batteryDelta = if (startBattery != null && endBattery != null) endBattery - startBattery else null,
            energyKwh = null,
            averagePowerW = null,
            peakPowerW = null,
            coverageRatio = 0.0,
            insideTempAverage = null,
            outsideTempAverage = null,
            climateActiveSampleCount = 0,
            climateSampleCount = 0,
            source = "local_history"
        )
    }
}

private data class TimedDrive(
    val drive: DriveData,
    val start: Instant,
    val end: Instant
)

private fun ChargeData.overlaps(windowStart: Instant, windowEnd: Instant): Boolean {
    val chargeStart = parseInstant(startDate) ?: return false
    val chargeEnd = parseInstant(endDate) ?: Instant.MAX
    return chargeStart.isBefore(windowEnd) && chargeEnd.isAfter(windowStart)
}

private fun parseInstant(value: String?): Instant? {
    val text = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
    return runCatching { OffsetDateTime.parse(text).toInstant() }
        .getOrElse { runCatching { Instant.parse(text) }.getOrNull() }
}
