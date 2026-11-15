package com.matelink.ui.screens.battery

import com.matelink.data.api.models.BatteryDetails
import com.matelink.data.api.models.CarStatus
import com.matelink.data.api.models.DriveBatteryDetails
import com.matelink.data.api.models.DriveData
import com.matelink.data.api.models.DriveRange
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** These are the exact pure projections invoked by BatteryViewModel's normal consumers. */
class BatteryActualProjectionRegressionTest {
    @Test fun missingUsableSocDoesNotBorrowObservedMainSoc() {
        val stats = requireNotNull(computeBatteryStats(BatteryUiState(carStatus = CarStatus(
            batteryDetails = BatteryDetails(batteryLevel = 80)))))
        assertEquals(80, stats.batteryLevelObserved)
        assertNull("usable SOC was not observed", stats.usableBatteryLevel)
    }

    @Test fun explicitUsableZeroRemainsObserved() {
        val stats = requireNotNull(computeBatteryStats(BatteryUiState(carStatus = CarStatus(
            batteryDetails = BatteryDetails(batteryLevel = 80, usableBatteryLevel = 0)))))
        assertEquals(0, stats.usableBatteryLevel)
    }

    private fun drive() = DriveData(7, source = "telemetry_mqtt", lengthUnit = "km",
        startDate = "2026-10-20T10:00:00Z", endDate = "2026-10-20T10:20:00Z",
        batteryDetails = DriveBatteryDetails(80, 70), rangeRated = DriveRange(null, 350.0), outsideTempAvg = 20.0)

    @Test fun trendChoosesEndSocWithEndRangeWhenStartRangeIsMissing() {
        val observation = requireNotNull(drive().toBatteryTrendSample())
        assertEquals(70.0, observation.socPercent!!, 0.0)
        assertEquals(350.0, observation.ratedRangeKm!!, 0.0)
        assertEquals(500.0, observation.ratedRangeKm!! / (observation.socPercent!! / 100.0), 1e-9)
    }

    @Test fun disjointSocAndRangeCannotBecomeAnObservation() {
        val observation = drive().copy(batteryDetails = DriveBatteryDetails(80, null)).toBatteryTrendSample()
        assertNull("a start SOC and an end range have no shared boundary", observation)
    }

    @Test fun endBoundaryUsesItsOwnCalendarDateAndReceiptTemperatureUnit() {
        val observation = requireNotNull(drive().copy(startDate = "2026-10-20T23:50:00Z",
            endDate = "2026-10-21T00:10:00Z", outsideTempAvg = 68.0, temperatureUnit = "F").toBatteryTrendSample())
        assertEquals(LocalDate.of(2026, 10, 21), observation.date)
        assertEquals(20.0, observation.temperatureC!!, 1e-9)
        assertNull(drive().copy(temperatureUnit = "").toBatteryTrendSample())
    }

    @Test fun invalidUsableSocStaysUnknownEvenWithValidObservedMainSoc() {
        for (value in listOf(-1, 101)) {
            val stats = requireNotNull(computeBatteryStats(BatteryUiState(carStatus = CarStatus(
                batteryDetails = BatteryDetails(batteryLevel = 80, usableBatteryLevel = value)))))
            assertEquals(80, stats.batteryLevelObserved)
            assertNull(stats.usableBatteryLevel)
        }
    }
}
