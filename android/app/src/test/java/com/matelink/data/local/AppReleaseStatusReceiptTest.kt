package com.matelink.data.local

import android.app.Application
import android.content.Context
import com.matelink.data.api.models.*
import com.matelink.ui.screens.battery.readCachedBatteryReceipt
import com.squareup.moshi.Moshi
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
class AppReleaseStatusReceiptTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val scope = HistoryReadScope(HistoryConnectionSource.CLOUD, "cloud", "fixture-account", "https://fixture.invalid")
    private val car = CarData(7, vehicleUid = "fixture-uid")
    private val units = Units("mi", "psi", "F")
    private fun store() = VehicleStatusStore(app, Moshi.Builder().build())
    @Before fun clean() {
        app.getSharedPreferences("vehicle_cached_status", Context.MODE_PRIVATE).edit().clear().commit()
        app.getSharedPreferences("vehicle_history_identity", Context.MODE_PRIVATE).edit().clear().commit()
    }
    private fun context() = VehicleContextStore(app).resolveVerifiedHistoryCar(car, scope.accountNamespace,
        scope.source, scope.serverIdentity, scope.effectiveApiOrigin)

    @Test fun sameReceiptUnitsSourceAndTimeSurviveProcessRecreationWithoutConversion() {
        val vehicle = context()
        val raw = CarStatus(odometer = 1234.5, climateDetails = ClimateDetails(insideTemp = 72.0),
            tpmsDetails = TpmsDetails(pressureFl = 42.0))
        val fields = mapOf("inside_temp" to "fleet_api", "tpms_pressure_fl" to "telemetry_mqtt")
        store().saveStatus(scope, vehicle, raw, "2026-10-26T01:02:03Z", units, "telemetry_mqtt", fields)
        val receipt = store().getCachedReceipt(scope, vehicle)!!
        assertEquals(raw, receipt.status)
        assertEquals(units, receipt.units)
        assertEquals("telemetry_mqtt", receipt.source)
        assertEquals("2026-10-26T01:02:03Z", receipt.observedAt)
        assertEquals(fields, receipt.fieldSources)
        assertEquals(72.0, receipt.displayStatus.insideTemp!!, 0.0)
        assertNull(store().getCachedReceipt(scope.copy(accountNamespace = "other"), vehicle))
        assertNull(store().getCachedReceipt(scope.copy(effectiveApiOrigin = "https://other.invalid"), vehicle))
    }

    @Test fun verifiedBatteryAliasReopensTheSameUnitsAndReplacementDoesNotBorrowOldUnits() {
        val vehicle = context()
        store().saveStatus(scope, vehicle, CarStatus(odometer = 10.0), "2026-10-26T01:00:00Z", units, "fleet_api")
        val contexts = VehicleContextStore(app)
        val digest = contexts.findVerifiedOriginCloudIdentityDigest(scope.accountNamespace!!,
            scope.effectiveApiOrigin, car.carId, vehicle.localHistoryCarId)!!
        val opaque = vehicle.copy(stableIdentity = contexts.originCloudRemoteOpaqueIdentity(
            scope.accountNamespace!!, scope.effectiveApiOrigin, car.carId))
        val cached = readCachedBatteryReceipt(scope, opaque, digest, store())!!
        assertEquals(units, cached.units)
        assertEquals(10.0, cached.displayStatus.odometer!!, 0.0)
        // An explicitly unitless NEW raw response cannot inherit the previous imperial labels.
        store().saveStatus(scope, vehicle, CarStatus(odometer = 11.0), "2026-10-26T02:00:00Z")
        val replacement = readCachedBatteryReceipt(scope, opaque, digest, store())!!
        assertNull(replacement.units)
        assertNull(replacement.displayStatus.odometer)
        assertEquals(11.0, replacement.status.odometer!!, 0.0)
    }

    @Test fun legacyUnitlessRawRecordIsRetainedButNeverGuessLabelled() {
        val vehicle = context()
        val original = CarStatus(odometer = 100.0, batteryDetails = BatteryDetails(batteryLevel = 0,
            ratedBatteryRange = 200.0), drivingDetails = DrivingDetails(speed = 0.0),
            climateDetails = ClimateDetails(insideTemp = 32.0), tpmsDetails = TpmsDetails(pressureFl = 40.0))
        store().saveStatus(scope, vehicle, original, "2026-10-26T01:00:00Z")
        // Retain only the original v2 raw record to exercise genuine legacy fallback.
        val prefs = app.getSharedPreferences("vehicle_cached_status", Context.MODE_PRIVATE)
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith("receipt_v4_") }.forEach { editor.remove(it) }
        editor.commit()
        val receipt = store().getCachedReceipt(scope, vehicle)!!
        assertEquals(original, receipt.status)
        assertNull(receipt.units)
        assertNull(receipt.displayStatus.odometer)
        assertNull(receipt.displayStatus.ratedBatteryRangeKm)
        assertNull(receipt.displayStatus.insideTemp)
        assertNull(receipt.displayStatus.tpmsDetails?.pressureFl)
        assertEquals(0, receipt.displayStatus.batteryLevel)
    }
}
