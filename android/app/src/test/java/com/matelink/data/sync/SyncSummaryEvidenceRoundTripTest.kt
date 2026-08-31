package com.matelink.data.sync

import com.matelink.data.api.models.ChargeBatteryDetails
import com.matelink.data.api.models.ChargeData
import com.matelink.data.api.models.DriveBatteryDetails
import com.matelink.data.api.models.DriveData
import com.matelink.data.api.models.DriveOdometerDetails
import com.matelink.data.repository.UnifiedHistoryRepository
import com.matelink.domain.analytics.toAnalysisChargeData
import com.matelink.domain.analytics.toAnalysisDriveData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SyncSummaryEvidenceRoundTripTest {
    @Test
    fun driveSyncWriteRoundTripsMissingZeroAndPartialEvidenceForOfflineAndUnifiedReads() {
        val missing = DriveData(driveId = 1, startDate = "2026-08-01T00:00:00Z", endDate = "2026-08-01T01:00:00Z")
            .toSyncSummary(-7)!!
            .toAnalysisDriveData()
        val zero = DriveData(
            driveId = 2,
            startDate = "2026-08-01T00:00:00Z",
            endDate = "2026-08-01T01:00:00Z",
            odometerDetails = DriveOdometerDetails(distance = 0.0),
            batteryDetails = DriveBatteryDetails(startBatteryLevel = 0)
        ).toSyncSummary(-7)!!.toAnalysisDriveData()
        val cached = DriveData(
            driveId = 3,
            startDate = "2026-08-01T00:00:00Z",
            endDate = "2026-08-01T01:00:00Z",
            odometerDetails = DriveOdometerDetails(odometerStart = 100.0, distance = 0.0),
            batteryDetails = DriveBatteryDetails(startBatteryLevel = 0, endBatteryLevel = 80)
        ).toSyncSummary(-7)!!.toAnalysisDriveData()

        assertNull(UnifiedHistoryRepository.mergeDrives(emptyList(), listOf(missing)).single().distance)
        assertEquals(0.0, UnifiedHistoryRepository.mergeDrives(emptyList(), listOf(zero)).single().distance ?: -1.0, 0.0)
        val merged = UnifiedHistoryRepository.mergeDrives(
            listOf(
                DriveData(
                    driveId = 3,
                    odometerDetails = DriveOdometerDetails(odometerEnd = 101.0),
                    batteryDetails = DriveBatteryDetails(endBatteryLevel = 79)
                )
            ),
            listOf(cached)
        ).single()
        assertEquals(100.0, merged.odometerDetails?.odometerStart ?: -1.0, 0.0)
        assertEquals(101.0, merged.odometerDetails?.odometerEnd ?: -1.0, 0.0)
        assertEquals(0.0, merged.distance ?: -1.0, 0.0)
        assertEquals(0, merged.startBatteryLevel)
        assertEquals(79, merged.endBatteryLevel)
    }

    @Test
    fun chargeSyncWriteRoundTripsMissingZeroAndPartialEvidenceForOfflineAndUnifiedReads() {
        val missing = ChargeData(chargeId = 1, startDate = "2026-08-01T00:00:00Z", endDate = "2026-08-01T01:00:00Z")
            .toSyncSummary(-7)!!
            .toAnalysisChargeData()
        val zero = ChargeData(
            chargeId = 2,
            startDate = "2026-08-01T00:00:00Z",
            endDate = "2026-08-01T01:00:00Z",
            chargeEnergyAdded = 0.0,
            cost = 0.0,
            batteryDetails = ChargeBatteryDetails(startBatteryLevel = 0)
        ).toSyncSummary(-7)!!.toAnalysisChargeData()
        val cached = ChargeData(
            chargeId = 3,
            startDate = "2026-08-01T00:00:00Z",
            endDate = "2026-08-01T01:00:00Z",
            chargeEnergyAdded = 0.0,
            batteryDetails = ChargeBatteryDetails(startBatteryLevel = 0, endBatteryLevel = 80)
        ).toSyncSummary(-7)!!.toAnalysisChargeData()

        assertNull(UnifiedHistoryRepository.mergeCharges(emptyList(), listOf(missing)).single().chargeEnergyAdded)
        assertEquals(0.0, UnifiedHistoryRepository.mergeCharges(emptyList(), listOf(zero)).single().cost ?: -1.0, 0.0)
        val merged = UnifiedHistoryRepository.mergeCharges(
            listOf(ChargeData(chargeId = 3, batteryDetails = ChargeBatteryDetails(endBatteryLevel = 79))),
            listOf(cached)
        ).single()
        assertEquals(0.0, merged.chargeEnergyAdded ?: -1.0, 0.0)
        assertEquals(0, merged.startBatteryLevel)
        assertEquals(79, merged.endBatteryLevel)
    }
}
