package com.matelink.ui.screens.drives

import com.matelink.R
import com.matelink.data.api.models.DriveDetail
import com.matelink.data.api.models.DrivePosition
import org.junit.Assert.assertEquals
import org.junit.Test

class DriveDetailSampleMeanLabelTest {
    private val unavailable = DriveDetailEnergyPresentation(null, null, null, null, null)
    @Test fun irregularSamplesAreCalledSampleMeansRatherThanWholeTripAverages() {
        val detail = DriveDetail(9, startDate = "2026-10-20T00:00:00Z", endDate = "2026-10-20T00:10:00Z", lengthUnit = "km",
            positions = listOf(DrivePosition(date = "2026-10-20T00:00:00Z", speed = 0.0, power = 0.0),
                DrivePosition(date = "2026-10-20T00:00:01Z", speed = 90.0, power = 90.0),
                DrivePosition(date = "2026-10-20T00:10:00Z", speed = 90.0, power = 90.0)))
        val stats = calculateDriveDetailStats(detail, unavailable)
        assertEquals(60.0, requireNotNull(stats.speedAvg), 0.001)
        assertEquals(60.0, requireNotNull(stats.powerAvg), 0.001)
        assertEquals(R.string.drive_detail_sample_mean, driveDetailSpeedAverageLabel(detail))
        assertEquals(R.string.drive_detail_sample_mean, driveDetailPowerAverageLabel(detail))
    }
    @Test fun genuineReportedAverageIncludingZeroRetainsItsAverageLabel() {
        val detail = DriveDetail(10, lengthUnit = "km", speedAvg = 0.0,
            positions = listOf(DrivePosition(speed = 99.0, power = 0.0)))
        assertEquals(0.0, requireNotNull(calculateDriveDetailStats(detail, unavailable).speedAvg), 0.0)
        assertEquals(R.string.average, driveDetailSpeedAverageLabel(detail))
        assertEquals(R.string.drive_detail_sample_mean, driveDetailPowerAverageLabel(detail))
    }
}
