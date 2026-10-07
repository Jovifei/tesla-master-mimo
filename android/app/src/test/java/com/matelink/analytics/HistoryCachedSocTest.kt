package com.matelink.analytics

import com.matelink.data.local.entity.ChargeSummary
import com.matelink.data.local.entity.DriveSummary
import com.matelink.domain.analytics.HistorySummaryEvidenceCodec
import com.matelink.domain.analytics.toAnalysisChargeData
import com.matelink.domain.analytics.toAnalysisDriveData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class HistoryCachedSocTest {
    @Test
    fun decodedMissingSocNeverUsesRoomScalarPlaceholders() {
        listOf(
            "",
            ",\"battery_details\":null",
            ",\"battery_details\":{}",
            ",\"battery_details\":{\"start_battery_level\":null,\"end_battery_level\":null}"
        ).forEach { fields ->
            val driveJson = "{\"drive_id\":1$fields}"
            val chargeJson = "{\"charge_id\":1$fields}"
            assertNotNull(HistorySummaryEvidenceCodec.decodeDrive(driveJson))
            assertNotNull(HistorySummaryEvidenceCodec.decodeCharge(chargeJson))

            // Nonzero legacy columns also cannot fill an explicitly unknown API field.
            val drive = driveSummary(driveJson, start = 80, end = 60).toAnalysisDriveData()
            val charge = chargeSummary(chargeJson, start = 20, end = 70).toAnalysisChargeData()
            assertNull(drive.startBatteryLevel)
            assertNull(drive.endBatteryLevel)
            assertNull(charge.startBatteryLevel)
            assertNull(charge.endBatteryLevel)
        }
    }

    @Test
    fun decodedObservedZeroAndPartialSocRemainDistinctFromUnknown() {
        listOf(
            "\"start_battery_level\":0" to (0 to null),
            "\"end_battery_level\":0" to (null to 0),
            "\"start_battery_level\":0,\"end_battery_level\":0" to (0 to 0),
            "\"start_battery_level\":0,\"end_battery_level\":60" to (0 to 60),
            "\"start_battery_level\":40,\"end_battery_level\":null" to (40 to null)
        ).forEach { (fields, expected) ->
            val drive = driveSummary("{\"drive_id\":1,\"battery_details\":{$fields}}")
                .toAnalysisDriveData()
            val charge = chargeSummary("{\"charge_id\":1,\"battery_details\":{$fields}}")
                .toAnalysisChargeData()
            assertEquals(expected.first, drive.startBatteryLevel)
            assertEquals(expected.second, drive.endBatteryLevel)
            assertEquals(expected.first, charge.startBatteryLevel)
            assertEquals(expected.second, charge.endBatteryLevel)
        }
    }

    @Test
    fun absentOrUnreadableEvidenceDoesNotPromoteLegacyZeroToObservedSoc() {
        listOf(null, "not-json", "{}").forEach { evidence ->
            val drive = driveSummary(evidence, start = 0, end = 60).toAnalysisDriveData()
            val charge = chargeSummary(evidence, start = 40, end = 0).toAnalysisChargeData()
            assertNull(drive.startBatteryLevel)
            assertEquals(60, drive.endBatteryLevel)
            assertEquals(40, charge.startBatteryLevel)
            assertNull(charge.endBatteryLevel)
        }
    }

    private fun driveSummary(evidence: String?, start: Int = 0, end: Int = 0) = DriveSummary(
        driveId = 1, carId = -7,
        startDate = "2026-10-04T07:00:00Z", endDate = "2026-10-04T07:20:00Z",
        durationMin = 20, startAddress = "", endAddress = "",
        distance = 10.0, speedMax = 50, speedAvg = 30, powerMax = 0, powerMin = 0,
        startBatteryLevel = start, endBatteryLevel = end,
        outsideTempAvg = null, insideTempAvg = null, energyConsumed = null,
        efficiency = null, apiEvidence = evidence
    )

    private fun chargeSummary(evidence: String?, start: Int = 0, end: Int = 0) = ChargeSummary(
        chargeId = 1, carId = -7,
        startDate = "2026-10-04T07:00:00Z", endDate = "2026-10-04T07:20:00Z",
        durationMin = 20, address = "", latitude = 0.0, longitude = 0.0,
        energyAdded = 10.0, energyUsed = null, cost = null,
        startBatteryLevel = start, endBatteryLevel = end,
        outsideTempAvg = null, odometer = 0.0, apiEvidence = evidence
    )
}
