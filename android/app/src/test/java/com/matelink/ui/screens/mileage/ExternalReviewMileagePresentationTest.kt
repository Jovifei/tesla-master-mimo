package com.matelink.ui.screens.mileage

import com.matelink.data.api.models.DriveBatteryDetails
import com.matelink.data.api.models.DriveData
import com.matelink.data.api.models.DriveOdometerDetails
import com.matelink.domain.analytics.buildMileageEvidence
import java.time.LocalDateTime
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Test

/** Actual daily aggregation and the value expressions called by the day row and its detail. */
class ExternalReviewMileagePresentationTest {
    private fun day(battery: DriveBatteryDetails?): DailyMileage {
        val drive = DriveData(1, startDate = "2026-10-14T10:00:00Z",
            endDate = "2026-10-14T11:00:00Z", source = "teslamate_archive",
            qualityState = "observed", lengthUnit = "km", batteryDetails = battery,
            odometerDetails = DriveOdometerDetails(distance = 10.0))
        val timed = Class.forName("com.matelink.ui.screens.mileage.TimedDrive")
            .getDeclaredConstructor(DriveData::class.java, LocalDateTime::class.java)
            .apply { isAccessible = true }
            .newInstance(drive, LocalDateTime.of(2026, 10, 14, 10, 0))
        val days = Class.forName("com.matelink.ui.screens.mileage.MileageViewModelKt")
            .getDeclaredMethod("computeDailyAggregation", List::class.java,
                List::class.java, YearMonth::class.java)
            .apply { isAccessible = true }
            .invoke(null, listOf(timed), emptyList<Any>(), YearMonth.of(2026, 10)) as List<DailyMileage>
        return days.single()
    }

    @Test fun missingSocStaysUnknownInBothActualDayRowAndDetail() {
        val day = day(null)
        val evidence = buildMileageEvidence(day.drives)
        assertEquals(0, evidence.batterySampleCount)
        assertEquals(0.0, day.totalBatteryUsage, 0.0) // Internal placeholder, not an observation.
        val detail = formatObservedBattery(day.totalBatteryUsage, evidence.batterySampleCount)
        assertEquals("—", detail)
        assertEquals(detail, formatMileageDayRowBattery(day.totalBatteryUsage, evidence.batterySampleCount))
    }

    @Test fun observedZeroSocUsageRemainsZeroInBothActualConsumers() {
        val day = day(DriveBatteryDetails(80, 80))
        val evidence = buildMileageEvidence(day.drives)
        assertEquals(1, evidence.batterySampleCount)
        assertEquals("0%", formatObservedBattery(day.totalBatteryUsage, evidence.batterySampleCount))
        assertEquals("0%", formatMileageDayRowBattery(day.totalBatteryUsage, evidence.batterySampleCount))
    }

    @Test fun observedPositiveSocUsageUsesTheSameValueInBothActualConsumers() {
        val day = day(DriveBatteryDetails(80, 77))
        val evidence = buildMileageEvidence(day.drives)
        assertEquals(1, evidence.batterySampleCount)
        assertEquals("3%", formatObservedBattery(day.totalBatteryUsage, evidence.batterySampleCount))
        assertEquals("3%", formatMileageDayRowBattery(day.totalBatteryUsage, evidence.batterySampleCount))
    }
}
