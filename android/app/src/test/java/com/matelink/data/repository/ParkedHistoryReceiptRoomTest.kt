package com.matelink.data.repository

import android.app.Application
import androidx.room.Room
import com.matelink.data.api.models.LinkedCharge
import com.matelink.data.api.models.ParkedDetailData
import com.matelink.data.local.HistoryConnectionSource
import com.matelink.data.local.StatsDatabase
import com.matelink.data.local.VehicleContext
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application=Application::class, sdk=[28], manifest=Config.NONE)
class ParkedHistoryReceiptRoomTest {
    @get:Rule val temp = TemporaryFolder()
    private fun open() = Room.databaseBuilder(RuntimeEnvironment.getApplication(), StatsDatabase::class.java,
        File(temp.root, "parked-receipts.db").absolutePath).allowMainThreadQueries().build()
    private fun binding() = ParkedReceiptBinding(VehicleContext(7,"synthetic-stable-A",-71,
        HistoryConnectionSource.SELF_HOSTED,"https://synthetic.example"),10,11,"teslamate_archive","archive-A",
        "2026-10-27T00:10:00Z","2026-10-27T02:00:00Z","synthetic-older-receipt-A","synthetic-newer-receipt-A")
    private fun received() = ParkedDetailData(10,11,"2026-10-27T00:10:00Z","2026-10-27T02:00:00Z",
        source="database_latest", carId=7, outsideTempAverage=0.0, averagePowerKw=0.0,
        linkedCharges=listOf(LinkedCharge(51,startDate="2026-10-27T00:20:00Z",endDate="2026-10-27T00:30:00Z",energyAddedKwh=0.0)),
        chargeSessionsComplete=true)

    @Test fun headerReceiptRetainsZeroSessionsAndUnverifiedInstanceAcrossRealRoomReopen() = runBlocking<Unit> {
        var db = open()
        try {
            val key = binding()
            assertTrue(ParkedHistoryReceiptStore(db.historyDetailDao()).save(key,received()))
            val receipt = db.historyDetailDao().latestCompleteCandidates(key.identityKey,5).single()
            assertEquals("parked", receipt.kind)
            assertFalse(receipt.sourceInstanceVerified)
            assertFalse(receipt.providerTotalVerified)
            assertEquals(0, receipt.pointCount)
            assertTrue(db.historyDetailDao().chunks(receipt.receiptId).isEmpty())
            db.close(); db = open()
            assertEquals(received(), ParkedHistoryReceiptStore(db.historyDetailDao()).read(key))
        } finally { db.close() }
    }

    @Test fun foreignScopeSourceInstanceCarWindowOrChangedLocalReceiptCannotBorrowTheHeader() = runBlocking<Unit> {
        val db = open()
        try {
            val store = ParkedHistoryReceiptStore(db.historyDetailDao())
            val key = binding()
            assertTrue(store.save(key, received()))
            for (invalid in listOf(received().copy(carId=8), received().copy(sourceInstanceId="foreign"),
                received().copy(source="telemetry_mqtt"), received().copy(startDate="2026-10-26T00:10:00Z")))
                assertFalse(store.save(key,invalid))
            assertNull(store.read(key.copy(context=key.context.copy(localHistoryCarId=-72,stableIdentity="synthetic-stable-B"))))
            assertNull(store.read(key.copy(olderReceipt="corrected-local-receipt")))
            assertEquals(received(),store.read(key))
        } finally { db.close() }
    }

    @Test fun corruptNewestHeaderFallsBackAndFailedInsertOrBudgetCannotEraseEarlierReceipt() = runBlocking<Unit> {
        val db = open()
        try {
            val store = ParkedHistoryReceiptStore(db.historyDetailDao())
            val key = binding()
            assertTrue(store.save(key,received()))
            assertTrue(store.save(key,received().copy(address="new revision")))
            val newest = db.historyDetailDao().latestCompleteCandidates(key.identityKey,5).first()
            db.openHelper.writableDatabase.execSQL("UPDATE history_detail_receipts SET headerJson = '{}' WHERE receiptId = ?",arrayOf(newest.receiptId))
            assertEquals(received(),store.read(key))
            val tiny = ParkedHistoryReceiptStore(db.historyDetailDao(),HistoryDetailStorageLimits(maxTotalBytes=1))
            assertFalse(tiny.save(key,received().copy(address="over budget")))
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER synthetic_parked_reject BEFORE INSERT ON history_detail_receipts BEGIN SELECT RAISE(ABORT,'synthetic insert failure'); END")
            assertTrue(runCatching { store.save(key,received().copy(address="write fails")) }.isFailure)
            assertEquals(received(),store.read(key))
        } finally { db.close() }
    }

    @Test fun revokedPublicationBeforeInsertCannotCreateANewHeader() = runBlocking<Unit> {
        val db = open()
        try {
            val store = ParkedHistoryReceiptStore(db.historyDetailDao())
            var checks = 0
            val result = runCatching { store.save(binding(),received()) {
                if (++checks == 2) throw CancellationException("synthetic scope changed")
            } }
            assertTrue(result.exceptionOrNull() is CancellationException)
            assertNull(store.read(binding()))
        } finally { db.close() }
    }
}
