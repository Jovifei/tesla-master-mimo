package com.matelink.ui.screens.dashboard

import com.matelink.data.api.models.CarStatus
import com.matelink.data.api.models.CarStatusDetails
import com.matelink.data.api.models.DrivingDetails
import com.matelink.data.api.models.TpmsDetails
import com.matelink.data.api.models.Units
import com.matelink.data.local.TirePosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VehicleStatusPresentationTest {
    @Test
    fun drivingMetricsAppearOnlyForDrivingStateAndKeepZeroAndNegativePower() {
        val parked = CarStatus(
            state = "online",
            drivingDetails = DrivingDetails(speed = 0.0, power = 0.0, shiftState = "P")
        )
        assertNull(drivingTelemetryFor(parked))

        val driving = CarStatus(
            state = "driving",
            drivingDetails = DrivingDetails(speed = 0.0, power = -9.0, shiftState = "D")
        )
        val telemetry = drivingTelemetryFor(driving)
        assertEquals(0.0, telemetry?.speed ?: -1.0, 0.0)
        assertEquals(PowerDirection.REGENERATING, powerDirection(telemetry?.power))
        assertEquals(ShiftState.DRIVE, telemetry?.shiftState)
    }

    @Test
    fun openingPanelContainsOnlyTheFourAllowedOpenings() {
        val status = CarStatus(
            carStatus = CarStatusDetails(
                doorsOpen = true,
                windowsOpen = false,
                frunkOpen = null,
                trunkOpen = true
            )
        )

        assertEquals(
            setOf(VehicleOpening.DOORS, VehicleOpening.TRUNK),
            openVehicleOpenings(status)
        )
        assertTrue(shouldShowOpeningPanel(status))
        assertFalse(shouldShowOpeningPanel(CarStatus(carStatus = CarStatusDetails())))
    }

    @Test
    fun tpmsWarningsRemainSeparateFromPressureValues() {
        val warnings = warningTires(
            TpmsDetails(
                pressureFl = 2.5,
                warningFl = true,
                warningRr = false
            )
        )

        assertEquals(setOf(TirePosition.FL), warnings)
    }

    @Test
    fun pressureFormattingUsesTheReportedUnit() {
        assertEquals("2.5 bar", formatPressure(2.5, Units(unitOfPressure = "bar")))
        assertEquals("2.5 psi", formatPressure(2.5, Units(unitOfPressure = "psi")))
    }

    @Test
    fun locationDisplayUsesGeofenceThenCachedAddressThenCoordinates() {
        assertEquals("Home", locationDisplay("Home", "Shanghai", 31.2, 121.5))
        assertEquals("Shanghai", locationDisplay(null, "Shanghai", 31.2, 121.5))
        assertEquals("31.2000, 121.5000", locationDisplay(null, null, 31.2, 121.5))
        assertNull(locationDisplay(null, null, null, null))
    }

    @Test
    fun recentMqttSnapshotHasItsOwnSourceCategory() {
        assertEquals(SnapshotSourceKind.RECENT, snapshotSourceKind("mqtt_latest"))
    }

    @Test
    fun trimLabelsAreDerivedOnlyFromKnownTrimSignals() {
        assertEquals(VehicleTrim.PERFORMANCE, vehicleTrimFor("P74D"))
        assertEquals(VehicleTrim.LONG_RANGE, vehicleTrimFor("74D"))
        assertEquals(VehicleTrim.STANDARD_RANGE, vehicleTrimFor("50"))
        assertNull(vehicleTrimFor("unknown"))
    }
}
