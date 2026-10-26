package com.matelink.ui.screens.dashboard

import com.matelink.R
import com.matelink.data.api.models.CarStatus
import com.matelink.data.api.models.ChargingDetails
import com.matelink.data.api.models.DrivingDetails
import com.matelink.data.api.models.Units
import com.matelink.data.repository.ApiErrorKind
import com.matelink.domain.telemetry.SnapshotFreshness
import org.junit.Assert.*
import org.junit.Test

/** Functions are the actual data expressions consumed by the existing Compose HUD/badge. */
class AppReleaseDashboardValuesTest {
    @Test fun absentHudMetricsAreUnknownWhileObservedZeroRemainsZero() {
        val missing = CarStatus(state = "driving", chargingDetails = ChargingDetails(chargerVoltage = 230.0))
        assertEquals("--", dashboardHudSpeed(missing, Units("km")))
        assertEquals("--", dashboardHudChargeEnergy(missing))
        assertEquals("230V / --A", dashboardHudVoltageCurrent(missing))
        val zero = CarStatus(drivingDetails = DrivingDetails(speed = 0.0),
            chargingDetails = ChargingDetails(chargeEnergyAdded = 0.0, chargerVoltage = 230.0, chargerActualCurrent = 0.0))
        assertEquals("0 km/h", dashboardHudSpeed(zero, Units("km")))
        assertEquals("0.0 kWh", dashboardHudChargeEnergy(zero))
        assertEquals("230V / 0A", dashboardHudVoltageCurrent(zero))
    }

    @Test fun recentOrUnavailableHttpResponseDoesNotAssertVehicleSleep() {
        assertFalse(dashboardIsAsleep("online", ApiErrorKind.SERVICE_UNAVAILABLE, SnapshotFreshness.RECENT))
        assertFalse(dashboardIsAsleep(null, ApiErrorKind.SERVICE_UNAVAILABLE, SnapshotFreshness.UNAVAILABLE))
        assertTrue(dashboardIsAsleep("asleep", null, SnapshotFreshness.HISTORY))
        assertEquals(R.string.snapshot_source_recent, dashboardSnapshotLabelRes(SnapshotFreshness.RECENT, false))
        assertEquals(R.string.snapshot_source_asleep, dashboardSnapshotLabelRes(SnapshotFreshness.RECENT, true))
    }
}
