package com.matelink.data.local

import android.content.Context
import android.content.SharedPreferences
import com.matelink.data.api.models.*
import com.matelink.domain.map.VehiclePosition
import com.matelink.domain.map.VehiclePositionResolver
import com.matelink.util.parseIsoInstant
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
            scope.accountNamespace.orEmpty(), vehicle.localHistoryCarId.toString(),
            vehicle.stableIdentity).joinToString("\u0000")
        return MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun verifiedAliasKey(scope: HistoryReadScope, vehicle: VehicleContext, digest: String): String? {
        if (scope.source != HistoryConnectionSource.CLOUD || !digest.matches(Regex("[0-9a-f]{64}"))) return null
        return key(scope, vehicle.copy(stableIdentity = "verified-sha256:$digest"))
    }

    /** Used only with the current origin/account/car binding's persisted UID digest. */
    internal fun getCachedStatusForVerifiedIdentity(
        scope: HistoryReadScope, vehicle: VehicleContext, digest: String
    ): CarStatus? {
        val alias = verifiedAliasKey(scope, vehicle, digest) ?: return null
        val json = prefs.getString("status_v3_$alias", null) ?: return null
        return runCatching { adapter.fromJson(json) }.getOrNull()
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
            val editor = prefs.edit()
                .putString("status_v2_$key", json)
                .putString("observed_at_v2_$key", observedAt)
            if (scope.source == HistoryConnectionSource.CLOUD &&
                vehicle.stableIdentity.startsWith("cloud-origin:")) {
                val digest = MessageDigest.getInstance("SHA-256")
                    .digest(vehicle.stableIdentity.toByteArray(Charsets.UTF_8))
                    .joinToString("") { "%02x".format(it) }
                verifiedAliasKey(scope, vehicle, digest)?.let { alias ->
                    editor.putString("status_v3_$alias", json)
                        .putString("observed_at_v3_$alias", observedAt)
                }
            }
            // Independent last verified position: keep its OLD observation time.
            val positionAt = parseIsoInstant(observedAt)?.toString()
            if (positionAt != null &&
                VehiclePositionResolver.resolve(status.latitude, status.longitude, null, null) != null) {
                editor.putString("position_v2_$key", json)
                    .putString("position_at_v2_$key", positionAt)
            }
            // The saved receipt must survive process exit; async apply() does
            // not prove a durable local observation when a write is interrupted.
            if (editor.commit()) _updates.tryEmit(vehicle.remoteApiCarId)
        } catch (_: Exception) {
        }
    }

    /** A previous position is not part of the current live status. */
    internal fun getCachedPosition(scope: HistoryReadScope, vehicle: VehicleContext): VehiclePosition? {
        val key = key(scope, vehicle) ?: return null
        val at = prefs.getString("position_at_v2_$key", null)
            ?.let(::parseIsoInstant)?.toString() ?: return null
        val previous = prefs.getString("position_v2_$key", null)
            ?.let { runCatching { adapter.fromJson(it) }.getOrNull() } ?: return null
        val coordinates = VehiclePositionResolver.resolve(
            previous.latitude, previous.longitude, null, null) ?: return null
        return coordinates.copy(source = "scoped_historical", observedAt = at)
    }

    /** Never re-date old GPS or other sparse fields as a new observation. */
    internal fun reconcileAndSave(scope: HistoryReadScope, vehicle: VehicleContext,
                                  live: CarStatus, observedAt: String?): CarStatus {
        saveStatus(scope, vehicle, live, observedAt)
        return live
    }

}
