package com.matelink.data.local

import android.content.Context
import android.content.SharedPreferences
import com.matelink.data.api.models.*
import com.squareup.moshi.Moshi
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VehicleStatusStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val moshi: Moshi
) {
    private val prefs: SharedPreferences = context.getSharedPreferences(
        "vehicle_cached_status",
        Context.MODE_PRIVATE
    )
    private val adapter = moshi.adapter(CarStatus::class.java)

    fun getCachedStatus(carId: Int): CarStatus {
        val json = prefs.getString("status_$carId", null)
        if (!json.isNullOrBlank()) {
            try {
                val parsed = adapter.fromJson(json)
                if (parsed != null) return parsed
            } catch (_: Exception) {
            }
        }
        return defaultFallbackStatus(carId)
    }

    fun getCachedObservedAt(carId: Int): String? {
        return prefs.getString("observed_at_$carId", null)
    }

    fun saveStatus(carId: Int, status: CarStatus, observedAt: String? = null) {
        try {
            val json = adapter.toJson(status)
            prefs.edit()
                .putString("status_$carId", json)
                .putString("observed_at_$carId", observedAt)
                .apply()
        } catch (_: Exception) {
        }
    }

    private fun defaultFallbackStatus(carId: Int): CarStatus {
        return CarStatus(
            displayName = "Jovi大鼠标",
            state = "asleep",
            stateSince = null,
            odometer = 18039.0,
            batteryDetails = BatteryDetails(
                batteryLevel = 84,
                usableBatteryLevel = 84,
                ratedBatteryRange = 338.0,
                estBatteryRange = 320.0,
                idealBatteryRange = 338.0
            ),
            climateDetails = ClimateDetails(
                insideTemp = 33.1,
                outsideTemp = 31.0,
                isClimateOn = false
            ),
            carStatus = CarStatusDetails(
                locked = true,
                sentryMode = true,
                doorsOpen = false,
                windowsOpen = false
            ),
            chargingDetails = ChargingDetails(
                pluggedIn = false,
                chargingState = "Disconnected"
            ),
            tpmsDetails = TpmsDetails(
                pressureFl = 2.9,
                pressureFr = 2.9,
                pressureRl = 2.9,
                pressureRr = 2.9
            )
        )
    }
}
