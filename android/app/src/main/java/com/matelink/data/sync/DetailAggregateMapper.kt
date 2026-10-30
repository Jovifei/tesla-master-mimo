package com.matelink.data.sync

import com.matelink.data.api.models.ChargeDetail
import com.matelink.data.api.models.ChargerDetails
import com.matelink.data.api.models.DriveDetail
import com.matelink.data.local.entity.ChargeDetailAggregate
import com.matelink.data.local.entity.DriveDetailAggregate
import com.matelink.data.local.entity.SchemaVersion
import com.matelink.ui.screens.charges.ChargeStatsCalculator
import com.matelink.ui.screens.charges.ChargeType
import com.matelink.domain.telemetry.usableVehicleCoordinates
import kotlin.math.roundToInt

internal fun DriveDetail.toAggregate(carId: Int, computedAt: Long): DriveDetailAggregate {
    val positions = positions.orEmpty()
    val elevations = positions.mapNotNull { it.elevation }
    val insideTemperatures = positions.mapNotNull { com.matelink.data.api.models.canonicalAggregateTemperatureC(it.insideTemp, temperatureUnit, source) }
    val outsideTemperatures = positions.mapNotNull { com.matelink.data.api.models.canonicalAggregateTemperatureC(it.outsideTemp, temperatureUnit, source) }
    val powers = positions.mapNotNull { it.power }
    val firstCoordinate = positions.firstOrNull { usableVehicleCoordinates(it.latitude, it.longitude) != null }
    val lastCoordinate = positions.lastOrNull { usableVehicleCoordinates(it.latitude, it.longitude) != null }
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
        schemaVersion = SchemaVersion.DRIVE_ENERGY,
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
        maxPower = powers.maxOrNull()?.roundToInt(),
        minPower = powers.minOrNull()?.roundToInt(),
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
    val outsideTemperatures = points.mapNotNull { com.matelink.data.api.models.canonicalAggregateTemperatureC(it.outsideTemp, temperatureUnit, source) }
    // Share the detail consumer's existing conflict/contract arbiter, rather than any/max hints.
    val detectedType = ChargeStatsCalculator.detectChargeType(this)
    val observedPhases = when (detectedType) {
        ChargeType.AC -> chargerDetails.mapNotNull { it.chargerPhases }.filter { it in 1..3 }.maxOrNull()
        ChargeType.DC -> chargerDetails.mapNotNull { it.chargerPhases }.firstOrNull { it == 0 }
        ChargeType.UNKNOWN -> null
    }

    return ChargeDetailAggregate(
        chargeId = chargeId,
        carId = carId,
        schemaVersion = SchemaVersion.CURRENT,
        computedAt = computedAt,
        isFastCharger = detectedType == ChargeType.DC,
        fastChargerBrand = chargerDetails.firstNonBlank { it.fastChargerBrand },
        connectorType = chargerDetails.firstNonBlank { it.fastChargerType },
        maxChargerPower = chargerDetails.mapNotNull { it.chargerPower }.maxOrNull()?.roundToInt(),
        maxChargerVoltage = chargerDetails.mapNotNull { it.chargerVoltage }.maxOrNull()?.roundToInt(),
        maxChargerCurrent = chargerDetails.mapNotNull { it.chargerActualCurrent }.maxOrNull()?.roundToInt(),
        chargerPhases = observedPhases,
        maxOutsideTemp = outsideTemperatures.maxOrNull(),
        minOutsideTemp = outsideTemperatures.minOrNull(),
        chargePointCount = points.size
    )
}

private fun List<ChargerDetails>.firstNonBlank(value: (ChargerDetails) -> String?): String? =
    firstNotNullOfOrNull { details -> value(details)?.trim()?.takeIf(String::isNotEmpty) }
