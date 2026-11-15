package com.matelink.releaseaudit

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.matelink.data.api.models.*
import com.matelink.data.local.ConnectionMode
import com.matelink.data.local.TpmsAlertProfile
import com.matelink.data.sync.HistoryResourceFixture
import com.matelink.data.sync.toAggregate
import com.matelink.domain.analytics.projectEffectiveChargeCost
import com.matelink.domain.telemetry.SnapshotFreshness
import com.matelink.domain.telemetry.snapshotEvidence
import com.matelink.ui.navigation.Screen
import com.matelink.ui.navigation.shouldRedirectToTeslaLogin
import com.matelink.ui.screens.charges.ChargeType
import com.matelink.ui.screens.charges.historyChargeType
import com.matelink.ui.screens.drives.historyEnergy
import com.matelink.ui.screens.drives.usableDriveRoutePoints
import com.matelink.ui.screens.settings.TariffConfigViewModel
import java.io.File
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Release data-chain regressions promoted from the fixed build72 audit. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class AppReleaseDataChainRegressionTest {
    @get:Rule val temp = TemporaryFolder()

    private val paid = ChargeData(4, source = "teslamate_archive", currencyCode = "CNY",
        sourceInstanceId = "synthetic-provider", cost = 12.0, address = "Synthetic third-party charger")
    private val observed = Instant.parse("2026-10-26T10:00:00Z")

    @Test fun vehicleFreeFlagMustNotEraseAnObservedThirdPartyBill() {
        val result = projectEffectiveChargeCost(paid, -7, null, "CNY",
            freeSupercharging = true, isDcCharge = true)
        assertEquals("Known paid session must not become free from a vehicle-wide flag",
            12.0, result.cost ?: Double.NaN, 0.0)
    }

    @Test fun controlProviderBillWithoutFreeFlagRemainsTwelve() {
        assertEquals(12.0, projectEffectiveChargeCost(paid, -7, null, "CNY").cost!!, 0.0)
    }

    @Test fun powerOnlyArchivePointMustNotBecomeObservedACAfterAggregation() {
        val detail = ChargeDetail(9, source = "teslamate_archive",
            chargePoints = listOf(ChargePoint(chargerDetails = ChargerDetails(chargerPower = 80.0))))
        val aggregate = detail.toAggregate(-7, 0L)
        val dcIds = if (aggregate.isFastCharger) setOf(9) else emptySet()
        assertEquals("Processed is not affirmative AC evidence", ChargeType.UNKNOWN,
            historyChargeType(ChargeData(9, source = "teslamate_archive"), dcIds, setOf(9)))
    }

    @Test fun roundTripMustKeepTheActualReturnToItsStart() {
        val a = 30.30 to 120.10
        val b = 30.31 to 120.12
        val actual = usableDriveRoutePoints(listOf(
            DrivePosition(latitude = a.first, longitude = a.second),
            DrivePosition(latitude = b.first, longitude = b.second),
            DrivePosition(latitude = a.first, longitude = a.second)))
        assertEquals(listOf(a, b, a), actual)
    }

    @Test fun controlInvalidCoordinatesCannotInventARoute() {
        assertTrue(usableDriveRoutePoints(listOf(
            DrivePosition(latitude = 0.0, longitude = 0.0),
            DrivePosition(latitude = Double.NaN, longitude = 120.10))).isEmpty())
    }

    @Test fun equalIntegerSocWithoutEnergySamplesMustNotInvent145WhPerKm() {
        val drive = DriveData(10, source = "telemetry_mqtt",
            startDate = "2026-10-26T10:00:00Z", endDate = "2026-10-26T10:03:00Z",
            odometerDetails = DriveOdometerDetails(distance = 0.8), durationMin = 3,
            batteryDetails = DriveBatteryDetails(startBatteryLevel = 60, endBatteryLevel = 60))
        assertNull("No energy/counter/power observation supports a numeric energy",
            drive.historyEnergy(batteryCapacityKwh = 60.0).energyKwh)
    }

    @Test fun sourceTokenEmittedByTheBackendMustNotBeClassifiedUnavailable() {
        assertNotEquals(SnapshotFreshness.UNAVAILABLE,
            snapshotEvidence("telemetry_mqtt", observed.toString(), emptyMap(), now = observed).freshness)
    }

    @Test fun controlKnownFleetSourceWithTimestampIsLive() {
        assertEquals(SnapshotFreshness.LIVE,
            snapshotEvidence("fleet_api", observed.toString(), emptyMap(), now = observed).freshness)
    }

    @Test fun expiredSessionAfterLoginFromColdLoginDestinationMustRedirect() {
        assertTrue(shouldRedirectToTeslaLogin(Screen.TeslaLogin, ConnectionMode.TESLA_CLOUD,
            isAuthenticated = false, currentRoute = "Dashboard"))
    }

    @Test fun profileSavedUnderTheUiCarKeyMustBeReadableUnderTheWorkerKey() = runTest {
        val f = HistoryResourceFixture(File(temp.root, "profile.db"))
        f.initialize(cloud = true)
        try {
            val profile = TpmsAlertProfile(2.9, 2.6, 3.4, enabled = true)
            // This is the actual SettingsVM writer helper; worker reads the verified local key.
            val binding = com.matelink.ui.screens.settings.resolveTpmsProfileBinding(1, f.vehicleContexts)
            com.matelink.ui.screens.settings.saveBoundTpmsProfile(f.settings, f.vehicleContexts, binding, profile)
            assertEquals(profile, f.settings.getTpmsAlertProfile(f.localId(1)))
        } finally { f.close() }
    }

    @Test fun peakTariffInputMustRejectNonFiniteNumbers() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val f = HistoryResourceFixture(File(temp.root, "tariff.db"))
        f.initialize(cloud = true)
        val vm = TariffConfigViewModel(f.settings)
        try {
            vm.updatePeakPrice(Double.NaN)
            assertTrue("A displayed/saved rate must be finite", vm.uiState.value.peakPrice.isFinite())
        } finally { vm.viewModelScope.cancel(); f.close(); Dispatchers.resetMain() }
    }
}
