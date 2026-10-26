package com.matelink.data.local

import android.content.Context
import android.content.SharedPreferences
import com.matelink.data.api.models.*
import com.matelink.domain.map.VehiclePosition
import com.matelink.domain.map.VehiclePositionResolver
import com.matelink.util.parseIsoInstant
import com.squareup.moshi.Moshi
import com.squareup.moshi.JsonClass
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/** The status and its unit/provenance context are one received observation. */
@JsonClass(generateAdapter = true)
internal data class CachedVehicleStatusReceipt(
    val status: CarStatus,
    val units: Units? = null,
    val source: String? = null,
    val observedAt: String? = null,
    val fieldSources: Map<String, String> = emptyMap()
) {
    /** Keep the original raw record; unknown preferred units cannot label its values. */
    val displayStatus: CarStatus get() {
        val lengthKnown = units?.unitOfLength in setOf("km", "mi")
        val temperatureKnown = units?.unitOfTemperature in setOf("C", "F")
        val pressureKnown = units?.unitOfPressure in setOf("bar", "psi")
        return status.copy(
            odometer = status.odometer.takeIf { lengthKnown },
            batteryDetails = status.batteryDetails?.let { battery -> if (lengthKnown) battery else battery.copy(
                ratedBatteryRange = null, estBatteryRange = null, idealBatteryRange = null) },
            drivingDetails = status.drivingDetails?.let { driving -> if (lengthKnown) driving else driving.copy(
                speed = null, elevation = null) },
            climateDetails = status.climateDetails?.let { climate -> if (temperatureKnown) climate else climate.copy(
                insideTemp = null, outsideTemp = null) },
            tpmsDetails = status.tpmsDetails?.let { tpms -> if (pressureKnown) tpms else tpms.copy(
                pressureFl = null, pressureFr = null, pressureRl = null, pressureRr = null) }
        )
    }
}

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
    private val receiptAdapter = moshi.adapter(CachedVehicleStatusReceipt::class.java)
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
    ): CarStatus? = getCachedReceiptForVerifiedIdentity(scope, vehicle, digest)?.status

    internal fun getCachedStatus(scope: HistoryReadScope, vehicle: VehicleContext): CarStatus? =
        getCachedReceipt(scope, vehicle)?.status

    internal fun getCachedObservedAt(scope: HistoryReadScope, vehicle: VehicleContext): String? =
        getCachedReceipt(scope, vehicle)?.observedAt

    internal fun getCachedReceipt(scope: HistoryReadScope, vehicle: VehicleContext): CachedVehicleStatusReceipt? =
        key(scope, vehicle)?.let { readReceipt(it, 2) }

    internal fun getCachedReceiptForVerifiedIdentity(
        scope: HistoryReadScope, vehicle: VehicleContext, digest: String
    ): CachedVehicleStatusReceipt? = verifiedAliasKey(scope, vehicle, digest)?.let { readReceipt(it, 3) }

    private fun readReceipt(key: String, legacyVersion: Int): CachedVehicleStatusReceipt? {
        prefs.getString("receipt_v4_$key", null)?.let { json ->
            runCatching { receiptAdapter.fromJson(json) }.getOrNull()?.let { return it }
        }
        // Legacy raw bytes remain available, with their original time and explicitly unknown units.
        val json = prefs.getString("status_v${legacyVersion}_$key", null) ?: return null
        val status = runCatching { adapter.fromJson(json) }.getOrNull() ?: return null
        return CachedVehicleStatusReceipt(status,
            observedAt = prefs.getString("observed_at_v${legacyVersion}_$key", null))
    }

    internal fun saveStatus(scope: HistoryReadScope, vehicle: VehicleContext, status: CarStatus, observedAt: String? = null,
                            units: Units? = null, source: String? = null, fieldSources: Map<String, String> = emptyMap()) {
        val key = key(scope, vehicle) ?: return
        try {
            val json = adapter.toJson(status)
            val receipt = receiptAdapter.toJson(CachedVehicleStatusReceipt(status, units, source, observedAt, fieldSources))
            val editor = prefs.edit()
                .putString("status_v2_$key", json)
                .putString("observed_at_v2_$key", observedAt)
                .putString("receipt_v4_$key", receipt)
            if (scope.source == HistoryConnectionSource.CLOUD &&
                vehicle.stableIdentity.startsWith("cloud-origin:")) {
                val digest = MessageDigest.getInstance("SHA-256")
                    .digest(vehicle.stableIdentity.toByteArray(Charsets.UTF_8))
                    .joinToString("") { "%02x".format(it) }
                verifiedAliasKey(scope, vehicle, digest)?.let { alias ->
                    editor.putString("status_v3_$alias", json)
                        .putString("observed_at_v3_$alias", observedAt)
                        .putString("receipt_v4_$alias", receipt)
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
                                  live: CarStatus, observedAt: String?, units: Units? = null,
                                  source: String? = null, fieldSources: Map<String, String> = emptyMap()): CarStatus {
        saveStatus(scope, vehicle, live, observedAt, units, source, fieldSources)
        return live
    }

}
