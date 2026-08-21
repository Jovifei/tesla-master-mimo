package com.matelink.domain.report

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompletedDriveDetectorTest {
    @Test
    fun firstRunCreatesWatermarkWithoutHistoricalReports() {
        val plan = CompletedDriveDetector.evaluate(
            carId = 1,
            currentCursor = null,
            candidates = listOf(candidate(7), candidate(9))
        )

        assertTrue(plan.initialized)
        assertEquals(9, plan.nextCursor)
        assertTrue(plan.newDrives.isEmpty())
    }

    @Test
    fun emptyFirstRunInitializesAtZero() {
        val plan = CompletedDriveDetector.evaluate(
            carId = 1,
            currentCursor = null,
            candidates = emptyList()
        )

        assertTrue(plan.initialized)
        assertEquals(0, plan.nextCursor)
        assertTrue(plan.newDrives.isEmpty())
    }

    @Test
    fun subsequentRunReturnsOnlyNewEligibleDrivesInOrder() {
        val plan = CompletedDriveDetector.evaluate(
            carId = 1,
            currentCursor = 8,
            candidates = listOf(candidate(10), candidate(7), candidate(9))
        )

        assertFalse(plan.initialized)
        assertEquals(listOf(9, 10), plan.newDrives.map { it.driveId })
        assertEquals(10, plan.nextCursor)
    }

    @Test
    fun invalidAndOtherCarCandidatesAreIgnored() {
        val candidates = listOf(
            candidate(4, carId = 2),
            candidate(5, distanceKm = 0.0),
            candidate(6, durationMinutes = 0),
            candidate(7, endDate = ""),
            candidate(8, distanceKm = Double.NaN)
        )

        val plan = CompletedDriveDetector.evaluate(
            carId = 1,
            currentCursor = 3,
            candidates = candidates
        )

        assertTrue(plan.newDrives.isEmpty())
        assertEquals(3, plan.nextCursor)
    }

    @Test
    fun duplicateDriveIdsAreDeliveredOnce() {
        val plan = CompletedDriveDetector.evaluate(
            carId = 1,
            currentCursor = 4,
            candidates = listOf(candidate(5), candidate(5), candidate(6))
        )

        assertEquals(listOf(5, 6), plan.newDrives.map { it.driveId })
    }

    @Test
    fun cursorNeverMovesBackward() {
        val plan = CompletedDriveDetector.evaluate(
            carId = 1,
            currentCursor = 20,
            candidates = listOf(candidate(3), candidate(4))
        )

        assertEquals(20, plan.nextCursor)
        assertTrue(plan.newDrives.isEmpty())
    }

    private fun candidate(
        driveId: Int,
        carId: Int = 1,
        endDate: String = "2026-08-20T12:00:00+08:00",
        durationMinutes: Int = 12,
        distanceKm: Double = 8.5
    ) = CompletedDriveCandidate(
        carId = carId,
        driveId = driveId,
        endDate = endDate,
        durationMinutes = durationMinutes,
        distanceKm = distanceKm
    )
}
