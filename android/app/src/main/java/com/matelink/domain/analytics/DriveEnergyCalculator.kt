package com.matelink.domain.analytics

import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime

data class DrivePowerSample(
    val timestamp: String?,
    val powerKw: Double?
)

data class DriveEnergyResult(
    val energyKwh: Double?,
    val coverageSeconds: Long
)

object DriveEnergyCalculator {

    private const val MAX_INTERVAL_SECONDS = 30L

    fun calculate(samples: List<DrivePowerSample>): DriveEnergyResult {
        var energyKwh = 0.0
        var coverageSeconds = 0L

        samples.zipWithNext().forEach { (start, end) ->
            val startTime = start.timestamp.toInstantOrNull()
            val endTime = end.timestamp.toInstantOrNull()
            val startPower = start.powerKw?.takeIf(Double::isFinite)
            val endPower = end.powerKw?.takeIf(Double::isFinite)

            if (startTime == null || endTime == null || startPower == null || endPower == null) {
                return@forEach
            }

            val intervalSeconds = Duration.between(startTime, endTime)
                .seconds
                .takeIf { it > 0L }
                ?.coerceAtMost(MAX_INTERVAL_SECONDS)
                ?: return@forEach

            coverageSeconds += intervalSeconds
            energyKwh += ((startPower + endPower) / 2.0) * intervalSeconds / 3600.0
        }

        return DriveEnergyResult(
            energyKwh = energyKwh.takeIf { it > 0.0 },
            coverageSeconds = coverageSeconds
        )
    }

    private fun String?.toInstantOrNull(): Instant? =
        this?.let { timestamp -> runCatching { OffsetDateTime.parse(timestamp).toInstant() }.getOrNull() }
}
