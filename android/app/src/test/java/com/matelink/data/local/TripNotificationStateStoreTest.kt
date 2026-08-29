package com.matelink.data.local

import com.matelink.data.local.entity.DriveSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TripNotificationStateStoreTest {
    @Test
    fun firstCompletedDriveSyncSetsWatermarkWithoutNotifications() {
        val plan = completedTripNotificationPlan(
            watermark = null,
            summaries = listOf(drive(12), drive(8))
        )

        assertTrue(plan.toNotify.isEmpty())
        assertEquals(12, plan.watermarkToSave)
    }

    @Test
    fun laterSyncOnlyNotifiesDriveIdsAboveTheWatermarkInOrder() {
        val plan = completedTripNotificationPlan(
            watermark = 10,
            summaries = listOf(drive(12), drive(10), drive(11), drive(11))
        )

        assertEquals(listOf(11, 12), plan.toNotify.map { it.driveId })
        assertEquals(12, plan.watermarkToSave)
    }

    @Test
    fun noCompletedDriveDoesNotCreateAFirstSyncWatermark() {
        val plan = completedTripNotificationPlan(watermark = null, summaries = emptyList())

        assertTrue(plan.toNotify.isEmpty())
        assertEquals(null, plan.watermarkToSave)
    }

    @Test
    fun notificationFailureDoesNotAdvanceTheExistingWatermark() = kotlinx.coroutines.runBlocking {
        val watermarks = FakeWatermarks(10)
        val notifier = FakeNotifier(result = false)

        CompletedTripNotificationProcessor(watermarks, notifier).process(
            carId = 7,
            summaries = listOf(drive(11))
        )

        assertEquals(10, watermarks.watermark)
        assertEquals(listOf(11), notifier.shownDriveIds)
    }

    @Test
    fun multiplePagesCanBeProcessedAsOneCompletedSyncAndNotifyOnlyLaterDrives() = kotlinx.coroutines.runBlocking {
        val watermarks = FakeWatermarks(null)
        val notifier = FakeNotifier(result = true)
        val processor = CompletedTripNotificationProcessor(watermarks, notifier)

        processor.process(carId = 7, summaries = listOf(drive(12), drive(8), drive(12)))
        processor.process(carId = 7, summaries = listOf(drive(14), drive(13), drive(12)))

        assertEquals(listOf(13, 14), notifier.shownDriveIds)
        assertEquals(14, watermarks.watermark)
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

    private class FakeWatermarks(initial: Int?) : TripNotificationWatermarks {
        var watermark = initial
        override suspend fun getWatermark(carId: Int): Int? = watermark
        override suspend fun advanceWatermark(carId: Int, driveId: Int) {
            if (watermark == null || driveId > watermark!!) watermark = driveId
        }
    }

    private class FakeNotifier(private val result: Boolean) : CompletedTripNotifier {
        val shownDriveIds = mutableListOf<Int>()
        override fun showCompletedDrive(carId: Int, summary: DriveSummary): Boolean {
            shownDriveIds += summary.driveId
            return result
        }
    }
}
