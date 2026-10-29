package com.matelink.data.repository

import com.matelink.data.api.models.CarData
import com.matelink.data.api.models.DataReadiness
import com.matelink.data.api.models.FleetTelemetryDiagnostics
import com.matelink.data.api.models.TelemetryConfigureResult
import com.matelink.data.api.models.TelemetryPairingStatus

/** Narrow data boundary for the readiness screen; it keeps ViewModel races independently testable. */
interface DataReadinessDataSource {
    suspend fun getDataReadiness(carId: Int): ApiResult<DataReadiness>
    /** Legacy/local implementations have no official proof; they must not fabricate one. */
    suspend fun getTelemetryDiagnostics(carId: Int): ApiResult<FleetTelemetryDiagnostics> =
        ApiResult.Error("Fleet diagnostics unavailable")
    suspend fun getTelemetryPairingStatus(carId: Int): ApiResult<TelemetryPairingStatus>
    suspend fun configureTelemetry(carId: Int): ApiResult<TelemetryConfigureResult>
    suspend fun getCar(carId: Int): ApiResult<CarData>
    suspend fun getCarStatus(carId: Int): ApiResult<CarStatusWithUnits>
}

interface LegacyHistoryMigrationService {
    suspend fun inspect(
        legacyCarId: Int,
        currentModel: String?,
        currentVehicleFingerprint: String?,
        currentObservedOdometer: Double?
    ): LegacyHistoryMigrationEligibility

    suspend fun migrate(
        legacyCarId: Int,
        targetHistoryCarId: Int,
        eligibility: LegacyHistoryMigrationEligibility
    ): LegacyHistoryMigrationResult
}
