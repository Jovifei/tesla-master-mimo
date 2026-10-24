package com.matelink.data.local

import android.content.Context
import android.content.SharedPreferences
import com.matelink.data.api.models.*
import com.matelink.domain.map.mergeCarStatusPosition
import com.squareup.moshi.Moshi
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
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

    /** A verified local vehicle id binds cloud account, API origin and vehicle UID. */
    private fun key(scope: HistoryReadScope, vehicle: VehicleContext): String? {
        if (vehicle.remoteApiCarId <= 0 || vehicle.localHistoryCarId >= 0 ||
            vehicle.connectionSource != scope.source || vehicle.serverIdentity != scope.serverIdentity ||
            scope.effectiveApiOrigin.isBlank() ||
            (scope.source == HistoryConnectionSource.CLOUD && scope.accountNamespace.isNullOrBlank())) return null
        val identity = listOf(scope.source.name, scope.effectiveApiOrigin,
            scope.accountNamespace.orEmpty(), vehicle.localHistoryCarId.toString()).joinToString("\u0000")
        return MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    internal fun getCachedStatus(scope: HistoryReadScope, vehicle: VehicleContext): CarStatus? {
        val key = key(scope, vehicle) ?: return null
        val json = prefs.getString("status_v2_$key", null)
        if (!json.isNullOrBlank()) {
            try {
                val parsed = adapter.fromJson(json)
                if (parsed != null) return parsed
            } catch (_: Exception) {
            }
        }
        return null
    }

    internal fun getCachedObservedAt(scope: HistoryReadScope, vehicle: VehicleContext): String? =
        key(scope, vehicle)?.let { prefs.getString("observed_at_v2_$it", null) }

    internal fun saveStatus(scope: HistoryReadScope, vehicle: VehicleContext, status: CarStatus, observedAt: String? = null) {
        val key = key(scope, vehicle) ?: return
        try {
            val json = adapter.toJson(status)
            prefs.edit()
                .putString("status_v2_$key", json)
                .putString("observed_at_v2_$key", observedAt)
                .apply()
            _updates.tryEmit(vehicle.remoteApiCarId)
        } catch (_: Exception) {
        }
    }

    /** Save observed non-position fields even when GPS is absent. Only this same
     * scoped vehicle's previously usable coordinates may complete the snapshot.
     */
    internal fun reconcileAndSave(scope: HistoryReadScope, vehicle: VehicleContext,
                                  live: CarStatus, observedAt: String?): CarStatus {
        val merged = mergeCarStatusPosition(live, getCachedStatus(scope, vehicle)) ?: live
        saveStatus(scope, vehicle, merged, observedAt)
        return merged
    }

}
