package com.matelink.data.repository

import com.matelink.data.api.models.ChargeBatteryDetails
import com.matelink.data.api.models.ChargeData
import com.matelink.data.api.models.DriveBatteryDetails
import com.matelink.data.api.models.DriveData
import com.matelink.data.api.models.DriveOdometerDetails
import org.junit.Assert.*
import org.junit.Test

class AppReleaseHistoryWindowTest {
    private fun charge(start: String, cost: Double? = null) = ChargeData(7,
        startDate = start, endDate = start.replace("10:00", "11:00"),
        source = "teslamate_archive", sourceInstanceId = "synthetic-instance",
        sourceRecordId = "charge-7", currencyCode = "CNY", qualityState = "observed", cost = cost)

    @Test fun changedChargeWindowCannotBorrowOldCostSocEnergyOrAddress() {
        val old = charge("2026-10-20T10:00:00Z", 12.0).copy(chargeEnergyAdded = 15.0,
            batteryDetails = ChargeBatteryDetails(startBatteryLevel = 20, endBatteryLevel = 80),
            address = "Synthetic old window")
        val incoming = charge("2026-10-21T10:00:00Z")
        val result = UnifiedHistoryRepository.mergeCharges(listOf(incoming), listOf(old)).single()
        assertEquals(incoming.startDate, result.startDate)
        assertNull(result.cost)
        assertNull(result.startBatteryLevel)
        assertNull(result.batteryInputKwh)
        assertNull(result.address)
    }

    @Test fun sameChargeWindowWeakNullKeepsQualifiedOldValuesAndTrueZeroCanCorrect() {
        val old = charge("2026-10-20T10:00:00Z", 12.0)
        val weak = charge("2026-10-20T10:00:00Z")
        assertEquals(12.0, UnifiedHistoryRepository.mergeCharges(listOf(weak), listOf(old)).single().cost!!, 0.0)
        assertEquals(0.0, UnifiedHistoryRepository.mergeCharges(listOf(weak.copy(cost = 0.0)), listOf(old)).single().cost!!, 0.0)
    }

    @Test fun changedDriveWindowCannotBorrowOldDistanceSocOrAddresses() {
        val old = DriveData(4, startDate = "2026-10-20T10:00:00Z", endDate = "2026-10-20T11:00:00Z",
            source = "teslamate_archive", sourceInstanceId = "synthetic-instance", sourceRecordId = "drive-4",
            qualityState = "observed", startAddress = "Synthetic old start",
            odometerDetails = DriveOdometerDetails(distance = 20.0),
            batteryDetails = DriveBatteryDetails(startBatteryLevel = 80, endBatteryLevel = 70))
        val incoming = old.copy(startDate = "2026-10-21T10:00:00Z", endDate = "2026-10-21T11:00:00Z",
            startAddress = null, odometerDetails = null, batteryDetails = null)
        val result = UnifiedHistoryRepository.mergeDrives(listOf(incoming), listOf(old)).single()
        assertNull(result.startAddress)
        assertNull(result.distanceKm)
        assertNull(result.startBatteryLevel)
    }
}
