package com.matelink.data.sync

import com.matelink.data.api.TeslamateApi
import com.matelink.data.api.models.CarData
import com.matelink.data.api.models.CarStatus
import com.matelink.data.local.HistoryConnectionSource
import com.matelink.data.local.HistoryIdentityUnavailableException
import com.matelink.data.local.HistoryReadScope
import com.matelink.data.local.SettingsDataStore
import com.matelink.data.local.VehicleContext
import com.matelink.data.local.VehicleContextRepository
import com.matelink.data.repository.ApiResult
import com.matelink.data.repository.CurrentChargeOutcome
import com.matelink.data.repository.currentChargeResultForResponse
import com.matelink.di.TeslamateApiFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.io.IOException
import javax.inject.Inject

internal data class BoundNotificationVehicle(val car: CarData, val context: VehicleContext)

/** A notification read can never silently switch to a newer source/client. */
class BoundNotificationSource @Inject constructor(
    private val contexts: VehicleContextRepository,
    private val settings: SettingsDataStore,
    private val apiFactory: TeslamateApiFactory
) {
    internal suspend fun openSelfHosted(): BoundNotificationSession {
        val scope = contexts.captureReadScope()
        if (scope.source != HistoryConnectionSource.SELF_HOSTED) throw HistoryIdentityUnavailableException()
        val configuration = settings.settings.first()
        if (contexts.captureReadScope() != scope) throw HistoryIdentityUnavailableException()
        val api = apiFactory.create(scope.effectiveApiOrigin, apiTokenOverride = configuration.apiToken,
            requiredCloudMode = false)
        if (contexts.captureReadScope() != scope) throw HistoryIdentityUnavailableException()
        return BoundNotificationSession(scope, api, contexts)
    }
}

internal class BoundNotificationSession(
    val scope: HistoryReadScope,
    private val api: TeslamateApi,
    private val contexts: VehicleContextRepository
) {
    suspend fun ensureCurrent(vehicle: BoundNotificationVehicle? = null) {
        if (scope.source != HistoryConnectionSource.SELF_HOSTED || contexts.captureReadScope() != scope ||
            (vehicle != null && !contexts.isHistoryContextCurrentNow(scope, vehicle.context)))
            throw HistoryIdentityUnavailableException()
    }

    private suspend fun <T> read(vehicle: BoundNotificationVehicle? = null, request: suspend () -> T): T {
        ensureCurrent(vehicle)
        return try {
            request().also { ensureCurrent(vehicle) }
        } catch (e: CancellationException) { throw e }
          catch (e: Exception) { ensureCurrent(vehicle); throw e }
    }

    suspend fun vehicles(): List<BoundNotificationVehicle> {
        val response = read { api.getCars() }
        if (!response.isSuccessful) throw IOException("notification_cars_unavailable")
        val cars = response.body()?.data?.cars.orEmpty()
        if (cars.any { it.carId <= 0 } || cars.map { it.carId }.distinct().size != cars.size)
            throw IOException("notification_cars_invalid")
        val result = cars.map { car ->
            // Only this batch's authenticated /cars response authorizes a key.
            BoundNotificationVehicle(car, contexts.resolveVerifiedHistoryCar(car, scope))
        }
        result.forEach { ensureCurrent(it) }
        return result
    }

    suspend fun status(vehicle: BoundNotificationVehicle): CarStatus {
        val response = read(vehicle) { api.getCarStatus(vehicle.car.carId) }
        if (!response.isSuccessful) throw IOException("notification_status_unavailable")
        return response.body()?.data?.status ?: throw IOException("notification_status_missing")
    }

    suspend fun currentChargeAvailable(vehicle: BoundNotificationVehicle): Boolean {
        val response = read(vehicle) { api.getCurrentCharge(vehicle.car.carId) }
        val result = currentChargeResultForResponse(response, vehicle.car.carId)
        return result is ApiResult.Success && result.data is CurrentChargeOutcome.Active
    }
}
