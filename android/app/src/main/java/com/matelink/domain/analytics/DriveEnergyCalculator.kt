package com.matelink.domain.analytics

import java.time.Duration
import java.time.Instant
import com.matelink.util.parseIsoInstant

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
        var coverageMillis = 0L

        samples.zipWithNext().forEach { (start, end) ->
            val startTime = start.timestamp.toInstantOrNull()
            val endTime = end.timestamp.toInstantOrNull()
            val startPower = start.powerKw?.takeIf(Double::isFinite)
            val endPower = end.powerKw?.takeIf(Double::isFinite)

            if (startTime == null || endTime == null || startPower == null || endPower == null) {
                return@forEach
            }

            val intervalMillis = Duration.between(startTime, endTime)
                .toMillis()
                .takeIf { it > 0L }
                ?.coerceAtMost(MAX_INTERVAL_SECONDS * 1000)
                ?: return@forEach

            coverageMillis += intervalMillis
            energyKwh += ((startPower + endPower) / 2.0) * intervalMillis / 3_600_000.0
        }

        return DriveEnergyResult(
            energyKwh = energyKwh.takeIf { it > 0.0 },
            coverageSeconds = coverageMillis / 1000
        )
    }

    private fun String?.toInstantOrNull(): Instant? =
        this?.let { timestamp -> parseIsoInstant(timestamp) }
}
