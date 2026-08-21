package com.matelink.domain.report

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompletedDriveDetectorTest {
    @Test
    fun firstRunCreatesWatermarkWithoutHistoricalReports() {
        val plan = CompletedDriveDetector.evaluate(1, null, listOf(candidate(7), candidate(9)))
        assertTrue(plan.initialized)
        assertEquals(9, plan.nextCursor)
        assertTrue(plan.newDrives.isEmpty())
    }

    @Test
    fun emptyFirstRunInitializesAtZero() {
        val plan = CompletedDriveDetector.evaluate(1, null, emptyList())
        assertTrue(plan.initialized)
        assertEquals(0, plan.nextCursor)
        assertTrue(plan.newDrives.isEmpty())
    }

    @Test
    fun subsequentRunReturnsOnlyNewEligibleDrivesInOrder() {
        val plan = CompletedDriveDetector.evaluate(
            1, 8, listOf(candidate(10), candidate(7), candidate(9))
        )
        assertFalse(plan.initialized)
        assertEquals(listOf(9, 10), plan.newDrives.map { it.driveId })
        assertEquals(10, plan.nextCursor)
    }

    @Test
    fun activationCutoffSuppressesLateAppearingHistoryButAdvancesCursor() {
        val plan = CompletedDriveDetector.evaluate(
            carId = 1,
            currentCursor = 0,
            candidates = listOf(
                candidate(8, endedAtEpochMillis = 900),
                candidate(9, endedAtEpochMillis = 1_100)
            ),
            minimumEndEpochMillis = 1_000
        )
        assertEquals(listOf(9), plan.newDrives.map { it.driveId })
        assertEquals(9, plan.nextCursor)
    }

    @Test
    fun unparseableEndTimeIsNotDeliveredWhenActivationCutoffApplies() {
        val plan = CompletedDriveDetector.evaluate(
            carId = 1,
            currentCursor = 0,
            candidates = listOf(candidate(5, endedAtEpochMillis = null)),
            minimumEndEpochMillis = 1_000
        )
        assertTrue(plan.newDrives.isEmpty())
        assertEquals(5, plan.nextCursor)
    }

    @Test
    fun invalidAndOtherCarCandidatesAreIgnored() {
        val plan = CompletedDriveDetector.evaluate(
            1,
            3,
            listOf(
                candidate(4, carId = 2),
                candidate(5, distanceKm = 0.0),
                candidate(6, durationMinutes = 0),
                candidate(7, endDate = ""),
                candidate(8, distanceKm = Double.NaN)
            )
        )
        assertTrue(plan.newDrives.isEmpty())
        assertEquals(3, plan.nextCursor)
    }

    @Test
    fun duplicateDriveIdsAreDeliveredOnce() {
        val plan = CompletedDriveDetector.evaluate(
            1, 4, listOf(candidate(5), candidate(5), candidate(6))
        )
        assertEquals(listOf(5, 6), plan.newDrives.map { it.driveId })
    }

    @Test
    fun cursorNeverMovesBackward() {
        val plan = CompletedDriveDetector.evaluate(1, 20, listOf(candidate(3), candidate(4)))
        assertEquals(20, plan.nextCursor)
        assertTrue(plan.newDrives.isEmpty())
    }

    private fun candidate(
        driveId: Int,
        carId: Int = 1,
        endDate: String = "2026-08-20T12:00:00+08:00",
        endedAtEpochMillis: Long? = 2_000,
        durationMinutes: Int = 12,
        distanceKm: Double = 8.5
    ) = CompletedDriveCandidate(
        carId = carId,
        driveId = driveId,
        endDate = endDate,
        endedAtEpochMillis = endedAtEpochMillis,
        durationMinutes = durationMinutes,
        distanceKm = distanceKm
    )
}
