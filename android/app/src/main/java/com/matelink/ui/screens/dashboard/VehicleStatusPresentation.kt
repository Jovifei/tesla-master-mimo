package com.matelink.ui.screens.dashboard

import com.matelink.data.api.models.CarStatus
import com.matelink.data.api.models.TpmsDetails
import com.matelink.data.api.models.Units
import com.matelink.data.local.TirePosition
import com.matelink.domain.model.UnitFormatter
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal enum class PowerDirection {
    CONSUMING,
    REGENERATING,
    STEADY
}

internal enum class ShiftState {
    DRIVE,
    REVERSE,
    NEUTRAL,
    PARK
}

internal enum class VehicleTrim {
    PERFORMANCE,
    LONG_RANGE,
    STANDARD_RANGE
}

internal enum class VehicleOpening {
    DOORS,
    WINDOWS,
    FRUNK,
    TRUNK
}

internal data class DrivingTelemetry(
    val speed: Double?,
    val power: Double?,
    val shiftState: ShiftState?
)

internal fun drivingTelemetryFor(status: CarStatus): DrivingTelemetry? {
    if (!status.state.equals("driving", ignoreCase = true)) return null

    val telemetry = DrivingTelemetry(
        speed = status.speed,
        power = status.power,
        shiftState = shiftStateFor(status.shiftState)
    )
    return telemetry.takeIf { it.speed != null || it.power != null || it.shiftState != null }
}

internal fun shiftStateFor(value: String?): ShiftState? = when (value?.trim()?.uppercase(Locale.ROOT)) {
    "D" -> ShiftState.DRIVE
    "R" -> ShiftState.REVERSE
    "N" -> ShiftState.NEUTRAL
    "P" -> ShiftState.PARK
    else -> null
}

internal fun powerDirection(value: Double?): PowerDirection? = when {
    value == null -> null
    value > 0 -> PowerDirection.CONSUMING
    value < 0 -> PowerDirection.REGENERATING
    else -> PowerDirection.STEADY
}

internal fun vehicleTrimFor(value: String?): VehicleTrim? {
    val trim = value?.trim()?.uppercase(Locale.ROOT) ?: return null
    return when {
        trim.startsWith("P") || trim.contains("PERFORMANCE") -> VehicleTrim.PERFORMANCE
        trim == "50" || trim.contains("STANDARD") -> VehicleTrim.STANDARD_RANGE
        trim == "74" || trim == "74D" || trim.contains("LONG") -> VehicleTrim.LONG_RANGE
        else -> null
    }
}

internal fun openVehicleOpenings(status: CarStatus): Set<VehicleOpening> = buildSet {
    status.carStatus?.let {
        if (it.doorsOpen == true) add(VehicleOpening.DOORS)
        if (it.windowsOpen == true) add(VehicleOpening.WINDOWS)
        if (it.frunkOpen == true) add(VehicleOpening.FRUNK)
        if (it.trunkOpen == true) add(VehicleOpening.TRUNK)
    }
}

internal fun shouldShowOpeningPanel(status: CarStatus): Boolean = openVehicleOpenings(status).isNotEmpty()

internal fun warningTires(tpms: TpmsDetails?): Set<TirePosition> = buildSet {
    if (tpms?.warningFl == true) add(TirePosition.FL)
    if (tpms?.warningFr == true) add(TirePosition.FR)
    if (tpms?.warningRl == true) add(TirePosition.RL)
    if (tpms?.warningRr == true) add(TirePosition.RR)
}

internal fun formatPressure(value: Double, units: Units): String =
    UnitFormatter.formatPressure(value, units)

internal fun locationDisplay(
    geofence: String?,
    cachedAddress: String?,
    latitude: Double?,
    longitude: Double?
): String? = geofence.cleanLocation() ?: cachedAddress.cleanLocation() ?:
    if (latitude != null && longitude != null) {
        "%.4f, %.4f".format(Locale.ROOT, latitude, longitude)
    } else {
        null
    }

internal fun formatSnapshotTime(value: String?): String? {
    val parsed = value?.let { runCatching { OffsetDateTime.parse(it) }.getOrNull() } ?: return null
    val formatter = DateTimeFormatter.ofPattern("MM-dd HH:mm", Locale.getDefault())
    return parsed.atZoneSameInstant(ZoneId.systemDefault()).format(formatter)
}

private fun String?.cleanLocation(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
