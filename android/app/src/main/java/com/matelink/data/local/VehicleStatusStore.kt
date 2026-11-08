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
    private val healthAdapter = moshi.adapter(BatteryHealth::class.java)
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

    internal fun getCachedBatteryHealth(carId: Int): BatteryHealth? {
        val json = prefs.getString("battery_health_$carId", null) ?: return null
        return runCatching { healthAdapter.fromJson(json) }.getOrNull()
    }

    internal fun saveBatteryHealth(carId: Int, health: BatteryHealth) {
        val json = healthAdapter.toJson(health)
        prefs.edit().putString("battery_health_$carId", json).apply()
    }

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

    internal fun reconcileVehicleStatus(cached: CarStatus?, incoming: CarStatus): CarStatus {
        if (cached == null) return incoming

        val reconciledBattery = when {
            incoming.batteryDetails == null -> cached.batteryDetails
            cached.batteryDetails == null -> incoming.batteryDetails
            else -> BatteryDetails(
                batteryLevel = incoming.batteryDetails.batteryLevel ?: cached.batteryDetails.batteryLevel,
                usableBatteryLevel = incoming.batteryDetails.usableBatteryLevel ?: cached.batteryDetails.usableBatteryLevel,
                estBatteryRange = incoming.batteryDetails.estBatteryRange ?: cached.batteryDetails.estBatteryRange,
                ratedBatteryRange = incoming.batteryDetails.ratedBatteryRange ?: cached.batteryDetails.ratedBatteryRange,
                idealBatteryRange = incoming.batteryDetails.idealBatteryRange ?: cached.batteryDetails.idealBatteryRange
            )
        }

        val reconciledCarStatus = when {
            incoming.carStatus == null -> cached.carStatus
            cached.carStatus == null -> incoming.carStatus
            else -> CarStatusDetails(
                healthy = incoming.carStatus.healthy ?: cached.carStatus.healthy,
                locked = incoming.carStatus.locked ?: cached.carStatus.locked,
                sentryMode = incoming.carStatus.sentryMode ?: cached.carStatus.sentryMode,
                windowsOpen = incoming.carStatus.windowsOpen ?: cached.carStatus.windowsOpen,
                doorsOpen = incoming.carStatus.doorsOpen ?: cached.carStatus.doorsOpen,
                trunkOpen = incoming.carStatus.trunkOpen ?: cached.carStatus.trunkOpen,
                frunkOpen = incoming.carStatus.frunkOpen ?: cached.carStatus.frunkOpen,
                isUserPresent = incoming.carStatus.isUserPresent ?: cached.carStatus.isUserPresent,
                centerDisplayState = incoming.carStatus.centerDisplayState ?: cached.carStatus.centerDisplayState,
                valetMode = incoming.carStatus.valetMode ?: cached.carStatus.valetMode,
                speedLimitMode = incoming.carStatus.speedLimitMode ?: cached.carStatus.speedLimitMode,
                mediaInfo = incoming.carStatus.mediaInfo ?: cached.carStatus.mediaInfo
            )
        }

        val reconciledClimate = when {
            incoming.climateDetails == null -> cached.climateDetails
            cached.climateDetails == null -> incoming.climateDetails
            else -> ClimateDetails(
                isClimateOn = incoming.climateDetails.isClimateOn ?: cached.climateDetails.isClimateOn,
                insideTemp = incoming.climateDetails.insideTemp ?: cached.climateDetails.insideTemp,
                outsideTemp = incoming.climateDetails.outsideTemp ?: cached.climateDetails.outsideTemp,
                isPreconditioning = incoming.climateDetails.isPreconditioning ?: cached.climateDetails.isPreconditioning,
                driverTempSetting = incoming.climateDetails.driverTempSetting ?: cached.climateDetails.driverTempSetting,
                passengerTempSetting = incoming.climateDetails.passengerTempSetting ?: cached.climateDetails.passengerTempSetting,
                fanStatus = incoming.climateDetails.fanStatus ?: cached.climateDetails.fanStatus,
                isFrontDefrosterOn = incoming.climateDetails.isFrontDefrosterOn ?: cached.climateDetails.isFrontDefrosterOn,
                isRearDefrosterOn = incoming.climateDetails.isRearDefrosterOn ?: cached.climateDetails.isRearDefrosterOn,
                seatHeaterLeft = incoming.climateDetails.seatHeaterLeft ?: cached.climateDetails.seatHeaterLeft,
                seatHeaterRight = incoming.climateDetails.seatHeaterRight ?: cached.climateDetails.seatHeaterRight,
                seatHeaterRearLeft = incoming.climateDetails.seatHeaterRearLeft ?: cached.climateDetails.seatHeaterRearLeft,
                seatHeaterRearCenter = incoming.climateDetails.seatHeaterRearCenter ?: cached.climateDetails.seatHeaterRearCenter,
                seatHeaterRearRight = incoming.climateDetails.seatHeaterRearRight ?: cached.climateDetails.seatHeaterRearRight,
                steeringWheelHeater = incoming.climateDetails.steeringWheelHeater ?: cached.climateDetails.steeringWheelHeater,
                defrostMode = incoming.climateDetails.defrostMode ?: cached.climateDetails.defrostMode,
                climateKeeperMode = incoming.climateDetails.climateKeeperMode ?: cached.climateDetails.climateKeeperMode,
                cabinOverheatProtection = incoming.climateDetails.cabinOverheatProtection ?: cached.climateDetails.cabinOverheatProtection,
                cabinOverheatProtectionCooling = incoming.climateDetails.cabinOverheatProtectionCooling ?: cached.climateDetails.cabinOverheatProtectionCooling,
                sideMirrorHeaters = incoming.climateDetails.sideMirrorHeaters ?: cached.climateDetails.sideMirrorHeaters
            )
        }

        val reconciledCharging = when {
            incoming.chargingDetails == null -> cached.chargingDetails
            cached.chargingDetails == null -> incoming.chargingDetails
            else -> incoming.chargingDetails.copy(
                pluggedIn = incoming.chargingDetails.pluggedIn ?: cached.chargingDetails.pluggedIn,
                chargingState = incoming.chargingDetails.chargingState ?: cached.chargingDetails.chargingState,
                chargeEnergyAdded = incoming.chargingDetails.chargeEnergyAdded ?: cached.chargingDetails.chargeEnergyAdded,
                chargeLimitSoc = incoming.chargingDetails.chargeLimitSoc ?: cached.chargingDetails.chargeLimitSoc,
                chargerPower = incoming.chargingDetails.chargerPower ?: cached.chargingDetails.chargerPower,
                chargerPhases = incoming.chargingDetails.chargerPhases ?: cached.chargingDetails.chargerPhases,
                chargerVoltage = incoming.chargingDetails.chargerVoltage ?: cached.chargingDetails.chargerVoltage,
                chargerActualCurrent = incoming.chargingDetails.chargerActualCurrent ?: cached.chargingDetails.chargerActualCurrent,
                chargeCurrentRequest = incoming.chargingDetails.chargeCurrentRequest ?: cached.chargingDetails.chargeCurrentRequest,
                chargeCurrentRequestMax = incoming.chargingDetails.chargeCurrentRequestMax ?: cached.chargingDetails.chargeCurrentRequestMax,
                scheduledChargingStartTime = incoming.chargingDetails.scheduledChargingStartTime ?: cached.chargingDetails.scheduledChargingStartTime,
                scheduledDepartureTime = incoming.chargingDetails.scheduledDepartureTime ?: cached.chargingDetails.scheduledDepartureTime,
                chargePortDoorOpen = incoming.chargingDetails.chargePortDoorOpen ?: cached.chargingDetails.chargePortDoorOpen,
                timeToFullCharge = incoming.chargingDetails.timeToFullCharge ?: cached.chargingDetails.timeToFullCharge,
                minutesToFullCharge = incoming.chargingDetails.minutesToFullCharge ?: cached.chargingDetails.minutesToFullCharge
            )
        }

        val reconciledTpms = when {
            incoming.tpmsDetails == null -> cached.tpmsDetails
            cached.tpmsDetails == null -> incoming.tpmsDetails
            incoming.tpmsDetails.pressureFl == null && incoming.tpmsDetails.pressureFr == null -> cached.tpmsDetails
            else -> incoming.tpmsDetails
        }

        val reconciledGeodata = when {
            incoming.carGeodata == null -> cached.carGeodata
            cached.carGeodata == null -> incoming.carGeodata
            incoming.carGeodata.latitude == null || incoming.carGeodata.longitude == null -> cached.carGeodata
            else -> incoming.carGeodata
        }

        val reconciledVersions = incoming.carVersions ?: cached.carVersions
        val reconciledOdometer = if (incoming.odometer != null && incoming.odometer > 0.0) incoming.odometer else cached.odometer
        val reconciledState = if (!incoming.state.isNullOrBlank() && incoming.state != "unknown") incoming.state else cached.state ?: incoming.state

        return incoming.copy(
            displayName = incoming.displayName ?: cached.displayName,
            state = reconciledState,
            stateSince = incoming.stateSince ?: cached.stateSince,
            odometer = reconciledOdometer,
            carStatus = reconciledCarStatus,
            carGeodata = reconciledGeodata,
            carVersions = reconciledVersions,
            drivingDetails = incoming.drivingDetails ?: cached.drivingDetails,
            climateDetails = reconciledClimate,
            batteryDetails = reconciledBattery,
            chargingDetails = reconciledCharging,
            tpmsDetails = reconciledTpms,
            commandAuthorization = incoming.commandAuthorization ?: cached.commandAuthorization
        )
    }

    /** Never re-date old GPS or other sparse fields as a new observation. */
    internal fun reconcileAndSave(scope: HistoryReadScope, vehicle: VehicleContext,
                                  live: CarStatus, observedAt: String?, units: Units? = null,
                                  source: String? = null, fieldSources: Map<String, String> = emptyMap()): CarStatus {
        val previous = getCachedReceipt(scope, vehicle)?.status
        val reconciled = reconcileVehicleStatus(previous, live)
        val mergedUnits = units ?: getCachedReceipt(scope, vehicle)?.units
        val mergedFieldSources = if (previous != null) {
            val prevSources = getCachedReceipt(scope, vehicle)?.fieldSources.orEmpty()
            prevSources + fieldSources
        } else fieldSources
        saveStatus(
            scope, vehicle, reconciled,
            observedAt ?: getCachedObservedAt(scope, vehicle),
            mergedUnits,
            source ?: getCachedReceipt(scope, vehicle)?.source,
            mergedFieldSources
        )
        return reconciled
    }

}
