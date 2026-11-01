package com.matelink.data.repository

import android.app.Application
import androidx.room.Room
import com.matelink.data.api.models.*
import com.matelink.data.local.*
import com.matelink.data.sync.toSyncSummary
import com.matelink.domain.analytics.HistorySummaryEvidenceCodec
import com.matelink.domain.analytics.toRawAnalysisChargeData
import com.matelink.domain.analytics.toAnalysisDriveData
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
class HistorySourceBudgetIsolationTest {
    @get:Rule val temp = TemporaryFolder()
    private val start = "2026-10-13T00:00:00Z"
    private val end = "2026-10-13T00:01:00Z"
    private val car = -7

    private fun open() = Room.databaseBuilder(RuntimeEnvironment.getApplication(), StatsDatabase::class.java,
        File(temp.root, "source-budget.db").absolutePath).allowMainThreadQueries().build()
    private fun drive(id: Int = 4, at: String = start) = DriveData(id, startDate = at,
        endDate = if (at == start) end else "2026-10-13T01:01:00Z",
        source = "teslamate_archive", sourceInstanceId = "drive-a", sourceId = "drive-$id",
        qualityState = "observed", startAddress = "Saved drive", lengthUnit = "km")
    private fun charge(instance: String) = ChargeData(9, startDate = start, endDate = end,
        source = "teslamate_archive", sourceInstanceId = instance, sourceId = "charge-$instance",
        qualityState = "observed", chargeEnergyAdded = 2.0)

    private val scope = HistoryReadScope(HistoryConnectionSource.CLOUD, "cloud", "receipt-test", "https://receipt.invalid")
    private val context = VehicleContext(7,
        cloudOriginVehicleStableIdentity("receipt-test", "https://receipt.invalid", "vehicle-test"),
        car, HistoryConnectionSource.CLOUD, "cloud")

    @Test fun fullDriveArchiveDoesNotConsumeChargeArchiveOrCurveBudget() = runBlocking {
        var db = open()
        try {
            val savedDrive = drive().toSyncSummary(car)!!
            val savedCharge = charge("A").toSyncSummary(car)!!
            val full = requireNotNull(HistorySourceReceiptArchive.capture(savedDrive)).copy(
                receiptId = "synthetic-full-drive", identityKey = "synthetic-full-drive",
                encodedByteCount = 64L * 1024 * 1024)
            db.driveSummaryDao().upsert(savedDrive)
            db.chargeSummaryDao().upsert(savedCharge)
            db.driveSummaryDao().insertConflictArchive(full)

            db.chargeSummaryDao().upsertPreservingEvidence(listOf(charge("B").toSyncSummary(car)!!))
            assertEquals(64L * 1024 * 1024, db.driveSummaryDao().archiveUsedBytes())
            assertTrue(db.chargeSummaryDao().archiveUsedBytes() in 1L until (64L * 1024 * 1024))
            assertEquals(0L, db.historyDetailDao().storedByteCount())
            assertEquals("B", db.chargeSummaryDao().get(car, 9)!!.toRawAnalysisChargeData().sourceInstanceId)

            val detail = ChargeDetail(9, startDate = start, endDate = end, source = "teslamate_archive",
                sourceInstanceId = "B", sourceId = "charge-B", chargePoints = listOf(
                    ChargePoint(date = start, chargeEnergyAdded = 0.0), ChargePoint(date = end, chargeEnergyAdded = 2.0)))
            val identity = HistoryDetailIdentity.create(context, HistoryDetailIdentity.CHARGE, 9,
                "teslamate_archive", "B", start, end, "charge-B")
            assertTrue(HistoryDetailRepository(db.historyDetailDao()).saveCharge(identity, detail) is HistoryDetailWriteResult.Stored)
            db.close(); db = open()
            assertEquals(savedDrive, db.driveSummaryDao().get(car, 4))
            assertEquals(detail, HistoryDetailRepository(db.historyDetailDao()).readCharge(identity).detail)
        } finally { db.close() }
    }

    @Test fun fullChargeArchiveShowsCachedChargeWhileDriveStillRefreshes() = runBlocking {
        var db = open()
        try {
            val savedDrive = drive().toSyncSummary(car)!!
            val savedCharge = charge("A").toSyncSummary(car)!!
            val full = requireNotNull(HistorySourceReceiptArchive.capture(savedCharge)).copy(
                receiptId = "synthetic-full-charge", identityKey = "synthetic-full-charge",
                encodedByteCount = 64L * 1024 * 1024)
            db.driveSummaryDao().upsert(savedDrive)
            db.chargeSummaryDao().upsert(savedCharge)
            db.chargeSummaryDao().insertConflictArchive(full)
            val newDrive = drive(5, "2026-10-13T01:00:00Z")
            val repo = UnifiedHistoryRepository(HistoryReadDependencies(
                captureScope = { scope }, getCars = { ApiResult.Success(listOf(CarData(7, vehicleUid = "vehicle-test"))) },
                getHistoryContext = { ApiResult.Success(HistoryContextData(1, 7, "vehicle-test")) },
                resolveCar = { _, _ -> context }, cachedContext = { _, _ -> context },
                localDrives = { db.driveSummaryDao().getAllChronological(it) },
                localCharges = { db.chargeSummaryDao().getAllForCar(it) },
                getDrives = { _, _, _, page -> ApiResult.Success(if (page == 1) listOf(newDrive) else emptyList()) },
                getCharges = { _, _, _, page -> ApiResult.Success(if (page == 1) listOf(charge("B")) else emptyList()) },
                persistDrives = { db.driveSummaryDao().upsertPreservingEvidence(it) },
                persistCharges = { db.chargeSummaryDao().upsertPreservingEvidence(it) }
            ))
            val result = repo.load(7)
            assertTrue(result is ApiResult.Success)
            val history = (result as ApiResult.Success).data
            assertEquals("history_cached", history.chargesSyncError)
            assertFalse(history.chargesFromRemote)
            assertTrue(history.drivesFromRemote)
            assertEquals(setOf(4, 5), history.drives.map { it.driveId }.toSet())
            assertEquals("A", history.charges.single().sourceInstanceId)
            assertEquals(savedCharge, db.chargeSummaryDao().get(car, 9))
            assertNotNull(db.driveSummaryDao().get(car, 5))
            assertEquals(0L, db.historyDetailDao().storedByteCount())
            db.close(); db = open()
            assertEquals(savedCharge, db.chargeSummaryDao().get(car, 9))
            assertNotNull(db.driveSummaryDao().get(car, 5))
        } finally { db.close() }
    }

    @Test fun legacyUnverifiedDriveRejectionKeepsOtherCarAndChargeCurve() = runBlocking {
        var db = open()
        try {
            val legacy = drive().toSyncSummary(car)!!.copy(apiEvidence = null,
                startBatteryLevel = 80, endBatteryLevel = 79, outsideTempAvg = -4.0,
                qualityState = "incomplete", qualityReason = "legacy_unverified")
            val other = legacy.copy(carId = -8, startBatteryLevel = 42)
            val incoming = drive().copy(source = null, sourceInstanceId = null, sourceId = null,
                startAddress = "New unverified source").toSyncSummary(car)!!
            db.driveSummaryDao().upsert(legacy)
            db.driveSummaryDao().upsert(other)
            val detail = ChargeDetail(9, startDate = start, endDate = end, source = "teslamate_archive",
                sourceInstanceId = "A", sourceId = "charge-A", chargePoints = listOf(
                    ChargePoint(date = start, chargeEnergyAdded = 0.0), ChargePoint(date = end, chargeEnergyAdded = 2.0)))
            val identity = HistoryDetailIdentity.create(context, HistoryDetailIdentity.CHARGE, 9,
                "teslamate_archive", "A", start, end, "charge-A")
            val points = HistoryDetailRepository(db.historyDetailDao())
            assertTrue(points.saveCharge(identity, detail) is HistoryDetailWriteResult.Stored)
            val beforeBytes = db.historyDetailDao().storedByteCount()
            try {
                db.driveSummaryDao().upsertPreservingEvidence(listOf(incoming))
                fail("A legacy row without a raw receipt must not be overwritten")
            } catch (_: HistorySourceArchiveException) { }
            assertEquals(beforeBytes, db.historyDetailDao().storedByteCount())
            db.close(); db = open()
            assertEquals(legacy, db.driveSummaryDao().get(car, 4))
            assertEquals(other, db.driveSummaryDao().get(-8, 4))
            assertEquals(detail, HistoryDetailRepository(db.historyDetailDao()).readCharge(identity).detail)
        } finally { db.close() }
    }

    @Test fun untrustedUnknownUnitReceiptIsArchivedWithoutReplacingVerifiedDrive() = runBlocking {
        var db = open()
        try {
            val verified = drive().toSyncSummary(car)!!
            val weak = drive().copy(qualityState = "incomplete", qualityReason = "unverified_source",
                startAddress = "Unverified address", lengthUnit = "").toSyncSummary(car)!!
            db.driveSummaryDao().upsert(verified)
            db.driveSummaryDao().upsertPreservingEvidence(listOf(weak))
            db.close(); db = open()
            val saved = requireNotNull(db.driveSummaryDao().get(car, 4))
            assertEquals(HistorySummaryEvidenceCodec.sourceEvidence(verified.apiEvidence),
                HistorySummaryEvidenceCodec.sourceEvidence(saved.apiEvidence))
            assertEquals("Saved drive", saved.toAnalysisDriveData().startAddress)
            val archived = mutableListOf<String>()
            db.openHelper.readableDatabase.query(
                "SELECT headerJson FROM history_detail_receipts WHERE localHistoryCarId = ? AND kind = ?",
                arrayOf(car, "archived_drive_source")
            ).use { rows -> while (rows.moveToNext()) archived += rows.getString(0) }
            assertTrue(archived.any { HistorySummaryEvidenceCodec.sourceEvidence(it) ==
                HistorySummaryEvidenceCodec.sourceEvidence(weak.apiEvidence) })
        } finally { db.close() }
    }
}
