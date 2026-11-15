package com.matelink.ui.screens.readiness

import com.matelink.data.api.models.FleetReadinessContext
import com.matelink.data.api.models.FleetReadinessObservedOdometer
import com.matelink.data.api.models.FleetTelemetryDiagnostics
import com.matelink.data.api.models.validHistoryVehicleUid
import java.time.Instant
import com.matelink.domain.telemetry.failedSnapshotFreshness
import com.matelink.domain.telemetry.SnapshotFreshness

/** Never repairs missing context by calling a legacy provider-backed endpoint. */
internal fun readOnlyFleetReadinessContext(data: FleetTelemetryDiagnostics?, carId: Int): FleetReadinessContext? {
    val context = data?.readinessContext?.takeIf { it.availability in setOf("available", "partial") } ?: return null
    val readiness = context.readiness ?: return null
    if (readiness.capabilityVersion != 1 || !validHistoryVehicleUid(readiness.vehicleUid)) return null
    val car = context.car
    if (car != null && (car.carId != carId || !validHistoryVehicleUid(car.vehicleUid))) return null
    return context
}

internal fun readOnlyFleetOdometerKm(sample: FleetReadinessObservedOdometer?, now: Instant = Instant.now()): Double? {
    if (sample == null || sample.unit != "km" || sample.source != "telemetry_mqtt") return null
    val value = sample.value?.takeIf { it.isFinite() && it >= 0.0 } ?: return null
    val observedAt = sample.observedAt?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return null
    return value.takeIf { !observedAt.isAfter(now) &&
        failedSnapshotFreshness(sample.observedAt, true, now) == SnapshotFreshness.RECENT }
}
