package com.matelink.domain.analytics

import com.matelink.data.api.models.DriveBatteryDetails
import com.matelink.data.api.models.DriveData
import com.matelink.data.api.models.DriveOdometerDetails
import com.matelink.data.sync.toSyncSummary
import org.junit.Assert.assertEquals
import org.junit.Test

/** Equal provenance rank establishes quality, never observation freshness. */
class CapacityFreshnessRecoveryTest {
    private val raw = DriveData(4, startDate = "2026-10-13T00:00:00Z",
        endDate = "2026-10-13T00:01:00Z", source = "teslamate_archive",
        qualityState = "observed", batteryDetails = DriveBatteryDetails(80, 79),
        odometerDetails = DriveOdometerDetails(distance = 2.0))
    private val detail = raw.asCachedDetail()
    private fun resolved(capacity: Double) = detail.resolveDriveEnergy(
        batteryCapacityKwh = capacity, capacityBasis = "api_reported_current_capacity")

    @Test fun unknownSameRankLateCapacityCannotReplaceVerifiedNinety() {
        val saved = raw.toSyncSummary(-7)!!.withResolvedDriveEnergy(detail, resolved(90.0))
        val result = saved.withResolvedDriveEnergy(detail, resolved(60.0))
        assertEquals(90.0, result.toAnalysisDriveData().energyContract!!.netEnergy!!.capacityKwh!!, 0.0)
        assertEquals(0.9, result.energyConsumed!!, 1e-12)
        assertEquals(450.0, result.efficiency!!, 1e-12)
    }

    @Test fun unknownSameRankLargerCapacityAlsoCannotReplaceVerifiedSixty() {
        val saved = raw.toSyncSummary(-7)!!.withResolvedDriveEnergy(detail, resolved(60.0))
        val result = saved.withResolvedDriveEnergy(detail, resolved(90.0))
        assertEquals(60.0, result.toAnalysisDriveData().energyContract!!.netEnergy!!.capacityKwh!!, 0.0)
        assertEquals(0.6, result.energyConsumed!!, 1e-12)
    }
}
