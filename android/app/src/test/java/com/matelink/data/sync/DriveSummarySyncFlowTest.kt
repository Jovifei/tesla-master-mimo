package com.matelink.data.sync

import com.matelink.data.local.CompletedTripNotificationProcessor
import com.matelink.data.local.CompletedTripNotifier
import com.matelink.data.local.TripNotificationWatermarks
import com.matelink.data.local.entity.DriveSummary
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DriveSummarySyncFlowTest {
    @Test
    fun onlyCompletedMultiPageSyncEstablishesTheSilentInitialWatermark() = runBlocking {
        val firstPage = (51..100).map(::drive)
        val watermarks = FakeWatermarks()
        val notifier = FakeNotifier()
        val requestedPages = mutableListOf<Int>()
        val persistedSizes = mutableListOf<Int>()
        val processor = CompletedTripNotificationProcessor(watermarks, notifier)
        val runner = DriveSummarySyncRunner(
            fetchPage = { page ->
                requestedPages += page
                when (page) {
                    1 -> DriveSummaryPageResult.Success(firstPage.map { it.driveId }, firstPage)
                    2 -> DriveSummaryPageResult.Success(listOf(50), listOf(drive(50)))
                    else -> error("Unexpected page $page")
                }
            },
            persistPage = { persistedSizes += it.size },
            onCompleted = { summaries -> processor.process(7, summaries) }
        )

        assertTrue(runner.sync())

        assertEquals(listOf(1, 2), requestedPages)
        assertEquals(listOf(50, 1), persistedSizes)
        assertEquals(100, watermarks.watermark)
        assertTrue(notifier.shownDriveIds.isEmpty())
    }

    @Test
    fun failedPageDoesNotCallTheCompletedSyncCallback() = runBlocking {
        val firstPage = (51..100).map(::drive)
        var completed = false
        val runner = DriveSummarySyncRunner(
            fetchPage = { page ->
                if (page == 1) {
                    DriveSummaryPageResult.Success(firstPage.map { it.driveId }, firstPage)
                } else {
                    DriveSummaryPageResult.Failure
                }
            },
            persistPage = { _ -> },
            onCompleted = { completed = true }
        )

        assertFalse(runner.sync())
        assertFalse(completed)
    }

    private fun drive(id: Int) = DriveSummary(
        driveId = id,
        carId = 7,
        startDate = "2026-08-29T08:00:00Z",
        endDate = "2026-08-29T08:30:00Z",
        durationMin = 30,
        startAddress = "Start",
        endAddress = "End",
        distance = 20.5,
        speedMax = 90,
        speedAvg = 41,
        powerMax = 100,
        powerMin = -20,
        startBatteryLevel = 80,
        endBatteryLevel = 75,
        outsideTempAvg = null,
        insideTempAvg = null,
        energyConsumed = null,
        efficiency = null
    )

    private class FakeWatermarks : TripNotificationWatermarks {
        var watermark: Int? = null
        override suspend fun getWatermark(carId: Int): Int? = watermark
        override suspend fun advanceWatermark(carId: Int, driveId: Int) {
            if (watermark == null || driveId > watermark!!) watermark = driveId
        }
    }

    private class FakeNotifier : CompletedTripNotifier {
        val shownDriveIds = mutableListOf<Int>()
        override fun showCompletedDrive(carId: Int, summary: DriveSummary): Boolean {
            shownDriveIds += summary.driveId
            return true
        }
    }
}
