package com.matelink.domain.analytics

import com.matelink.data.api.models.ChargeData
import com.matelink.data.api.models.DriveBatteryDetails
import com.matelink.data.api.models.DriveData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StandbyHistoryDerivationTest {
    @Test
    fun derivesParkingGapFromAdjacentDrivesWithoutInventingPower() {
        val windows = buildLocalStandbyWindows(
            drives = listOf(
                drive(1, "2026-08-30T08:00:00Z", "2026-08-30T09:00:00Z", endAddress = "家", endBattery = 80),
                drive(2, "2026-08-30T18:00:00Z", "2026-08-30T19:00:00Z", startAddress = "家", startBattery = 75)
            ),
            charges = emptyList()
        )

        assertEquals(1, windows.size)
        assertEquals("2026-08-30T09:00:00Z", windows.single().startDate)
        assertEquals("2026-08-30T18:00:00Z", windows.single().endDate)
        assertEquals(-5, windows.single().batteryDelta)
        assertNull(windows.single().energyKwh)
        assertNull(windows.single().averagePowerW)
        assertEquals("local_history", windows.single().source)
    }

    @Test
    fun ignoresParkingGapThatContainsACharge() {
        val windows = buildLocalStandbyWindows(
            drives = listOf(
                drive(1, "2026-08-30T08:00:00Z", "2026-08-30T09:00:00Z", endBattery = 80),
                drive(2, "2026-08-30T18:00:00Z", "2026-08-30T19:00:00Z", startBattery = 90)
            ),
            charges = listOf(
                ChargeData(
                    chargeId = 3,
                    startDate = "2026-08-30T12:00:00Z",
                    endDate = "2026-08-30T13:00:00Z"
                )
            )
        )

        assertTrue(windows.isEmpty())
    }

    private fun drive(
        id: Int,
        start: String,
        end: String,
        startAddress: String? = null,
        endAddress: String? = null,
        startBattery: Int? = null,
        endBattery: Int? = null
    ) = DriveData(
        driveId = id,
        startDate = start,
        endDate = end,
        startAddress = startAddress,
        endAddress = endAddress,
        batteryDetails = DriveBatteryDetails(startBattery, endBattery)
    )
}
