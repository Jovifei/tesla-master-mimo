package com.matelink.data.api.models

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import java.time.Instant

/** Versioned, additive contract. Values do not acquire measurement status merely by crossing an API. */
@JsonClass(generateAdapter = true)
data class EnergyContract(
    @Json(name = "version") val version: Int = 1,
    @Json(name = "net_energy") val netEnergy: EnergyMetric? = null,
    @Json(name = "battery_input") val batteryInput: EnergyMetric? = null,
    @Json(name = "ac_input") val acInput: EnergyMetric? = null,
    @Json(name = "stored_change") val storedChange: EnergyMetric? = null,
    @Json(name = "ac_loss") val acLoss: EnergyMetric? = null,
    @Json(name = "ac_efficiency") val acEfficiency: Double? = null,
    @Json(name = "charge_mode") val chargeMode: String? = null,
    @Json(name = "charge_mode_evidence") val chargeModeEvidence: String? = null
)

@JsonClass(generateAdapter = true)
data class EnergyMetric(
    @Json(name = "value_kwh") val valueKwh: Double? = null,
    @Json(name = "unit") val unit: String = "kWh",
    @Json(name = "method") val method: String? = null,
    @Json(name = "measurement_point") val measurementPoint: String? = null,
    @Json(name = "source") val source: String? = null,
    @Json(name = "source_field") val sourceField: String? = null,
    @Json(name = "quality") val quality: String = "unknown",
    @Json(name = "reason") val reason: String? = null,
    @Json(name = "start_date") val startDate: String? = null,
    @Json(name = "end_date") val endDate: String? = null,
    @Json(name = "observed_start_at") val observedStartAt: String? = null,
    @Json(name = "observed_end_at") val observedEndAt: String? = null,
    @Json(name = "time_basis") val timeBasis: String? = null,
    @Json(name = "coverage_kind") val coverageKind: String? = null,
    @Json(name = "coverage_seconds") val coverageSeconds: Double? = null,
    @Json(name = "coverage_ratio") val coverageRatio: Double? = null,
    /** Diagnostic covered-subset integral; never a substitute for whole-window value_kwh. */
    @Json(name = "covered_energy_kwh") val coveredEnergyKwh: Double? = null,
    @Json(name = "activity_evidence") val activityEvidence: String? = null
) {
    val isEstimated: Boolean get() = quality == "estimated"

    fun valueForWindow(start: String?, end: String?): Double? {
        val value = valueKwh?.takeIf(Double::isFinite) ?: return null
        if (unit != "kWh" || source.isNullOrBlank() || quality !in setOf("reported", "estimated")) return null
        val expectedStart = energyInstant(start) ?: return null
        val expectedEnd = energyInstant(end) ?: return null
        if (!expectedEnd.isAfter(expectedStart) || energyInstant(startDate) != expectedStart || energyInstant(endDate) != expectedEnd) return null
        val validMethod = when (method) {
            "api_reported_net" -> quality == "reported" && measurementPoint == "reported_net"
            "drive_power_integral" -> isEstimated && measurementPoint == "drive_power" && coverageKind == "time" && fullCoverage()
            "energy_remaining_delta" -> isEstimated && measurementPoint == "nominal_battery_remaining" && coverageKind == "endpoints" && fullCoverage()
            "session_counter_delta" -> quality == "reported" && measurementPoint in setOf("battery_input", "ac_charger_input") && coverageKind == "endpoints" && fullCoverage()
            "compatible_ac_balance" -> isEstimated && measurementPoint == "ac_to_battery" && coverageKind == "endpoints" && fullCoverage()
            else -> false
        }
        return value.takeIf { validMethod }
    }

    private fun fullCoverage() = coverageRatio?.takeIf(Double::isFinite)?.let { it >= 1.0 - 1e-9 && it <= 1.0 } == true
}

private fun energyInstant(raw: String?): Instant? = raw?.let { runCatching { Instant.parse(it) }.getOrNull() }

fun EnergyContract?.netValueForWindow(start: String?, end: String?): Double? =
    this?.takeIf { it.version == 1 }?.netEnergy?.takeIf { metric ->
        (metric.method == "api_reported_net" && metric.measurementPoint == "reported_net") ||
            (metric.method == "drive_power_integral" && metric.measurementPoint == "drive_power") ||
            (metric.method == "energy_remaining_delta" && metric.measurementPoint == "nominal_battery_remaining" &&
                metric.activityEvidence == "continuous_drive_no_charging_with_valid_endpoints")
    }?.valueForWindow(start, end)
