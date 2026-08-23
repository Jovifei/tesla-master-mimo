package com.matelink.analytics

import com.matelink.data.local.entity.ChargeSummary
import com.matelink.data.local.entity.DriveSummary
import com.matelink.domain.analytics.toAnalysisChargeData
import com.matelink.domain.analytics.toAnalysisDriveData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HistorySummaryMapperTest {
    @Test
    fun driveSummaryKeepsUnknownZeroPlaceholdersUnavailable() {
        val drive = DriveSummary(
            driveId = 1,
            carId = 7,
            startDate = "2026-08-20T10:00:00Z",
            endDate = "2026-08-20T10:20:00Z",
            durationMin = 0,
            startAddress = "",
            endAddress = "",
            distance = 0.0,
            speedMax = 0,
            speedAvg = 0,
            powerMax = 0,
            powerMin = 0,
            startBatteryLevel = 0,
            endBatteryLevel = 0,
            outsideTempAvg = null,
            insideTempAvg = null,
            energyConsumed = null,
            efficiency = null
        )

        val mapped = drive.toAnalysisDriveData()

        assertNull(mapped.distance)
        assertNull(mapped.durationMin)
        assertNull(mapped.startAddress)
        assertNull(mapped.startBatteryLevel)
        assertNull(mapped.speedAvg)
        assertNull(mapped.energyConsumedNet)
    }

    @Test
    fun chargeSummaryPreservesARealZeroCost() {
        val charge = ChargeSummary(
            chargeId = 2,
            carId = 7,
            startDate = "2026-08-20T10:00:00Z",
            endDate = "2026-08-20T11:00:00Z",
            durationMin = 60,
            address = "Home",
            latitude = 0.0,
            longitude = 0.0,
            energyAdded = 12.5,
            energyUsed = 13.0,
            cost = 0.0,
            startBatteryLevel = 40,
            endBatteryLevel = 60,
            outsideTempAvg = 25.0,
            odometer = 1200.0
        )

        val mapped = charge.toAnalysisChargeData()

        assertEquals(0.0, mapped.cost ?: -1.0, 0.0)
        assertEquals(12.5, mapped.chargeEnergyAdded ?: -1.0, 0.0)
        assertEquals(40, mapped.startBatteryLevel)
        assertNull(mapped.latitude)
    }
}
