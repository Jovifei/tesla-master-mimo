package com.matelink.ui.screens.drives

import com.matelink.data.api.models.DriveData
import com.matelink.data.api.models.DriveOdometerDetails
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DrivesSummaryEvidenceTest {

    @Test
    fun missingMaximumSpeedDoesNotBecomeZero() {
        val summary = calculateDrivesSummary(
            listOf(
                DriveData(
                    driveId = 1,
                    durationMin = 12,
                    odometerDetails = DriveOdometerDetails(distance = 8.5)
                )
            )
        )

        assertEquals(1, summary.totalDrives)
        assertEquals(8.5, summary.totalDistanceKm, 0.0001)
        assertNull(summary.maxSpeedKmh)
    }

    @Test
    fun emptySummaryKeepsMetricsUnavailable() {
        val summary = calculateDrivesSummary(emptyList())

        assertEquals(0, summary.totalDrives)
        assertNull(summary.maxSpeedKmh)
    }
}
