package com.matelink.data.sync

import com.matelink.data.api.models.ChargeDetail
import com.matelink.data.api.models.ChargerDetails
import com.matelink.data.api.models.DriveDetail
import com.matelink.data.local.entity.ChargeDetailAggregate
import com.matelink.data.local.entity.DriveDetailAggregate
import com.matelink.data.local.entity.SchemaVersion
import kotlin.math.roundToInt

internal fun DriveDetail.toAggregate(carId: Int, computedAt: Long): DriveDetailAggregate {
    val positions = positions.orEmpty()
    val elevations = positions.mapNotNull { it.elevation }
    val insideTemperatures = positions.mapNotNull { it.insideTemp }
    val outsideTemperatures = positions.mapNotNull { it.outsideTemp }
    val powers = positions.mapNotNull { it.power }
    val firstCoordinate = positions.firstOrNull { it.latitude != null && it.longitude != null }
    val lastCoordinate = positions.lastOrNull { it.latitude != null && it.longitude != null }
    var elevationGain = 0
    var elevationLoss = 0

    positions.zipWithNext().forEach { (start, end) ->
        val startElevation = start.elevation ?: return@forEach
        val endElevation = end.elevation ?: return@forEach
        val delta = endElevation - startElevation
        if (delta > 0) elevationGain += delta else elevationLoss += -delta
    }

    return DriveDetailAggregate(
        driveId = driveId,
        carId = carId,
        schemaVersion = SchemaVersion.CURRENT,
        computedAt = computedAt,
        maxElevation = elevations.maxOrNull(),
        minElevation = elevations.minOrNull(),
        startElevation = positions.firstOrNull()?.elevation,
        endElevation = positions.lastOrNull()?.elevation,
        elevationGain = elevationGain.takeIf { elevations.isNotEmpty() },
        elevationLoss = elevationLoss.takeIf { elevations.isNotEmpty() },
        hasElevationData = elevations.isNotEmpty(),
        maxInsideTemp = insideTemperatures.maxOrNull(),
        minInsideTemp = insideTemperatures.minOrNull(),
        maxOutsideTemp = outsideTemperatures.maxOrNull(),
        minOutsideTemp = outsideTemperatures.minOrNull(),
        maxPower = powers.maxOrNull(),
        minPower = powers.minOrNull(),
        climateOnPositions = positions.count { it.isClimateOn },
        positionCount = positions.size,
        startLatitude = firstCoordinate?.latitude,
        startLongitude = firstCoordinate?.longitude,
        endLatitude = lastCoordinate?.latitude,
        endLongitude = lastCoordinate?.longitude
    )
}

internal fun ChargeDetail.toAggregate(carId: Int, computedAt: Long): ChargeDetailAggregate {
    val points = chargePoints.orEmpty()
    val chargerDetails = points.mapNotNull { it.chargerDetails }
    val outsideTemperatures = points.mapNotNull { it.outsideTemp }
    val isFastCharger = chargerDetails.any { details ->
        details.fastChargerPresent == true ||
            (details.chargerPower != null && (details.chargerPhases == null || details.chargerPhases == 0))
    }

    return ChargeDetailAggregate(
        chargeId = chargeId,
        carId = carId,
        schemaVersion = SchemaVersion.CURRENT,
        computedAt = computedAt,
        isFastCharger = isFastCharger,
        fastChargerBrand = chargerDetails.firstNonBlank { it.fastChargerBrand },
        connectorType = chargerDetails.firstNonBlank { it.fastChargerType },
        maxChargerPower = chargerDetails.mapNotNull { it.chargerPower }.maxOrNull()?.roundToInt(),
        maxChargerVoltage = chargerDetails.mapNotNull { it.chargerVoltage }.maxOrNull()?.roundToInt(),
        maxChargerCurrent = chargerDetails.mapNotNull { it.chargerActualCurrent }.maxOrNull()?.roundToInt(),
        chargerPhases = chargerDetails.mapNotNull { it.chargerPhases }.maxOrNull(),
        maxOutsideTemp = outsideTemperatures.maxOrNull(),
        minOutsideTemp = outsideTemperatures.minOrNull(),
        chargePointCount = points.size
    )
}

private fun List<ChargerDetails>.firstNonBlank(value: (ChargerDetails) -> String?): String? =
    firstNotNullOfOrNull { details -> value(details)?.trim()?.takeIf(String::isNotEmpty) }
