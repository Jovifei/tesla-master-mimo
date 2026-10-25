package com.matelink.ui.screens.drives

import com.matelink.data.local.entity.DriveSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ParkedDetailLocalFallbackTest {

    private fun drive(driveId: Int, endBatteryLevel: Int, startBatteryLevel: Int = 35) = DriveSummary(
        driveId = driveId, carId = 1,
        startDate = "2026-10-23T18:40:00Z", endDate = "2026-10-23T18:42:00Z",
        durationMin = 2,
        startAddress = "A Road", endAddress = "访云街 西蔡庙, Hangzhou",
        distance = 1.0, speedMax = 30, speedAvg = 20,
        powerMax = 10, powerMin = -5,
        startBatteryLevel = startBatteryLevel, endBatteryLevel = endBatteryLevel,
        outsideTempAvg = null, insideTempAvg = null,
        energyConsumed = null, efficiency = null
    )

    private fun older(endBatteryLevel: Int, endDate: String = "2026-10-23T20:59:00Z") =
        drive(10, endBatteryLevel).copy(endDate = endDate)

    private fun newer(startBatteryLevel: Int) =
        drive(11, 99, startBatteryLevel).copy(startDate = "2026-10-23T22:20:00Z")

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
        assertEquals(0.0, detail.coverageRatio, 0.0)
    }

    @Test fun outOfRangeBatteryStaysUnknownInsteadOfFabricating() {
        val detail = requireNotNull(buildLocalParkedDetail(10, 11, older(130), newer(-1)))

        assertNull(detail.startBatteryLevel)
        assertNull(detail.endBatteryLevel)
        assertNull(detail.batteryDelta)
    }

    @Test fun nonChineseAddressFallsBackToRawAddress() {
        val detail = requireNotNull(buildLocalParkedDetail(10, 11, older(31).copy(endAddress = "123 Main Street, Springfield"), newer(32)))

        assertEquals("123 Main Street, Springfield", detail.address)
    }

    @Test fun differentLocalCarKeysOrReversedWindowCannotBeCombined() {
        assertNull(buildLocalParkedDetail(10, 11, older(31), newer(32).copy(carId = 2)))
        assertNull(buildLocalParkedDetail(10, 11, older(31), newer(32).copy(startDate = "2026-10-23T20:00:00Z")))
        assertNull(buildLocalParkedDetail(10, 11, older(31), newer(32).copy(driveId = 12)))
    }
}
