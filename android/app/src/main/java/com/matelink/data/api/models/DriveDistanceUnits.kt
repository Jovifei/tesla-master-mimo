package com.matelink.data.api.models

private const val KM_PER_MILE = 1.609344

/** Missing metadata retains the established cloud/legacy kilometre contract.
 * Explicit unsupported metadata never borrows a later status preference.
 */
internal fun knownDriveLengthUnit(unit: String?): Boolean = unit == null || unit == "km" || unit == "mi"

/** Length belongs to this receipt, temperature preferences remain independent. */
internal fun drivePresentationUnits(lengthUnit: String?, preferences: Units?): Units =
    (preferences ?: Units()).copy(unitOfLength = lengthUnit ?: "km")

internal fun driveDistanceKm(value: Double?, unit: String?): Double? =
    value?.takeIf { it.isFinite() && it >= 0.0 && knownDriveLengthUnit(unit) }
        ?.let { if (unit == "mi") it * KM_PER_MILE else it }?.takeIf(Double::isFinite)

private fun Units?.driveLengthUnit(): String? =
    if (this == null) null else unitOfLength?.trim()?.lowercase() ?: ""

/** These are the actual repository response boundaries, also used by tests. */
internal fun DrivesData.receivedDrives(): List<DriveData> =
    drives.orEmpty().map { it.copy(lengthUnit = units.driveLengthUnit()) }

internal fun DriveDetailData.receivedDrive(): DriveDetail? =
    drive?.copy(lengthUnit = units.driveLengthUnit())
