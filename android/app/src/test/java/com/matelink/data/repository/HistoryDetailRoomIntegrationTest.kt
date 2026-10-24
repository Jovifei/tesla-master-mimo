package com.matelink.data.repository

import android.app.Application
import androidx.room.Room
import com.matelink.data.api.models.ChargeDetail
import com.matelink.data.api.models.ChargeData
import com.matelink.data.api.models.ChargePoint
import com.matelink.data.api.models.ChargerDetails
import com.matelink.data.local.HistoryConnectionSource
import com.matelink.data.local.StatsDatabase
import com.matelink.data.local.VehicleContext
import com.matelink.data.local.cloudOriginVehicleStableIdentity
import com.matelink.data.local.entity.HistoryDetailReceiptState
import com.matelink.data.sync.toSyncSummary
import com.matelink.domain.analytics.toAnalysisChargeData
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Starts RED on v22: a real Room database still has nowhere to retain detail points. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
class HistoryDetailRoomIntegrationTest {
    @get:Rule val temp = TemporaryFolder()
    private val start = "2026-10-20T10:00:00Z"
    private val end = "2026-10-20T10:39:00Z"
    private val context = VehicleContext(7,
        cloudOriginVehicleStableIdentity("account-a", "https://api.example", "vehicle-a"),
        -71, HistoryConnectionSource.CLOUD, "cloud")

    private fun open() = Room.databaseBuilder(RuntimeEnvironment.getApplication(),
        StatsDatabase::class.java, File(temp.root, "detail.db").absolutePath)
        .allowMainThreadQueries().build()

    private fun identity(context: VehicleContext = this.context, instance: String = "archive-A",
        endDate: String = end) = HistoryDetailIdentity.create(context, HistoryDetailIdentity.CHARGE,
        64, "teslamate_archive", instance, start, endDate)

    private fun detail(energy: Double = 24.02) = ChargeDetail(64, startDate = start, endDate = end,
        source = "teslamate_archive", sourceInstanceId = "archive-A", chargeEnergyAdded = energy,
        chargePoints = listOf(
            ChargePoint(date = start, batteryLevel = 0, chargeEnergyAdded = 0.0,
                chargerDetails = ChargerDetails(chargerPower = 0.0)),
            ChargePoint(date = start, batteryLevel = null, chargeEnergyAdded = null,
                chargerDetails = ChargerDetails(chargerPower = -3.5)),
            ChargePoint(date = end, batteryLevel = 88, chargeEnergyAdded = energy)))

    @Test fun freshRoomHasDurableReceiptAndOrderedPointChunkTables() {
        val db = open()
        try {
            val names = mutableSetOf<String>()
            db.openHelper.writableDatabase.query(
                "SELECT name FROM sqlite_master WHERE type='table' AND name IN ('history_detail_receipts','history_detail_chunks')"
            ).use { cursor ->
                while (cursor.moveToNext()) names += cursor.getString(0)
            }
            assertEquals(setOf("history_detail_receipts", "history_detail_chunks"), names)
        } finally { db.close() }
    }

    @Test fun actualRoomReopensFullReceivedPointsAndRejectsOtherScopeInstanceOrWindow() = runBlocking {
        var db = open()
        try {
            val key = identity()
            val original = detail()
            val stored = HistoryDetailRepository(db.historyDetailDao(), HistoryDetailCodec(),
                HistoryDetailStorageLimits(maxPointsPerChunk = 2), System::currentTimeMillis)
                .saveCharge(key, original)
            assertTrue(stored is HistoryDetailWriteResult.Stored)
            val receipt = (stored as HistoryDetailWriteResult.Stored).receipt
            assertEquals(3, receipt.pointCount)
            assertTrue(receipt.responseComplete)
            assertFalse(receipt.providerTotalVerified)
            assertTrue(receipt.sourceInstanceVerified)
            assertEquals(HistoryDetailReceiptState.RESPONSE_COMPLETE_TOTAL_UNVERIFIED, receipt.state)
            assertEquals(listOf(2, 1), db.historyDetailDao().chunks(receipt.receiptId).map { it.pointCount })

            db.close()
            db = open()
            val reopened = HistoryDetailRepository(db.historyDetailDao())
            assertEquals(original, reopened.readCharge(key).detail)
            assertEquals(0.0, reopened.readCharge(key).detail!!.chargePoints!![0].chargeEnergyAdded!!, 0.0)
            assertEquals(-3.5, reopened.readCharge(key).detail!!.chargePoints!![1].chargerPowerValue!!, 0.0)
            assertNull(reopened.readCharge(identity(context.copy(stableIdentity =
                cloudOriginVehicleStableIdentity("account-b", "https://api.example", "vehicle-a")))).detail)
            assertNull(reopened.readCharge(identity(instance = "archive-B")).detail)
            assertNull(reopened.readCharge(identity(endDate = "2026-10-20T10:40:00Z")).detail)
            assertTrue(reopened.saveCharge(key, original.copy(sourceInstanceId = null)) is HistoryDetailWriteResult.Rejected)
            val sourceA = HistoryDetailIdentity.create(context, HistoryDetailIdentity.CHARGE,
                64, "teslamate_archive", "archive-A", start, end, sourceId = "record-A")
            assertTrue(reopened.saveCharge(sourceA, original.copy(sourceId = "record-B"))
                is HistoryDetailWriteResult.Rejected)
            assertNull(reopened.readCharge(sourceA).detail)
            assertEquals(original, reopened.readCharge(key).detail)

            reopened.recordFailure(key, "network_unavailable")
            val afterFailure = reopened.readCharge(key)
            assertEquals(original, afterFailure.detail)
            assertEquals("network_unavailable", afterFailure.reason)
            assertEquals(receipt.contentSha256, afterFailure.receipt!!.contentSha256)
        } finally { db.close() }
    }

    @Test fun partialAndInterruptedReplacementRetainPriorCompleteReceipt() = runBlocking {
        var db = open()
        try {
            val key = identity()
            val repo = HistoryDetailRepository(db.historyDetailDao(), HistoryDetailCodec(),
                HistoryDetailStorageLimits(maxPointsPerChunk = 2), System::currentTimeMillis)
            val original = detail()
            assertTrue(repo.saveCharge(key, original) is HistoryDetailWriteResult.Stored)
            val oldHash = repo.readCharge(key).receipt!!.contentSha256
            assertTrue(repo.saveCharge(key, detail(25.0), HistoryDetailResponseCoverage(
                responseComplete = false, nextCursor = "next-page")) is HistoryDetailWriteResult.Stored)
            assertEquals(HistoryDetailReceiptState.PARTIAL,
                db.historyDetailDao().latestAny(key.identityKey)!!.state)
            assertEquals(original, repo.readCharge(key).detail)
            assertEquals("detail_partial_newer", repo.readCharge(key).reason)

            var fences = 0
            try {
                repo.saveCharge(key, detail(26.0), ensureCurrent = {
                    fences++
                    if (fences == 3) throw CancellationException("identity changed after chunk write")
                })
                org.junit.Assert.fail("cancelled transaction must roll back")
            } catch (_: CancellationException) { }
            assertEquals(3, fences)
            assertEquals(oldHash, repo.readCharge(key).receipt!!.contentSha256)
            assertEquals(HistoryDetailReceiptState.PARTIAL,
                db.historyDetailDao().latestAny(key.identityKey)!!.state)

            db.close()
            db = open()
            assertEquals(original, HistoryDetailRepository(db.historyDetailDao()).readCharge(key).detail)
            assertEquals(oldHash, HistoryDetailRepository(db.historyDetailDao()).readCharge(key).receipt!!.contentSha256)
        } finally { db.close() }
    }

    @Test fun missingSourceAndStorageLimitFailClosedWithoutDroppingOldPoints() = runBlocking {
        val db = open()
        try {
            assertThrows(IllegalArgumentException::class.java) {
                HistoryDetailIdentity.create(context, HistoryDetailIdentity.CHARGE, 64,
                    null, null, start, end)
            }
            val zeroDuration = HistoryDetailIdentity.create(context, HistoryDetailIdentity.CHARGE,
                64, "teslamate_archive", null, start, start)
            assertEquals(start, zeroDuration.endInstant)
            val key = identity()
            val normal = HistoryDetailRepository(db.historyDetailDao())
            assertTrue(normal.saveCharge(key, detail()) is HistoryDetailWriteResult.Stored)
            val restricted = HistoryDetailRepository(db.historyDetailDao(), HistoryDetailCodec(),
                HistoryDetailStorageLimits(maxTotalBytes = 1), System::currentTimeMillis)
            assertEquals(HistoryDetailWriteResult.Rejected("total_storage_limit"),
                restricted.saveCharge(key, detail(25.0)))
            assertEquals(detail(), normal.readCharge(key).detail)
            assertEquals("total_storage_limit", normal.readCharge(key).reason)
        } finally { db.close() }
    }

    @Test fun nestedRoomTransactionRollsBackSummaryWhenPointBudgetRejects() = runBlocking {
        var db = open()
        try {
            val list = ChargeData(64, startDate = start, endDate = end,
                source = "teslamate_archive", sourceInstanceId = "archive-A",
                sourceId = "record-A", chargeEnergyAdded = 24.0)
            db.chargeSummaryDao().upsert(list.toSyncSummary(context.localHistoryCarId)!!)
            val identity = db.chargeSummaryDao().get(context.localHistoryCarId, 64)!!
                .verifiedDetailIdentity(context)!!
            val prior = db.chargeSummaryDao().get(context.localHistoryCarId, 64)!!.apiEvidence
            val detail = detail().copy(sourceId = "record-A")
            val restricted = HistoryDetailRepository(db.historyDetailDao(), HistoryDetailCodec(),
                HistoryDetailStorageLimits(maxReceiptBytes = 1), System::currentTimeMillis)
            try {
                persistVerifiedChargeDetail(db, restricted, context, detail) {}
                org.junit.Assert.fail("point limit must roll back the summary merge")
            } catch (e: HistoryDetailPointSaveRejected) {
                assertEquals("storage_limit", e.reason)
            }
            assertEquals(prior, db.chargeSummaryDao().get(context.localHistoryCarId, 64)!!.apiEvidence)
            assertEquals(24.0, db.chargeSummaryDao().get(context.localHistoryCarId, 64)!!
                .toAnalysisChargeData().batteryInputKwh!!, 0.0)
            assertNull(restricted.readCharge(identity).detail)
            assertEquals("storage_limit", restricted.readCharge(identity).reason)

            val normal = HistoryDetailRepository(db.historyDetailDao())
            assertTrue(persistVerifiedChargeDetail(db, normal, context, detail) {} != null)
            assertEquals(24.02, db.chargeSummaryDao().get(context.localHistoryCarId, 64)!!
                .toAnalysisChargeData().batteryInputKwh!!, 0.0)
            db.close()
            db = open()
            assertEquals(detail.chargePoints, HistoryDetailRepository(db.historyDetailDao())
                .readCharge(identity).detail?.chargePoints)
        } finally { db.close() }
    }

    @Test fun validSessionEnergyWithoutApiPointsSurvivesRoomCloseAndKeepsPointReason() = runBlocking {
        var db = open()
        try {
            val row = ChargeData(64, startDate = start, endDate = end,
                source = "teslamate_archive", sourceInstanceId = "archive-A",
                sourceId = "record-A", chargeEnergyAdded = null)
            db.chargeSummaryDao().upsert(row.toSyncSummary(context.localHistoryCarId)!!)
            val noPoints = detail(24.02).copy(sourceId = "record-A", chargePoints = null)
            val saved = persistVerifiedChargeDetail(db,
                HistoryDetailRepository(db.historyDetailDao()), context, noPoints) {}
            assertTrue(saved != null)
            assertNull(saved!!.receipt)
            assertEquals(24.02, db.chargeSummaryDao().get(context.localHistoryCarId, 64)!!
                .toAnalysisChargeData().batteryInputKwh!!, 0.0)
            db.close(); db = open()
            val restored = db.chargeSummaryDao().get(context.localHistoryCarId, 64)!!
            assertEquals(24.02, restored.toAnalysisChargeData().batteryInputKwh!!, 0.0)
            val result = HistoryDetailRepository(db.historyDetailDao()).readCharge(
                restored.verifiedDetailIdentity(context)!!)
            assertNull(result.detail)
            assertEquals("charge_points_not_provided", result.reason)
        } finally { db.close() }
    }

    @Test fun corruptedNewestCompleteFallsBackToPriorVerifiedChunkOnOfflineRead() = runBlocking {
        var db = open()
        try {
            val key = identity()
            val first = detail(24.02)
            val second = detail(25.0)
            val saved = HistoryDetailRepository(db.historyDetailDao())
            val old = saved.saveCharge(key, first) as HistoryDetailWriteResult.Stored
            val fresh = saved.saveCharge(key, second) as HistoryDetailWriteResult.Stored
            assertFalse(old.receipt.receiptId == fresh.receipt.receiptId)
            db.openHelper.writableDatabase.execSQL(
                "UPDATE history_detail_chunks SET payload=X'00' WHERE receiptId=? AND chunkIndex=0",
                arrayOf(fresh.receipt.receiptId))
            db.close(); db = open()
            val offline = HistoryDetailRepository(db.historyDetailDao()).readCharge(key)
            assertEquals(first, offline.detail)
            assertEquals(old.receipt.receiptId, offline.receipt!!.receiptId)
            assertEquals("detail_recovered_older_revision", offline.reason)
        } finally { db.close() }
    }

    @Test fun sameResponseRedownloadRepairsDamagedChunkWithoutDeletingItsOldReceipt() = runBlocking {
        val db = open()
        try {
            val key = identity()
            val value = detail()
            val repo = HistoryDetailRepository(db.historyDetailDao())
            val first = repo.saveCharge(key, value) as HistoryDetailWriteResult.Stored
            db.openHelper.writableDatabase.execSQL(
                "UPDATE history_detail_chunks SET payload = X'00' WHERE receiptId = ? AND chunkIndex = 0",
                arrayOf(first.receipt.receiptId))
            assertEquals("detail_cache_corrupt", repo.readCharge(key).reason)
            val repaired = repo.saveCharge(key, value) as HistoryDetailWriteResult.Stored
            assertFalse(first.receipt.receiptId == repaired.receipt.receiptId)
            assertEquals(value, repo.readCharge(key).detail)
            assertEquals(repaired.receipt.receiptId,
                (repo.saveCharge(key, value) as HistoryDetailWriteResult.Stored).receipt.receiptId)
            db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM history_detail_receipts").use {
                assertTrue(it.moveToFirst()); assertEquals(2, it.getInt(0))
            }
        } finally { db.close() }
    }
}
