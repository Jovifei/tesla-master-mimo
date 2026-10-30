package com.matelink.data.repository

import android.app.Application
import android.util.Log
import androidx.room.Room
import androidx.room.RoomDatabase
import com.matelink.data.api.models.DriveData
import com.matelink.data.api.models.DriveDetail
import com.matelink.data.api.models.DrivePosition
import com.matelink.data.local.HistoryConnectionSource
import com.matelink.data.local.StatsDatabase
import com.matelink.data.local.VehicleContext
import com.matelink.data.local.cloudOriginVehicleStableIdentity
import com.matelink.data.sync.toSyncSummary
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
import org.robolectric.shadows.ShadowLog
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
class HistoryDetailPersistenceDiagnosticTest {
    @get:Rule val temp = TemporaryFolder()
    private val start = "2026-10-30T12:41:30.123456Z"
    private val end = "2026-10-30T12:49:46.654321Z"
    private val context = VehicleContext(7,
        cloudOriginVehicleStableIdentity("PRIVATE_ACCOUNT_SENTINEL", "https://private.example", "PRIVATE_CAR_SENTINEL"),
        -71, HistoryConnectionSource.CLOUD, "PRIVATE_SERVER_SENTINEL")
    private fun raw() = DriveData(41, startDate = start, endDate = end, source = "teslamate_archive",
        sourceInstanceId = "PRIVATE_INSTANCE_SENTINEL", sourceRecordId = "PRIVATE_RECORD_SENTINEL",
        startAddress = "PRIVATE_ADDRESS_SENTINEL", qualityState = "observed")
    private fun detail() = DriveDetail(41, startDate = start, endDate = end, source = "teslamate_archive",
        sourceInstanceId = "PRIVATE_INSTANCE_SENTINEL", sourceRecordId = "PRIVATE_RECORD_SENTINEL")
    private val foregroundDiagnostic: (String) -> Unit = { Log.i("HistoryDetailDiag", it) }
    private fun open(queries: MutableList<Pair<String, List<Any?>>>? = null): StatsDatabase {
        val builder = Room.databaseBuilder(RuntimeEnvironment.getApplication(), StatsDatabase::class.java,
            File(temp.root, "detail.db").absolutePath).allowMainThreadQueries()
        if (queries != null) builder.setQueryCallback(object : RoomDatabase.QueryCallback {
            override fun onQuery(sqlQuery: String, bindArgs: List<Any?>) {
                queries += sqlQuery to bindArgs.toList()
            }
        }, Executor { it.run() })
        return builder.build()
    }
    private fun line(): String {
        val logs = ShadowLog.getLogsForTag("HistoryDetailDiag")
        assertEquals("one fixed diagnostic per rejected detail", 1, logs.size)
        assertTrue(logs.single().msg.contains("DETAIL_HAS_SAMPLES="))
        assertTrue(logs.single().msg.contains("DETAIL_HAS_MULTIPLE_SAMPLES="))
        return logs.single().msg
    }
    private fun assertPrivateSafe(value: String) {
        listOf("PRIVATE_", "teslamate_archive", start, end, "private.example", "detail.db").forEach {
            assertFalse("private value in diagnostic", value.contains(it))
        }
    }

    @Test fun missingSummaryReportsFixedStageAndStillReturnsNull() = runBlocking {
        val db = open()
        try {
            ShadowLog.clear()
            assertNull(persistVerifiedDriveDetail(db, HistoryDetailRepository(db.historyDetailDao()), context, detail(),
                onDiagnostic = foregroundDiagnostic) {})
            assertTrue(line().startsWith("stage=SUMMARY_MISSING "))
            assertTrue(line().contains("DETAIL_HAS_SAMPLES=false"))
            assertTrue(line().contains("DETAIL_HAS_MULTIPLE_SAMPLES=false"))
        } finally { db.close() }
    }

    @Test fun backgroundDefaultIsSilentAndDoesNotReadUnfilteredSummary() = runBlocking {
        val queries = CopyOnWriteArrayList<Pair<String, List<Any?>>>()
        val db = open(queries)
        try {
            ShadowLog.clear()
            assertNull(persistVerifiedDriveDetail(db, HistoryDetailRepository(db.historyDetailDao()), context, detail()) {})
            assertTrue(ShadowLog.getLogsForTag("HistoryDetailDiag").isEmpty())
            assertFalse(queries.any { it.first.contains("WHERE carId = ? AND driveId = ? LIMIT 1") })
        } finally { db.close() }
    }

    @Test fun undecodableEvidenceReportsBooleansWithoutChangingStoredRow() = runBlocking {
        val db = open()
        try {
            val row = raw().toSyncSummary(context.localHistoryCarId)!!.copy(apiEvidence = "PRIVATE_INVALID_JSON")
            db.driveSummaryDao().upsert(row)
            ShadowLog.clear()
            assertNull(persistVerifiedDriveDetail(db, HistoryDetailRepository(db.historyDetailDao()), context, detail(),
                onDiagnostic = foregroundDiagnostic) {})
            val log = line()
            assertTrue(log.contains("stage=SUMMARY_IDENTITY_REJECTED"))
            assertTrue(log.contains("RAW_EVIDENCE_DECODABLE=false"))
            assertTrue(log.contains("RAW_SOURCE_PRESENT=false"))
            assertPrivateSafe(log)
            assertEquals(row, db.driveSummaryDao().get(context.localHistoryCarId, 41))
        } finally { db.close() }
    }

    @Test fun rawRowInstantMismatchRemainsRejectedAndDoesNotPrintTimes() = runBlocking {
        val db = open()
        try {
            val row = raw().toSyncSummary(context.localHistoryCarId)!!.copy(startDate = "2026-10-30T12:41:30Z")
            db.driveSummaryDao().upsert(row)
            ShadowLog.clear()
            assertNull(persistVerifiedDriveDetail(db, HistoryDetailRepository(db.historyDetailDao()), context, detail(),
                onDiagnostic = foregroundDiagnostic) {})
            assertTrue(line().contains("RAW_ROW_START_MATCHES=false"))
            assertTrue(line().contains("RAW_ROW_END_MATCHES=true"))
            assertPrivateSafe(line())
            assertEquals(row, db.driveSummaryDao().get(context.localHistoryCarId, 41))
        } finally { db.close() }
    }

    @Test fun detailSourceMismatchReportsOnlyFixedBooleanChecks() = runBlocking {
        val db = open()
        try {
            val row = raw().toSyncSummary(context.localHistoryCarId)!!
            db.driveSummaryDao().upsert(row)
            ShadowLog.clear()
            assertNull(persistVerifiedDriveDetail(db, HistoryDetailRepository(db.historyDetailDao()), context,
                detail().copy(source = "PRIVATE_FOREIGN_SOURCE"), onDiagnostic = foregroundDiagnostic) {})
            assertTrue(line().contains("stage=DETAIL_IDENTITY_REJECTED"))
            assertTrue(line().contains("DETAIL_SOURCE_MATCHES=false"))
            assertTrue(line().contains("DETAIL_START_MATCHES=true"))
            assertPrivateSafe(line())
            assertEquals(row, db.driveSummaryDao().get(context.localHistoryCarId, 41))
        } finally { db.close() }
    }

    @Test fun mergeMissingKeepsExistingNullResult() = runBlocking {
        val db = open()
        try {
            db.driveSummaryDao().upsert(raw().toSyncSummary(context.localHistoryCarId)!!)
            var checks = 0
            ShadowLog.clear()
            assertNull(persistVerifiedDriveDetail(db, HistoryDetailRepository(db.historyDetailDao()), context, detail(),
                onDiagnostic = foregroundDiagnostic) {
                if (++checks == 2) db.driveSummaryDao().deleteDriveById(context.localHistoryCarId, 41)
            })
            assertTrue(line().startsWith("stage=SUMMARY_MERGE_UNAVAILABLE "))
        } finally { db.close() }
    }

    @Test fun cancellationStillPropagatesBeforeAnyDiagnostic() = runBlocking {
        val db = open()
        try {
            ShadowLog.clear()
            try {
                persistVerifiedDriveDetail(db, HistoryDetailRepository(db.historyDetailDao()), context, detail(),
                    onDiagnostic = foregroundDiagnostic) {
                    throw CancellationException("PRIVATE_CANCELLATION_SENTINEL")
                }
                fail("cancellation must propagate")
            } catch (_: CancellationException) { }
            assertTrue(ShadowLog.getLogsForTag("HistoryDetailDiag").isEmpty())
        } finally { db.close() }
    }

    @Test fun rawSourceRecordConflictIsReportedWithoutItsValues() = runBlocking {
        val db = open()
        try {
            val row = raw().copy(sourceId = "PRIVATE_OLD_RECORD", sourceRecordId = "PRIVATE_NEW_RECORD")
                .toSyncSummary(context.localHistoryCarId)!!
            db.driveSummaryDao().upsert(row)
            ShadowLog.clear()
            assertNull(persistVerifiedDriveDetail(db, HistoryDetailRepository(db.historyDetailDao()), context, detail(),
                onDiagnostic = foregroundDiagnostic) {})
            assertTrue(line().contains("RAW_SOURCE_ID_CONFLICT=true"))
            assertPrivateSafe(line())
            assertEquals(row, db.driveSummaryDao().get(context.localHistoryCarId, 41))
        } finally { db.close() }
    }

    @Test fun detailMismatchMatrixKeepsExactInstanceRecordAndInstantContract() = runBlocking {
        val db = open()
        try {
            val row = raw().toSyncSummary(context.localHistoryCarId)!!
            db.driveSummaryDao().upsert(row)
            listOf(
                detail().copy(sourceInstanceId = "PRIVATE_OTHER_INSTANCE") to "DETAIL_INSTANCE_MATCHES=false",
                detail().copy(sourceRecordId = "PRIVATE_OTHER_RECORD") to "DETAIL_RECORD_MATCHES=false",
                detail().copy(sourceId = "PRIVATE_CONFLICTING_ALIAS") to "DETAIL_RECORD_CONFLICT=true",
                detail().copy(startDate = "2026-10-30T12:41:30Z") to "DETAIL_START_MATCHES=false",
                detail().copy(endDate = "2026-10-30T12:49:46Z") to "DETAIL_END_MATCHES=false",
                detail().copy(startDate = "PRIVATE_INVALID_DATE") to "DETAIL_START_VALID=false",
                detail().copy(endDate = "PRIVATE_INVALID_DATE") to "DETAIL_END_VALID=false"
            ).forEach { (incoming, expected) ->
                ShadowLog.clear()
                assertNull(persistVerifiedDriveDetail(db, HistoryDetailRepository(db.historyDetailDao()), context, incoming,
                    onDiagnostic = foregroundDiagnostic) {})
                assertTrue(line().contains(expected))
                assertPrivateSafe(line())
                assertEquals(row, db.driveSummaryDao().get(context.localHistoryCarId, 41))
            }
        } finally { db.close() }
    }

    @Test fun diagnosticFailuresAreNonFatalButCancellationStillPropagates() {
        emitHistoryDetailDiag(write = { _, _ -> throw IllegalStateException("PRIVATE_LOG_FAILURE") }) {
            HistoryDetailDiag(HistoryDetailDiagStage.SUMMARY_UNAVAILABLE)
        }
        try {
            emitHistoryDetailDiag(write = { _, _ -> throw CancellationException("PRIVATE_CANCEL") }) {
                HistoryDetailDiag(HistoryDetailDiagStage.SUMMARY_UNAVAILABLE)
            }
            fail("diagnostic cancellation must propagate")
        } catch (_: CancellationException) { }
    }

    @Test fun matchingDetailStillPersistsWithoutRejectionDiagnostic() = runBlocking {
        val db = open()
        try {
            db.driveSummaryDao().upsert(raw().toSyncSummary(context.localHistoryCarId)!!)
            ShadowLog.clear()
            val saved = persistVerifiedDriveDetail(db, HistoryDetailRepository(db.historyDetailDao()), context, detail(),
                onDiagnostic = foregroundDiagnostic) {}
            assertNotNull(saved)
            assertTrue(ShadowLog.getLogsForTag("HistoryDetailDiag").isEmpty())
        } finally { db.close() }
    }

    @Test fun foregroundMissingOrQuarantinedLookupUsesOnlySameKeyAndDoesNotWrite() = runBlocking {
        for (quarantined in listOf(false, true)) {
            val queries = CopyOnWriteArrayList<Pair<String, List<Any?>>>()
            val db = open(queries)
            try {
                val row = raw().toSyncSummary(context.localHistoryCarId)!!.copy(qualityState = "quarantined")
                if (quarantined) {
                    db.driveSummaryDao().upsert(row)
                    db.driveSummaryDao().upsert(row.copy(carId = 9999, qualityState = "observed"))
                }
                ShadowLog.clear()
                assertNull(persistVerifiedDriveDetail(db, HistoryDetailRepository(db.historyDetailDao()), context, detail(),
                    onDiagnostic = foregroundDiagnostic) {})
                assertTrue(line().startsWith(if (quarantined) "stage=SUMMARY_QUARANTINED " else "stage=SUMMARY_MISSING "))
                val reads = queries.filter { it.first.contains("WHERE carId = ? AND driveId = ? LIMIT 1") }
                assertEquals(1, reads.size)
                assertEquals(listOf(context.localHistoryCarId, 41), reads.single().second.map { (it as Number).toInt() })
                assertEquals(if (quarantined) row else null,
                    db.driveSummaryDao().getAnyForPersistence(context.localHistoryCarId, 41))
                assertPrivateSafe(line())
            } finally { db.close() }
        }
    }

    @Test fun foregroundCallbackFailureDoesNotChangeNullAndCancellationPropagates() = runBlocking {
        val db = open()
        try {
            assertNull(persistVerifiedDriveDetail(db, HistoryDetailRepository(db.historyDetailDao()), context, detail(),
                onDiagnostic = { throw IllegalStateException("PRIVATE_CALLBACK_FAILURE") }) {})
            try {
                persistVerifiedDriveDetail(db, HistoryDetailRepository(db.historyDetailDao()), context, detail(),
                    onDiagnostic = { throw CancellationException("PRIVATE_CANCEL") }) {}
                fail("callback cancellation must propagate")
            } catch (_: CancellationException) { }
        } finally { db.close() }
    }

    @Test fun allFourFailureStagesDescribeOnlyThisReceivedDetailsSamplePresence() = runBlocking {
        val db = open()
        try {
            val received = detail().copy(positions = listOf(DrivePosition(date = start), DrivePosition(date = end)))
            val row = raw().toSyncSummary(context.localHistoryCarId)!!
            for (stage in listOf("SUMMARY_MISSING", "SUMMARY_IDENTITY_REJECTED", "DETAIL_IDENTITY_REJECTED", "SUMMARY_MERGE_UNAVAILABLE")) {
                db.driveSummaryDao().deleteDriveById(context.localHistoryCarId, 41)
                if (stage != "SUMMARY_MISSING") db.driveSummaryDao().upsert(
                    if (stage == "SUMMARY_IDENTITY_REJECTED") row.copy(apiEvidence = null) else row)
                val incoming = if (stage == "DETAIL_IDENTITY_REJECTED") received.copy(source = "PRIVATE_FOREIGN") else received
                var checks = 0
                ShadowLog.clear()
                assertNull(persistVerifiedDriveDetail(db, HistoryDetailRepository(db.historyDetailDao()), context, incoming,
                    onDiagnostic = foregroundDiagnostic) {
                    if (stage == "SUMMARY_MERGE_UNAVAILABLE" && ++checks == 2)
                        db.driveSummaryDao().deleteDriveById(context.localHistoryCarId, 41)
                })
                assertTrue(line().startsWith("stage=$stage "))
                assertTrue(line().contains("DETAIL_HAS_SAMPLES=true"))
                assertTrue(line().contains("DETAIL_HAS_MULTIPLE_SAMPLES=true"))
                assertPrivateSafe(line())
            }
        } finally { db.close() }
    }
}
