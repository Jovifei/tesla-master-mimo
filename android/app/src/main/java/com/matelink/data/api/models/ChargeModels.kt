package com.matelink.data.api.models

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import kotlin.math.roundToInt

@JsonClass(generateAdapter = true)
data class ChargesResponse(@Json(name = "data") val data: ChargesData? = null)

@JsonClass(generateAdapter = true)
data class ChargesData(
    @Json(name = "charges") val charges: List<ChargeData>? = null,
    @Json(name = "meta") val meta: ApiDataMeta? = null,
    @Json(name = "units") val units: Units? = null
)

@JsonClass(generateAdapter = true)
data class ChargeData(
    @Json(name = "charge_id") val chargeId: Int,
    @Json(name = "start_date") val startDate: String? = null,
    @Json(name = "end_date") val endDate: String? = null,
    @Json(name = "address") val address: String? = null,
    @Json(name = "charge_energy_added") val chargeEnergyAdded: Double? = null,
    @Json(name = "charge_energy_used") val chargeEnergyUsed: Double? = null,
    @Json(name = "cost") val cost: Double? = null,
    @Json(name = "duration_min") val durationMin: Int? = null,
    @Json(name = "duration_str") val durationStr: String? = null,
    @Json(name = "battery_details") val batteryDetails: ChargeBatteryDetails? = null,
    @Json(name = "range_ideal") val rangeIdeal: ChargeRange? = null,
    @Json(name = "range_rated") val rangeRated: ChargeRange? = null,
    @Json(name = "outside_temp_avg") val outsideTempAvg: Double? = null,
    @Json(name = "odometer") val odometer: Double? = null,
    @Json(name = "latitude") val latitude: Double? = null,
    @Json(name = "longitude") val longitude: Double? = null,
    @Json(name = "source") val source: String? = null,
    @Json(name = "quality_state") val qualityState: String? = null,
    @Json(name = "quality_reason") val qualityReason: String? = null,
    @Json(name = "energy_contract") val energyContract: EnergyContract? = null,
    @Json(name = "charge_type") val chargeType: String? = null,
    @Json(name = "source_instance_id") val sourceInstanceId: String? = null,
    @Json(name = "source_id") val sourceId: String? = null,
    @Json(name = "currency_code") val currencyCode: String? = null,
    @Json(name = "source_record_id") val sourceRecordId: String? = null,
    @Json(name = "_matelink_received_temperature_unit") val temperatureUnit: String? = null,
    @Json(name = "_matelink_received_length_unit") val lengthUnit: String? = null
) {
    val sourceRecordIdentity: SourceRecordIdentity get() = resolveSourceRecordIdentity(sourceId, sourceRecordId)
    val startBatteryLevel: Int? get() = batteryDetails?.startBatteryLevel
    val endBatteryLevel: Int? get() = batteryDetails?.endBatteryLevel
    val startRatedRangeKm: Double? get() = chargeDistanceKm(rangeRated?.startRange, lengthUnit)
    val endRatedRangeKm: Double? get() = chargeDistanceKm(rangeRated?.endRange, lengthUnit)
    val startIdealRangeKm: Double? get() = chargeDistanceKm(rangeIdeal?.startRange, lengthUnit)
    val endIdealRangeKm: Double? get() = chargeDistanceKm(rangeIdeal?.endRange, lengthUnit)
    val odometerKm: Double? get() = chargeDistanceKm(odometer, lengthUnit)
    val hasKnownLengthUnit: Boolean get() = knownChargeLengthUnit(lengthUnit)
    val batteryInputKwh: Double? get() = if (sourceRecordIdentity.conflicted) null else qualifiedChargeValue(energyContract, energyContract?.batteryInput, chargeEnergyAdded, startDate, endDate, "battery_input", source)
    val inputEnergyKwh: Double? get() = if (sourceRecordIdentity.conflicted) null else qualifiedChargeValue(energyContract, energyContract?.acInput, chargeEnergyUsed, startDate, endDate, "ac_charger_input", source)
}

@JsonClass(generateAdapter = true)
data class ChargeBatteryDetails(
    @Json(name = "start_battery_level") val startBatteryLevel: Int? = null,
    @Json(name = "end_battery_level") val endBatteryLevel: Int? = null,
    @Json(name = "current_battery_level") val currentBatteryLevel: Int? = null
)

@JsonClass(generateAdapter = true)
data class ChargeRange(
    @Json(name = "start_range") val startRange: Double? = null,
    @Json(name = "end_range") val endRange: Double? = null
)

@JsonClass(generateAdapter = true)
data class ChargeDetailResponse(
    @Json(name = "data") val data: ChargeDetailData? = null,
    @Json(name = "error") val error: String? = null
)

@JsonClass(generateAdapter = true)
data class ChargeDetailData(
    @Json(name = "car") val car: ChargeDetailCar? = null,
    @Json(name = "charge") val charge: ChargeDetail? = null,
    @Json(name = "units") val units: Units? = null
)

@JsonClass(generateAdapter = true)
data class ChargeDetailCar(
    @Json(name = "car_id") val carId: Int? = null,
    @Json(name = "car_name") val carName: String? = null
)

@JsonClass(generateAdapter = true)
data class ChargeDetail(
    @Json(name = "charge_id") val chargeId: Int,
    @Json(name = "start_date") val startDate: String? = null,
    @Json(name = "end_date") val endDate: String? = null,
    @Json(name = "address") val address: String? = null,
    @Json(name = "charge_energy_added") val chargeEnergyAdded: Double? = null,
    @Json(name = "charge_energy_used") val chargeEnergyUsed: Double? = null,
    @Json(name = "cost") val cost: Double? = null,
    @Json(name = "duration_min") val durationMin: Int? = null,
    @Json(name = "duration_str") val durationStr: String? = null,
    @Json(name = "battery_details") val batteryDetails: ChargeBatteryDetails? = null,
    @Json(name = "range_ideal") val rangeIdeal: ChargeRange? = null,
    @Json(name = "range_rated") val rangeRated: ChargeRange? = null,
    @Json(name = "outside_temp_avg") val outsideTempAvg: Double? = null,
    @Json(name = "odometer") val odometer: Double? = null,
    @Json(name = "latitude") val latitude: Double? = null,
    @Json(name = "longitude") val longitude: Double? = null,
    @Json(name = "charge_details") val chargePoints: List<ChargePoint>? = null,
    @Json(name = "is_charging") val isCharging: Boolean? = null,
    @Json(name = "source") val source: String? = null,
    @Json(name = "quality_state") val qualityState: String? = null,
    @Json(name = "quality_reason") val qualityReason: String? = null,
    @Json(name = "charger_phases") val chargerPhases: Int? = null,
    @Json(name = "charger_power") val chargerPower: Double? = null,
    @Json(name = "charger_voltage") val chargerVoltage: Double? = null,
    @Json(name = "charger_actual_current") val chargerActualCurrent: Double? = null,
    @Json(name = "charge_current_request") val chargeCurrentRequest: Double? = null,
    @Json(name = "charge_current_request_max") val chargeCurrentRequestMax: Double? = null,
    @Json(name = "time_to_full_charge") val timeToFullCharge: Double? = null,
    @Json(name = "fast_charger_present") val fastChargerPresent: Boolean? = null,
    @Json(name = "energy_contract") val energyContract: EnergyContract? = null,
    @Json(name = "charge_type") val chargeType: String? = null,
    @Json(name = "source_instance_id") val sourceInstanceId: String? = null,
    @Json(name = "source_id") val sourceId: String? = null,
    @Json(name = "currency_code") val currencyCode: String? = null,
    @Json(name = "source_record_id") val sourceRecordId: String? = null,
    @Json(name = "_matelink_received_temperature_unit") val temperatureUnit: String? = null,
    @Json(name = "_matelink_received_length_unit") val lengthUnit: String? = null,
    /** Provider's current plug-session counter, separate from whole-window energy. */
    @Json(name = "current_energy_receipt") val currentEnergyReceipt: CurrentChargeEnergyReceipt? = null,
    @Json(name = "observed_at") val observedAt: String? = null
) {
    val sourceRecordIdentity: SourceRecordIdentity get() = resolveSourceRecordIdentity(sourceId, sourceRecordId)
    val startBatteryLevel: Int? get() = batteryDetails?.startBatteryLevel
    val endBatteryLevel: Int? get() = batteryDetails?.endBatteryLevel
    val currentBatteryLevel: Int? get() = batteryDetails?.currentBatteryLevel
    val currentOrEndBatteryLevel: Int? get() = currentBatteryLevel ?: endBatteryLevel
    val startRatedRangeKm: Double? get() = chargeDistanceKm(rangeRated?.startRange, lengthUnit)
    val endRatedRangeKm: Double? get() = chargeDistanceKm(rangeRated?.endRange, lengthUnit)
    val startIdealRangeKm: Double? get() = chargeDistanceKm(rangeIdeal?.startRange, lengthUnit)
    val endIdealRangeKm: Double? get() = chargeDistanceKm(rangeIdeal?.endRange, lengthUnit)
    val odometerKm: Double? get() = chargeDistanceKm(odometer, lengthUnit)
    val hasKnownLengthUnit: Boolean get() = knownChargeLengthUnit(lengthUnit)
    val batteryInputKwh: Double? get() = if (sourceRecordIdentity.conflicted) null else qualifiedChargeValue(energyContract, energyContract?.batteryInput, chargeEnergyAdded, startDate, endDate, "battery_input", source)
    val inputEnergyKwh: Double? get() = if (sourceRecordIdentity.conflicted) null else qualifiedChargeValue(energyContract, energyContract?.acInput, chargeEnergyUsed, startDate, endDate, "ac_charger_input", source)
}

@JsonClass(generateAdapter = true)
data class CurrentChargeEnergyReceipt(
    @Json(name = "version") val version: Int = 1,
    @Json(name = "value_kwh") val valueKwh: Double? = null,
    @Json(name = "unit") val unit: String? = null,
    @Json(name = "source") val source: String? = null,
    @Json(name = "source_field") val sourceField: String? = null,
    @Json(name = "method") val method: String? = null,
    @Json(name = "quality") val quality: String? = null,
    @Json(name = "observed_at") val observedAt: String? = null
)

/** An explicit unknown contract must never resurrect a historical scalar. */
private fun qualifiedChargeValue(
    contract: EnergyContract?, metric: EnergyMetric?, legacy: Double?,
    start: String?, end: String?, purpose: String, source: String?
): Double? {
    if (contract == null) return legacy?.takeIf { it.isFinite() && it >= 0.0 && legacyScalarEnergyAllowed(source) }
    if (contract.version != 1 || metric?.method != "session_counter_delta" ||
        metric.measurementPoint != purpose ||
        (source != null && metric.source != source)) return null
    // AC input is not a whole-session metric in unknown or mixed AC/DC mode.
    if (purpose == "ac_charger_input" && (contract.chargeMode != "ac" ||
            contract.chargeModeEvidence != "observed_boundary_modes_no_conflict")) return null
    return metric.valueForWindow(start, end)?.takeIf { it >= 0.0 }
}

@JsonClass(generateAdapter = true)
data class ChargePoint(
    @Json(name = "date") val date: String? = null,
    @Json(name = "battery_level") val batteryLevel: Int? = null,
    @Json(name = "charge_energy_added") val chargeEnergyAdded: Double? = null,
    @Json(name = "charger_details") val chargerDetails: ChargerDetails? = null,
    @Json(name = "outside_temp") val outsideTemp: Double? = null,
    @Json(name = "battery_info") val batteryInfo: ChargeBatteryInfo? = null
) {
    val chargerPower: Int? get() = chargerPowerValue?.roundToInt()
    val chargerPowerValue: Double? get() = chargerDetails?.chargerPower?.takeIf(Double::isFinite)
    val chargerVoltage: Int? get() = chargerVoltageValue?.roundToInt()
    val chargerVoltageValue: Double? get() = chargerDetails?.chargerVoltage?.takeIf(Double::isFinite)
    val chargerCurrent: Int? get() = chargerCurrentValue?.roundToInt()
    val chargerCurrentValue: Double? get() = chargerDetails?.chargerActualCurrent?.takeIf(Double::isFinite)
}

@JsonClass(generateAdapter = true)
data class ChargerDetails(
    @Json(name = "charger_power") val chargerPower: Double? = null,
    @Json(name = "charger_voltage") val chargerVoltage: Double? = null,
    @Json(name = "charger_actual_current") val chargerActualCurrent: Double? = null,
    @Json(name = "charger_phases") val chargerPhases: Int? = null,
    @Json(name = "fast_charger_present") val fastChargerPresent: Boolean? = null,
    @Json(name = "fast_charger_brand") val fastChargerBrand: String? = null,
    @Json(name = "fast_charger_type") val fastChargerType: String? = null
)

@JsonClass(generateAdapter = true)
data class ChargeBatteryInfo(
    @Json(name = "ideal_battery_range_km") val idealBatteryRangeKm: Double? = null,
    @Json(name = "rated_battery_range_km") val ratedBatteryRangeKm: Double? = null,
    @Json(name = "usable_battery_level") val usableBatteryLevel: Int? = null
)
