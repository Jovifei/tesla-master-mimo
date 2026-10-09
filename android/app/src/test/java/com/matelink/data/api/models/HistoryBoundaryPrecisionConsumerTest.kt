package com.matelink.data.api.models

import com.matelink.data.sync.toSyncSummary
import com.matelink.domain.analytics.asCachedDetail
import com.matelink.domain.analytics.resolveDriveEnergy
import com.matelink.domain.analytics.toAnalysisDriveData
import com.matelink.domain.analytics.withResolvedDriveEnergy
import com.matelink.ui.screens.drives.presentDriveDetailEnergy
import com.squareup.moshi.Moshi
import org.junit.Assert.*
import org.junit.Test

/**
 * Synthetic JSON with the real Go historySessionMap envelope and fractional
 * source point times. Not production, natural provider, or authenticated data.
 */
class HistoryBoundaryPrecisionConsumerTest {
    private val moshi = Moshi.Builder().build()
    private val start = "2026-10-08T01:02:03.417Z"
    private val end = "2026-10-08T01:04:03.417Z"

    private fun drive(power: Double?): DriveDetail {
        val value = power?.toString() ?: "null"
        val json = """
          {"data":{"drive":{"drive_id":7,"source":"teslamate_archive",
            "quality_state":"observed","start_date":"$start","end_date":"$end",
            "odometer_details":{"odometer_distance":2.0},"energy_consumed_net":null,
            "drive_details":[
              {"date":"2026-10-08T01:02:03.417Z","power":$value},
              {"date":"2026-10-08T01:02:33.417Z","power":$value},
              {"date":"2026-10-08T01:03:03.417Z","power":$value},
              {"date":"2026-10-08T01:03:33.417Z","power":$value},
              {"date":"2026-10-08T01:04:03.417Z","power":$value}
            ]}}}
        """.trimIndent()
        return requireNotNull(moshi.adapter(DriveDetailResponse::class.java).fromJson(json)?.data?.drive)
    }

    @Test fun fractionalGoEnvelopeIntoMoshiRoomAndDisplayedEstimateIsExactWindow() {
        val detail = drive(1.0)
        val resolved = detail.resolveDriveEnergy()
        assertEquals("estimated", resolved.evidence.quality)
        assertEquals("source_sample_time", resolved.evidence.timeBasis)
        assertEquals("drive_power", resolved.evidence.measurementPoint)
        assertEquals(1.0 / 30.0, resolved.estimate.energyKwh!!, 1e-12)
        assertEquals(1.0, resolved.estimate.coverageRatio!!, 1e-12)
        val old = DriveData(driveId = 7, startDate = start, endDate = end,
            source = "teslamate_archive", energyConsumedNet = null,
            odometerDetails = DriveOdometerDetails(distance = 2.0)).toSyncSummary(3)!!
        val updated = old.withResolvedDriveEnergy(detail, resolved)
        val saved = updated.toAnalysisDriveData()
        assertEquals(start, saved.startDate)
        assertEquals(end, saved.endDate)
        assertEquals(resolved.evidence, saved.energyContract!!.netEnergy)
        val restored = saved.asCachedDetail().resolveDriveEnergy()
        assertEquals(resolved.estimate.energyKwh!!, restored.estimate.energyKwh!!, 1e-12)
        assertEquals("estimated", restored.evidence.quality)
        val ui = presentDriveDetailEnergy(restored.estimate.energyKwh,
            restored.estimate.efficiencyWhKm, restored.estimate.source.name.lowercase(),
            restored.estimate.coverageSeconds, restored.estimate.coverageRatio, restored.evidence)
        assertTrue(ui.isEstimated)
        assertEquals(1.0 / 30.0, ui.energyKwh!!, 1e-12)
    }

    @Test fun roundedOldApiBoundariesFailWithoutWideningCoverage() {
        val truncated = drive(1.0).copy(
            startDate = "2026-10-08T01:02:03Z",
            endDate = "2026-10-08T01:04:03Z"
        )
        val resolved = truncated.resolveDriveEnergy()
        assertNull(resolved.estimate.energyKwh)
        assertNull(resolved.evidence.valueKwh)
        assertEquals("unknown", resolved.evidence.quality)
        assertEquals("incomplete_power_coverage", resolved.estimate.qualityReason)
        assertTrue(resolved.estimate.coverageRatio!! < 1.0)
        assertTrue(resolved.estimate.coverageRatio!! > 0.99)
    }

    @Test fun matchingFractionalZeroAndNegativePowerRemainRealSignedEstimates() {
        for (power in listOf(0.0, -1.0)) {
            val energy = drive(power).resolveDriveEnergy()
            assertEquals(power / 30.0, energy.estimate.energyKwh!!, 1e-12)
            assertEquals("estimated", energy.evidence.quality)
        }
        val unknown = drive(null).resolveDriveEnergy()
        assertNull(unknown.estimate.energyKwh)
        assertNull(unknown.evidence.valueKwh)
    }
}
