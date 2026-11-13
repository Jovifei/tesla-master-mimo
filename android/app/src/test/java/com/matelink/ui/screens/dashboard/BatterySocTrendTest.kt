package com.matelink.ui.screens.dashboard

import com.matelink.data.api.models.ChargeData
import com.matelink.data.api.models.ChargeBatteryDetails
import com.matelink.data.api.models.DriveBatteryDetails
import com.matelink.data.api.models.DriveData
import com.matelink.data.local.HistoryConnectionSource
import com.matelink.data.local.VehicleContext
import com.matelink.data.repository.UnifiedHistory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class BatterySocTrendTest {
    @Test fun onlyRecordedValuesInTheLocalWeekAndSourceBreaksAreShown() {
        val history = UnifiedHistory(VehicleContext(1, "fixture", -1, HistoryConnectionSource.CLOUD, "fixture"),
            drives = listOf(
                DriveData(1, startDate = "2026-11-06T15:59:00Z", endDate = "2026-11-06T16:10:00Z",
                    batteryDetails = DriveBatteryDetails(startBatteryLevel = 41, endBatteryLevel = 40),
                    qualityState = "observed", source = "teslamate_archive"),
                DriveData(2, startDate = "2026-11-12T02:00:00Z", endDate = "2026-11-12T02:30:00Z",
                    batteryDetails = DriveBatteryDetails(startBatteryLevel = 90, endBatteryLevel = 70),
                    qualityState = "quarantined", source = "telemetry_mqtt")
            ),
            charges = listOf(ChargeData(3, startDate = "2026-11-06T17:00:00Z", endDate = "2026-11-06T17:20:00Z",
                batteryDetails = ChargeBatteryDetails(startBatteryLevel = 0, endBatteryLevel = 24),
                qualityState = "observed", source = "telemetry_mqtt")),
            drivesFromRemote = false, chargesFromRemote = false)
        val result = projectBatterySocWeek(history, Instant.parse("2026-11-13T08:00:00Z"), ZoneId.of("Asia/Shanghai"))
        assertEquals(Instant.parse("2026-11-06T16:00:00Z").toEpochMilli(), result.domainStartMs)
        assertEquals(listOf(40f, 0f, 24f), result.values)
        assertEquals(setOf(1), result.gaps)
        assertTrue(result.xFractions.zipWithNext().all { (before, after) -> before < after })
    }
}
