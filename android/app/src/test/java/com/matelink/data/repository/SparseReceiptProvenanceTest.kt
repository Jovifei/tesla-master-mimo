package com.matelink.data.repository

import android.app.Application
import androidx.room.Room
import com.matelink.data.api.models.*
import com.matelink.data.local.*
import com.matelink.data.local.dao.DriveSummaryDao
import com.matelink.data.local.entity.DriveSummary
import com.matelink.data.sync.toSyncSummary
import com.matelink.domain.analytics.*
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

/** Synthetic provider inputs; real production merge and isolated disk Room, never owner data. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
class SparseReceiptProvenanceTest {
    @get:Rule val temp = TemporaryFolder()
    private val start = "2026-10-13T00:00:00Z"
    private val end = "2026-10-13T00:01:00Z"
    private val original = DriveData(4, startDate = start, endDate = end,
        source = "telemetry_mqtt", qualityState = "observed",
        startAddress = "Old provider address", energyConsumedNet = 8.0,
        batteryDetails = DriveBatteryDetails(80, 79),
        odometerDetails = DriveOdometerDetails(distance = 2.0))
    private val sparse = original.copy(startAddress = "New provider address",
        energyConsumedNet = null, batteryDetails = null)

    private fun savedDetail(): DriveSummary {
        val detail = original.asCachedDetail()
        return original.toSyncSummary(-7)!!.withResolvedDriveEnergy(detail,
            detail.resolveDriveEnergy(model = "Y", trimBadging = "Long Range AWD"))
    }

    private fun assertGenuineReceipt(row: DriveSummary, vararg inputs: DriveSummary) {
        val source = requireNotNull(HistorySummaryEvidenceCodec.sourceJson(row.apiEvidence))
        assertTrue("Raw source must equal one complete received receipt, never merged field JSON",
            inputs.any { source == HistorySummaryEvidenceCodec.sourceJson(it.apiEvidence) })
    }

    private fun open() = Room.databaseBuilder(RuntimeEnvironment.getApplication(), StatsDatabase::class.java,
        File(temp.root, "sparse-receipt.db").absolutePath).allowMainThreadQueries().build()

    @Test fun sparseMergeKeepsOneGenuineReceiptAndScopedCapacityDetail() {
        val cached = savedDetail()
        val incoming = sparse.toSyncSummary(-7)!!
        val merged = mergeStoredDrive(incoming, cached)
        assertGenuineReceipt(merged, cached, incoming)
        assertEquals(0.784, merged.energyConsumed!!, 1e-12)
        assertEquals(78.4, merged.toAnalysisDriveData().energyContract!!.netEnergy!!.capacityKwh!!, 1e-12)
        assertEquals(79, merged.toAnalysisDriveData().endBatteryLevel)
        assertEquals("New provider address", merged.startAddress)
    }

    @Test fun sparseMergeWithoutDetailSnapshotStillKeepsAnActualRawReceipt() {
        val cached = original.toSyncSummary(-7)!!
        val incoming = sparse.toSyncSummary(-7)!!
        val merged = mergeStoredDrive(incoming, cached)
        assertGenuineReceipt(merged, cached, incoming)
        assertEquals("New provider address", merged.startAddress)
        assertNull(merged.energyConsumed)
        assertNull(merged.toAnalysisDriveData().netEnergyKwh)
    }

    @Test fun repeatedSummaryInputsPreserveRicherDetailWhileNewAddressApplies() {
        val detail = original.asCachedDetail().copy(
            odometerDetails = DriveOdometerDetails(distance = 3.0))
        val cached = original.toSyncSummary(-7)!!.withResolvedDriveEnergy(detail,
            detail.resolveDriveEnergy(model = "Y", trimBadging = "Long Range AWD"))
        val incoming = sparse.toSyncSummary(-7)!!
        val merged = mergeStoredDrive(incoming, cached)
        assertGenuineReceipt(merged, cached, incoming)
        assertEquals(3.0, merged.distance, 0.0)
        assertEquals("New provider address", merged.startAddress)
        assertEquals(0.784, merged.energyConsumed!!, 1e-12)
    }

    @Test fun repeatedSummaryCoordinatesPreserveActualDetailCoordinates() {
        val raw = original.copy(startLatitude = 1.0, startLongitude = 2.0,
            endLatitude = 3.0, endLongitude = 4.0)
        val detail = raw.asCachedDetail().copy(startLatitude = 11.0, startLongitude = 12.0,
            endLatitude = 13.0, endLongitude = 14.0)
        val cached = raw.toSyncSummary(-7)!!.withResolvedDriveEnergy(detail,
            detail.resolveDriveEnergy(model = "Y", trimBadging = "Long Range AWD"))
        val incoming = raw.copy(startAddress = "New provider address", energyConsumedNet = null,
            batteryDetails = null).toSyncSummary(-7)!!
        val merged = mergeStoredDrive(incoming, cached)
        assertEquals(incoming.apiEvidence, HistorySummaryEvidenceCodec.sourceJson(merged.apiEvidence))
        val displayed = merged.toAnalysisDriveData()
        assertEquals(11.0, displayed.startLatitude!!, 0.0)
        assertEquals(12.0, displayed.startLongitude!!, 0.0)
        assertEquals(13.0, displayed.endLatitude!!, 0.0)
        assertEquals(14.0, displayed.endLongitude!!, 0.0)
        assertEquals("New provider address", displayed.startAddress)
    }

    @Test fun diskUpsertReopenAndActualRepositoryLoadCannotInventRawReceipt() = runBlocking {
        var db = open()
        val cached = savedDetail()
        val incoming = sparse.toSyncSummary(-7)!!
        try {
            db.driveSummaryDao().upsert(cached)
            db.driveSummaryDao().upsertPreservingEvidence(listOf(incoming))
            val written = db.driveSummaryDao().get(-7, 4)!!
            db.close()
            db = open()
            val reopened = db.driveSummaryDao().get(-7, 4)!!
            assertEquals(written.apiEvidence, reopened.apiEvidence)
            val loaded = repository(db.driveSummaryDao()).load(7)
            assertTrue(loaded is ApiResult.Success)
            assertGenuineReceipt(db.driveSummaryDao().get(-7, 4)!!, cached, incoming)
            assertEquals(0.784, db.driveSummaryDao().get(-7, 4)!!.energyConsumed!!, 1e-12)
        } finally { db.close() }
    }

    @Test fun explicitUnknownReceiptRevokesOldDetailWithoutSynthesizingRawFields() {
        val cached = savedDetail()
        val incoming = sparse.copy(energyContract = EnergyContract(netEnergy = EnergyMetric(
            quality = "unknown", source = original.source, startDate = start, endDate = end,
            reason = "provider_energy_unsupported"))).toSyncSummary(-7)!!
        val merged = mergeStoredDrive(incoming, cached)
        assertEquals(incoming.apiEvidence, HistorySummaryEvidenceCodec.sourceJson(merged.apiEvidence))
        assertNull(merged.energyConsumed)
        assertNull(merged.toAnalysisDriveData().netEnergyKwh)
    }

    @Test fun completeNewSocReceiptRecomputesCapacityAndRemainsByteExact() {
        val cached = savedDetail()
        val incoming = original.copy(startAddress = "Corrected provider address",
            energyConsumedNet = null, batteryDetails = DriveBatteryDetails(80, 78)).toSyncSummary(-7)!!
        val merged = mergeStoredDrive(incoming, cached)
        assertEquals(incoming.apiEvidence, HistorySummaryEvidenceCodec.sourceJson(merged.apiEvidence))
        assertEquals(1.568, merged.energyConsumed!!, 1e-12)
        assertEquals(78, merged.toAnalysisDriveData().endBatteryLevel)
    }

    @Test fun unknownThenRepeatedSparseRefreshKeepsOneBoundedGenuinePriorReceipt() {
        val cached = original.toSyncSummary(-7)!!
        val unknown = sparse.copy(energyContract = EnergyContract(netEnergy = EnergyMetric(
            quality = "unknown", source = original.source, startDate = start, endDate = end,
            reason = "provider_energy_unsupported"))).toSyncSummary(-7)!!
        var saved = mergeStoredDrive(unknown, cached)
        repeat(3) {
            saved = mergeStoredDrive(sparse.toSyncSummary(-7)!!, saved)
            assertGenuineReceipt(saved, cached, unknown, sparse.toSyncSummary(-7)!!)
            val archived = HistorySummaryEvidenceCodec.priorDriveSourceJson(saved.apiEvidence, -7)
            assertEquals(cached.apiEvidence, archived)
            assertNotNull(archived)
            assertEquals(8.0, HistorySummaryEvidenceCodec.decodeDrive(requireNotNull(archived))!!.energyConsumedNet!!, 0.0)
            assertNull(saved.energyConsumed)
            assertNull(saved.toAnalysisDriveData().netEnergyKwh)
            assertNull(HistorySummaryEvidenceCodec.priorDriveSourceJson(saved.apiEvidence, 7))
        }
        val foreignWindow = sparse.copy(endDate = "2026-10-13T00:02:00Z").toSyncSummary(-7)!!
        val changed = mergeStoredDrive(foreignWindow, saved)
        assertNull(HistorySummaryEvidenceCodec.priorDriveSourceJson(changed.apiEvidence, -7))
    }

    private fun repository(dao: DriveSummaryDao): UnifiedHistoryRepository {
        val scope = HistoryReadScope(HistoryConnectionSource.CLOUD, "cloud", "receipt-test", "https://receipt.invalid")
        val context = VehicleContext(7,
            cloudOriginVehicleStableIdentity("receipt-test", "https://receipt.invalid", "vehicle-test"),
            -7, HistoryConnectionSource.CLOUD, "cloud")
        return UnifiedHistoryRepository(HistoryReadDependencies(
            captureScope = { scope }, getCars = { ApiResult.Success(listOf(CarData(7, vehicleUid = "vehicle-test"))) },
            getHistoryContext = { ApiResult.Success(HistoryContextData(1, 7, "vehicle-test")) },
            resolveCar = { _, _ -> context }, cachedContext = { _, _ -> context },
            localDrives = { dao.getAllChronological(it) }, localCharges = { emptyList() },
            getDrives = { _, _, _, page -> ApiResult.Success(if (page == 1) listOf(sparse) else emptyList()) },
            getCharges = { _, _, _, _ -> ApiResult.Success(emptyList()) },
            persistDrives = { dao.upsertPreservingEvidence(it) }, persistCharges = {}
        ))
    }
}
