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
    @Json(name = "activity_evidence") val activityEvidence: String? = null,
    @Json(name = "capacity_kwh") val capacityKwh: Double? = null,
    @Json(name = "capacity_basis") val capacityBasis: String? = null,
    @Json(name = "capacity_source_field") val capacitySourceField: String? = null,
    @Json(name = "capacity_model") val capacityModel: String? = null,
    @Json(name = "capacity_trim") val capacityTrim: String? = null,
    @Json(name = "calculation_version") val calculationVersion: Int? = null,
    @Json(name = "start_soc") val startSoc: Int? = null,
    @Json(name = "end_soc") val endSoc: Int? = null
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
            "drive_power_integral" -> isEstimated && measurementPoint == "drive_power" && coverageKind == "time" && validPowerCoverage()
            "energy_remaining_delta" -> isEstimated && measurementPoint in setOf("nominal_battery_remaining", "battery_energy_remaining") && coverageKind == "endpoints" && fullCoverage()
            "soc_capacity_estimate" -> isEstimated && measurementPoint == "battery_soc" && coverageKind == "endpoints" && fullCoverage() && validCapacityEstimate(value)
            "session_counter_delta" -> quality == "reported" && measurementPoint in setOf("battery_input", "ac_charger_input") && coverageKind == "endpoints" && fullCoverage()
            "compatible_ac_balance" -> isEstimated && measurementPoint == "ac_to_battery" && coverageKind == "endpoints" && fullCoverage()
            else -> false
        }
        return value.takeIf { validMethod }
    }

    private fun fullCoverage() = coverageRatio?.takeIf(Double::isFinite)?.let { it >= 1.0 - 1e-9 && it <= 1.0 } == true
    private fun validPowerCoverage() = coverageRatio?.takeIf(Double::isFinite)?.let { it >= 0.85 && it <= 1.0 + 1e-6 } == true

    private fun validCapacityEstimate(value: Double): Boolean {
        val capacity = capacityKwh?.takeIf { it.isFinite() && it > 0.0 } ?: return false
        val first = startSoc?.takeIf { it in 0..100 } ?: return false
        val last = endSoc?.takeIf { it in 0..100 } ?: return false
        if (calculationVersion != 1 || capacityBasis !in setOf("model_assumption",
                "model_trim_assumption", "provided_capacity", "api_reported_current_capacity")) return false
        val expected = (first - last) / 100.0 * capacity
        return first != last && kotlin.math.abs(value - expected) <= 1e-9 * maxOf(1.0, kotlin.math.abs(expected))
    }
}

/** One method/quality mapping for persisted summaries, details and history cards. */
fun EnergyMetric?.driveEnergySource(): String? = when {
    this == null -> "api" // Legacy scalar compatibility is qualified by the caller.
    quality == "estimated" && method == "drive_power_integral" -> "power_samples"
    quality == "estimated" && method == "energy_remaining_delta" -> "energy_remaining_delta"
    quality == "estimated" && method == "soc_capacity_estimate" -> "soc_capacity_estimate"
    quality == "reported" && method == "api_reported_net" -> "api"
    else -> null
}

/**
 * The deployed bb09 API has no versioned counter/window contract. Its older
 * Fleet MQTT scalar may be AC/DC mixed or stale after reset, so do NOT
 * promote that numeric value to measured whole-session battery energy.
 * Historical TeslaMate and pre-source-tag legacy self-hosted values retain
 * their prior compatibility projection; neither is proof of a Fleet event.
 */
internal fun legacyScalarEnergyAllowed(source: String?): Boolean =
    source == null || source == "teslamate_archive"

private fun energyInstant(raw: String?): Instant? = com.matelink.util.parseIsoInstant(raw)

fun EnergyContract?.netValueForWindow(start: String?, end: String?): Double? =
    this?.takeIf { it.version == 1 }?.netEnergy?.takeIf { metric ->
        (metric.method == "api_reported_net" && metric.measurementPoint == "reported_net") ||
            (metric.method == "drive_power_integral" && metric.measurementPoint == "drive_power") ||
            (metric.method == "energy_remaining_delta" && metric.measurementPoint == "nominal_battery_remaining" &&
                metric.hasDrivingNominalEndpoints(start, end)) ||
            (metric.method == "soc_capacity_estimate" && metric.measurementPoint == "battery_soc")
    }?.valueForWindow(start, end)

private fun EnergyMetric.hasDrivingNominalEndpoints(start: String?, end: String?): Boolean {
    if (source != "telemetry_mqtt" || sourceField != "EnergyRemaining" || timeBasis != "collector_received_at" ||
        activityEvidence != "continuous_drive_no_charging_with_valid_endpoints") return false
    val from = energyInstant(start) ?: return false
    val to = energyInstant(end) ?: return false
    val first = energyInstant(observedStartAt) ?: return false
    val last = energyInstant(observedEndAt) ?: return false
    if (!to.isAfter(from) || !last.isAfter(first) || first.isBefore(from) || last.isAfter(to)) return false
    fun seconds(a: Instant, b: Instant): Double = java.time.Duration.between(a, b).let { it.seconds + it.nano / 1e9 }
    return seconds(from, first) <= 30.0 && seconds(last, to) <= 30.0 &&
        seconds(first, last) >= seconds(from, to) * 0.9
}
