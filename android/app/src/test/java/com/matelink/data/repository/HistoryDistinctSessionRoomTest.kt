package com.matelink.data.repository

import android.app.Application
import androidx.room.Room
import com.matelink.data.api.models.*
import com.matelink.data.local.*
import com.matelink.data.sync.toSyncSummary
import com.matelink.domain.analytics.HistorySummaryEvidenceCodec
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
class HistoryDistinctSessionRoomTest {
    @get:Rule val temp = TemporaryFolder()
    private val start = "2026-10-13T00:00:00Z"
    private val end = "2026-10-13T00:01:00Z"
    private val offsetStart = "2026-10-13T08:00:00+08:00"
    private val offsetEnd = "2026-10-13T08:01:00+08:00"
    private val car = -7
    private val scope = HistoryReadScope(HistoryConnectionSource.CLOUD, "cloud", "receipt-test", "https://receipt.invalid")
    private val context = VehicleContext(7,
        cloudOriginVehicleStableIdentity("receipt-test", "https://receipt.invalid", "vehicle-test"),
        car, HistoryConnectionSource.CLOUD, "cloud")

    private fun open() = Room.databaseBuilder(RuntimeEnvironment.getApplication(), StatsDatabase::class.java,
        File(temp.root, "distinct-session.db").absolutePath).allowMainThreadQueries().build()
    private fun drive(id: Int, source: String, instance: String, record: String) = DriveData(id,
        startDate = start, endDate = end, source = source, sourceInstanceId = instance, sourceId = record,
        qualityState = "observed", lengthUnit = "km", startAddress = "Saved origin",
        batteryDetails = DriveBatteryDetails(80, 79))
    private fun charge(id: Int, source: String, instance: String, record: String) = ChargeData(id,
        startDate = start, endDate = end, source = source, sourceInstanceId = instance, sourceId = record,
        qualityState = "observed", address = "Saved charger", batteryDetails = ChargeBatteryDetails(20, 80),
        chargeEnergyAdded = 10.0)

    private fun repository(db: StatsDatabase, drives: List<DriveData>, charges: List<ChargeData>) =
        UnifiedHistoryRepository(HistoryReadDependencies(
            captureScope = { scope }, getCars = { ApiResult.Success(listOf(CarData(7, vehicleUid = "vehicle-test"))) },
            getHistoryContext = { ApiResult.Success(HistoryContextData(1, 7, "vehicle-test")) },
            resolveCar = { _, _ -> context }, cachedContext = { _, _ -> context },
            localDrives = { db.driveSummaryDao().getAllChronological(it) },
            localCharges = { db.chargeSummaryDao().getAllForCar(it) },
            getDrives = { _, _, _, page -> ApiResult.Success(if (page == 1) drives else emptyList()) },
            getCharges = { _, _, _, page -> ApiResult.Success(if (page == 1) charges else emptyList()) },
            persistDrives = { db.driveSummaryDao().upsertPreservingEvidence(it) },
            persistCharges = { db.chargeSummaryDao().upsertPreservingEvidence(it) }
        ))

    private suspend fun assertDifferentIdsRemainSeparate(sameSource: Boolean) {
        var db = open()
        try {
            val oldDrive = drive(41, "teslamate_archive", "source-a", "drive-record")
            val oldCharge = charge(51, "teslamate_archive", "source-a", "charge-record")
            val nextSource = if (sameSource) "teslamate_archive" else "telemetry_mqtt"
            val nextInstance = if (sameSource) "source-a" else "source-b"
            val nextDrive = drive(42, nextSource, nextInstance,
                if (sameSource) "drive-record" else "other-drive-record").copy(
                startDate = offsetStart, endDate = offsetEnd, startAddress = null,
                endAddress = "New destination", batteryDetails = null)
            val nextCharge = charge(52, nextSource, nextInstance,
                if (sameSource) "charge-record" else "other-charge-record").copy(
                startDate = offsetStart, endDate = offsetEnd, address = "New charger",
                batteryDetails = null, chargeEnergyAdded = null)
            val otherDrive = oldDrive.copy(sourceInstanceId = "vehicle-b", sourceId = "other-car-drive")
                .toSyncSummary(-8)!!
            val otherCharge = oldCharge.copy(sourceInstanceId = "vehicle-b", sourceId = "other-car-charge")
                .toSyncSummary(-8)!!
            val oldDriveRow = oldDrive.toSyncSummary(car)!!
            val oldChargeRow = oldCharge.toSyncSummary(car)!!
            db.driveSummaryDao().upsert(oldDriveRow)
            db.chargeSummaryDao().upsert(oldChargeRow)
            db.driveSummaryDao().upsert(otherDrive)
            db.chargeSummaryDao().upsert(otherCharge)

            val result = repository(db, listOf(nextDrive), listOf(nextCharge)).load(7)
            assertTrue(result is ApiResult.Success)
            val history = (result as ApiResult.Success).data
            assertEquals(setOf(41, 42), history.drives.map { it.driveId }.toSet())
            assertEquals(setOf(51, 52), history.charges.map { it.chargeId }.toSet())
            assertNull(history.drives.single { it.driveId == 42 }.startBatteryLevel)
            assertNull(history.drives.single { it.driveId == 42 }.startAddress)
            assertNull(history.charges.single { it.chargeId == 52 }.startBatteryLevel)
            assertNull(history.charges.single { it.chargeId == 52 }.batteryInputKwh)
            db.close(); db = open()
            assertEquals(oldDriveRow, db.driveSummaryDao().get(car, 41))
            assertEquals(oldChargeRow, db.chargeSummaryDao().get(car, 51))
            assertEquals(HistorySummaryEvidenceCodec.sourceEvidence(nextDrive.toSyncSummary(car)!!.apiEvidence),
                HistorySummaryEvidenceCodec.sourceEvidence(db.driveSummaryDao().get(car, 42)!!.apiEvidence))
            assertEquals(HistorySummaryEvidenceCodec.sourceEvidence(nextCharge.toSyncSummary(car)!!.apiEvidence),
                HistorySummaryEvidenceCodec.sourceEvidence(db.chargeSummaryDao().get(car, 52)!!.apiEvidence))
            assertEquals(otherDrive, db.driveSummaryDao().get(-8, 41))
            assertEquals(otherCharge, db.chargeSummaryDao().get(-8, 51))
        } finally { db.close() }
    }

    @Test fun sameSourceAndRecordWithoutIdMappingRemainSeparate() = runBlocking {
        assertDifferentIdsRemainSeparate(sameSource = true)
    }

    @Test fun differentSourcesSameInstantsRemainSeparateAcrossVehicles() = runBlocking {
        assertDifferentIdsRemainSeparate(sameSource = false)
    }

    @Test fun sameIdSameIdentityRefreshIsIdempotentInRoom() = runBlocking {
        var db = open()
        try {
            val oldDrive = drive(41, "teslamate_archive", "source-a", "drive-record")
            val nextDrive = oldDrive.copy(startDate = offsetStart, endDate = offsetEnd,
                startAddress = null, endAddress = "New destination", batteryDetails = null)
            val oldCharge = charge(51, "teslamate_archive", "source-a", "charge-record")
            db.driveSummaryDao().upsert(oldDrive.toSyncSummary(car)!!)
            db.chargeSummaryDao().upsert(oldCharge.toSyncSummary(car)!!)
            val repo = repository(db, listOf(nextDrive), listOf(oldCharge))
            repeat(2) { assertTrue(repo.load(7) is ApiResult.Success) }
            db.close(); db = open()
            assertEquals(listOf(41), db.driveSummaryDao().getAllChronological(car).map { it.driveId })
            assertEquals(listOf(51), db.chargeSummaryDao().getAllForCar(car).map { it.chargeId })
            assertEquals(HistorySummaryEvidenceCodec.sourceEvidence(nextDrive.toSyncSummary(car)!!.apiEvidence),
                HistorySummaryEvidenceCodec.sourceEvidence(db.driveSummaryDao().get(car, 41)!!.apiEvidence))
        } finally { db.close() }
    }
}
