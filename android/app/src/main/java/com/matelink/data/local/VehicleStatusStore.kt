package com.matelink.data.local

import android.content.Context
import android.content.SharedPreferences
import com.matelink.data.api.models.*
import com.squareup.moshi.Moshi
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

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
    private val _updates = MutableSharedFlow<Int>(extraBufferCapacity = 8)
    val updates: SharedFlow<Int> = _updates

    fun getCachedStatus(carId: Int): CarStatus? {
        val json = prefs.getString("status_$carId", null)
        if (!json.isNullOrBlank()) {
            try {
                val parsed = adapter.fromJson(json)
                if (parsed != null) return parsed
            } catch (_: Exception) {
            }
        }
        return null
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
            _updates.tryEmit(carId)
        } catch (_: Exception) {
        }
    }

}
