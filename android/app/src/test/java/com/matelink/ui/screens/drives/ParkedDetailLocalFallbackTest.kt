package com.matelink.ui.screens.drives

import com.matelink.data.api.models.DriveData
import com.matelink.data.api.models.DriveBatteryDetails
import com.matelink.data.api.models.DriveOdometerDetails
import com.matelink.data.repository.toLocalSummary
import com.matelink.domain.analytics.toRawAnalysisDriveData
import com.matelink.domain.analytics.HistorySummaryEvidenceCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ParkedDetailLocalFallbackTest {

    private fun drive(
        driveId: Int, endBatteryLevel: Int, startBatteryLevel: Int = 35,
        startDate: String = "2026-10-23T18:40:00Z", endDate: String = "2026-10-23T18:42:00Z",
        source: String = "teslamate_archive", instance: String? = "instance-A"
    ) = DriveData(
        driveId = driveId, startDate = startDate, endDate = endDate, durationMin = 2,
        startAddress = "A Road", endAddress = "访云街 西蔡庙, Hangzhou",
        odometerDetails = DriveOdometerDetails(distance = 1.0), lengthUnit = "km",
        speedMax = 30, speedAvg = 20.0, powerMax = 10, powerMin = -5,
        batteryDetails = DriveBatteryDetails(startBatteryLevel, endBatteryLevel),
        source = source, sourceInstanceId = instance, qualityState = "observed"
    ).toLocalSummary(1)!!

    private fun older(endBatteryLevel: Int, endDate: String = "2026-10-23T20:59:00Z") =
        drive(10, endBatteryLevel, endDate = endDate)

    private fun newer(startBatteryLevel: Int, startDate: String = "2026-10-23T22:20:00Z") =
        drive(11, 99, startBatteryLevel, startDate = startDate)

    @Test fun localDetailDerivesBoundariesFromAdjacentDrives() {
        val detail = requireNotNull(buildLocalParkedDetail(10, 11, older(31), newer(100)))

        assertEquals("2026-10-23T20:59:00Z", detail.startDate)
        assertEquals("2026-10-23T22:20:00Z", detail.endDate)
        assertEquals("访云街 西蔡庙", detail.address)
        assertEquals(31, detail.startBatteryLevel)
        assertEquals(100, detail.endBatteryLevel)
        assertEquals(69, detail.batteryDelta)
        assertEquals("local_room_cache", detail.source)
        assertNull(detail.insideTempAverage)
        assertNull(detail.linkedCharge)
        assertEquals(0, detail.sampleCount)
        assertEquals(0.0, detail.coverageRatio!!, 0.0)
    }

    @Test fun outOfRangeBatteryStaysUnknownInsteadOfFabricating() {
        val detail = requireNotNull(buildLocalParkedDetail(10, 11, older(130), newer(-1)))

        assertNull(detail.startBatteryLevel)
        assertNull(detail.endBatteryLevel)
        assertNull(detail.batteryDelta)
    }

    @Test fun nonChineseAddressFallsBackToRawAddress() {
        val initial = older(31)
        val address = "123 Main Street, Springfield"
        val observed = initial.copy(endAddress = address,
            apiEvidence = HistorySummaryEvidenceCodec.encode(initial.toRawAnalysisDriveData().copy(endAddress = address)))
        val detail = requireNotNull(buildLocalParkedDetail(10, 11, observed, newer(32)))

        assertEquals("123 Main Street, Springfield", detail.address)
    }

    @Test fun differentLocalCarKeysOrReversedWindowCannotBeCombined() {
        assertNull(buildLocalParkedDetail(10, 11, older(31), newer(32).copy(carId = 2)))
        assertNull(buildLocalParkedDetail(10, 11, older(31), newer(32, startDate = "2026-10-23T20:00:00Z")))
        assertNull(buildLocalParkedDetail(10, 11, older(31), newer(32).copy(driveId = 12)))
    }

    @Test fun crossSourceInstanceAndUnknownReceiptCannotBecomeParkedHistory() {
        val own = older(31)
        assertNull(buildLocalParkedDetail(10, 11, own,
            drive(11, 99, 30, startDate = "2026-10-23T22:20:00Z", instance = "instance-B")))
        assertNull(buildLocalParkedDetail(10, 11, own,
            drive(11, 99, 30, startDate = "2026-10-23T22:20:00Z", source = "fleet_telemetry")))
        assertNull(buildLocalParkedDetail(10, 11, own,
            drive(11, 99, 30, startDate = "2026-10-23T22:20:00Z", instance = null)))
        assertNull(buildLocalParkedDetail(10, 11, own, newer(30).copy(apiEvidence = null)))
    }
}
