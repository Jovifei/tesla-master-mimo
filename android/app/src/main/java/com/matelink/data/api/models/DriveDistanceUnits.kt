package com.matelink.data.api.models

private const val KM_PER_MILE = 1.609344

/** Missing metadata retains the established cloud/legacy kilometre contract.
 * Explicit unsupported metadata never borrows a later status preference.
 */
internal fun knownDriveLengthUnit(unit: String?): Boolean = unit == null || unit == "km" || unit == "mi"

/** Existing unitless kilometre receipts and explicit km share one physical unit. */
internal fun sameDriveLengthUnit(first: String?, second: String?): Boolean =
    (first ?: "km") == (second ?: "km")

/** Length belongs to this receipt, temperature preferences remain independent. */
internal fun drivePresentationUnits(lengthUnit: String?, preferences: Units?): Units =
    (preferences ?: Units()).copy(unitOfLength = lengthUnit ?: "km")

internal fun driveDistanceKm(value: Double?, unit: String?): Double? =
    value?.takeIf { it.isFinite() && it >= 0.0 && knownDriveLengthUnit(unit) }
        ?.let { if (unit == "mi") it * KM_PER_MILE else it }?.takeIf(Double::isFinite)

private fun Units?.driveLengthUnit(): String? =
    this?.unitOfLength?.trim()?.lowercase() ?: ""

/** These are the actual repository response boundaries, also used by tests. */
internal fun DrivesData.receivedDrives(): List<DriveData> =
    drives.orEmpty().map { it.copy(lengthUnit = units.driveLengthUnit(),
        temperatureUnit = units?.unitOfTemperature?.trim()?.uppercase() ?: "") }

internal fun DriveDetailData.receivedDrive(): DriveDetail? =
    drive?.copy(lengthUnit = units.driveLengthUnit(),
        temperatureUnit = units?.unitOfTemperature?.trim()?.uppercase() ?: "")

internal fun driveTemperatureC(value: Double?, unit: String?, source: String?): Double? {
    val raw = value?.takeIf(Double::isFinite) ?: return null
    return when (unit?.trim()?.uppercase()) {
        "C", "°C" -> raw
        "F", "°F" -> (raw - 32.0) * 5.0 / 9.0
        // These producers' archived DTO contract is Celsius. An explicit unknown
        // response unit above revokes that legacy fallback.
        null -> raw.takeIf { source in setOf("fleet_api", "telemetry_mqtt", "teslamate_archive") }
        else -> null
    }?.takeIf(Double::isFinite)
}

/** Charge length was not archived before this metadata existed; an absent unit is unknown. */
internal fun knownChargeLengthUnit(unit: String?): Boolean = unit?.trim()?.lowercase(java.util.Locale.ROOT) in setOf("km", "mi")

internal fun chargeDistanceKm(value: Double?, unit: String?): Double? =
    if (knownChargeLengthUnit(unit)) driveDistanceKm(value, unit?.trim()?.lowercase(java.util.Locale.ROOT)) else null

internal fun sameChargeLengthUnit(first: String?, second: String?): Boolean =
    knownChargeLengthUnit(first) && knownChargeLengthUnit(second) &&
        first?.trim()?.lowercase(java.util.Locale.ROOT) == second?.trim()?.lowercase(java.util.Locale.ROOT)

private fun historyTemperatureUnit(unit: String?, source: String?): String? = when (unit?.trim()?.uppercase(java.util.Locale.ROOT)) {
    "C", "°C" -> "C"
    "F", "°F" -> "F"
    null -> if (source in setOf("fleet_api", "telemetry_mqtt")) "C" else null
    else -> null
}

/** Sparse values may only borrow a number that has the same established physical unit. */
internal fun sameHistoryTemperatureUnit(first: String?, firstSource: String?, second: String?, secondSource: String?): Boolean {
    val current = historyTemperatureUnit(first, firstSource) ?: return false
    return current == historyTemperatureUnit(second, secondSource)
}

internal fun canonicalAggregateTemperatureC(value: Double?, unit: String?, source: String?): Double? {
    val established = historyTemperatureUnit(unit, source) ?: return null
    return driveTemperatureC(value, established, source)
}

private fun Units?.receivedHistoryLengthUnit(): String =
    this?.unitOfLength?.trim()?.lowercase(java.util.Locale.ROOT) ?: ""

private fun Units?.receivedHistoryTemperatureUnit(source: String?): String =
    this?.unitOfTemperature?.trim()?.uppercase(java.util.Locale.ROOT)
        ?: if (driveTemperatureC(0.0, null, source) != null) "C" else ""

internal fun ChargesData.receivedCharges(): List<ChargeData> = charges.orEmpty().map { charge ->
    charge.copy(lengthUnit = units.receivedHistoryLengthUnit(),
        temperatureUnit = units.receivedHistoryTemperatureUnit(charge.source))
}

internal fun ChargeDetailData.receivedCharge(): ChargeDetail? = charge?.let { detail ->
    detail.copy(lengthUnit = units.receivedHistoryLengthUnit(),
        temperatureUnit = units.receivedHistoryTemperatureUnit(detail.source))
}
