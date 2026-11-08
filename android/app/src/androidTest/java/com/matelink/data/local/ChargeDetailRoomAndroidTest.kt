package com.matelink.data.local

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.matelink.data.api.models.ChargeData
import com.matelink.data.api.models.ChargeDetail
import com.matelink.data.sync.toSyncSummary
import com.matelink.domain.analytics.toAnalysisChargeData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Runs in the isolated instrumentation package, never against the owner's database. */
@RunWith(AndroidJUnit4::class)
class ChargeDetailRoomAndroidTest {
    @Test fun scopedDetailAndWeakPageSurviveCloseAndReopen() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "charge-detail-${UUID.randomUUID()}.db"
        fun open() = Room.databaseBuilder(context, StatsDatabase::class.java, name).build()
        var db = open()
        val start = "2026-10-20T10:00:00Z"
        val end = "2026-10-20T10:39:00Z"
        try {
            val list = ChargeData(64, startDate = start, endDate = end,
                source = "teslamate_archive", qualityState = "observed",
                chargeEnergyAdded = 24.0, durationMin = 39)
            val detail = ChargeDetail(64, startDate = start, endDate = end,
                source = "teslamate_archive", chargeEnergyAdded = 24.02, cost = 27.38)
            db.chargeSummaryDao().upsert(list.toSyncSummary(30)!!)
            assertTrue(db.chargeSummaryDao().mergeDetail(30, detail) {})
            assertFalse(db.chargeSummaryDao().mergeDetail(30, detail.copy(
                endDate = "2026-10-20T10:40:00Z")) {})
            try {
                db.chargeSummaryDao().mergeDetail(30, detail.copy(chargeEnergyAdded = 99.0)) {
                    throw CancellationException("scope changed")
                }
            } catch (_: CancellationException) { }
            db.chargeSummaryDao().upsertPreservingEvidence(listOf(
                list.copy(chargeEnergyAdded = null).toSyncSummary(30)!!))
            db.close()
            db = open()
            val restored = db.chargeSummaryDao().get(30, 64)!!.toAnalysisChargeData()
            assertEquals(24.02, restored.batteryInputKwh!!, 0.001)
            assertEquals(27.38, restored.cost!!, 0.001)
            assertEquals(1, db.chargeSummaryDao().count(30))
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }
}
