package com.matelink.data.repository

import android.app.Application
import androidx.room.Room
import com.matelink.data.api.models.*
import com.matelink.data.local.*
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

/** Synthetic receipts through actual Room admission, Unified paging and persisted projection. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
class ChargeSourceInstanceAdmissionTest {
    @get:Rule val temp = TemporaryFolder()
    private val localId = -701
    private val scope = HistoryReadScope(HistoryConnectionSource.CLOUD, "cloud", "synthetic-account", "https://synthetic.test")
    private val context = VehicleContext(7, cloudOriginVehicleStableIdentity("synthetic-account", "https://synthetic.test", "synthetic-car"),
        localId, HistoryConnectionSource.CLOUD, "cloud")
    private val start = "2026-10-10T00:00:00Z"
    private val end = "2026-10-10T00:30:00Z"
    private fun open() = Room.databaseBuilder(RuntimeEnvironment.getApplication(), StatsDatabase::class.java,
        File(temp.root, "source-instance.db").absolutePath).allowMainThreadQueries().build()
    private fun row(instance: String?, sourceId: String? = "session-17", energy: Double? = 4.0) = ChargeData(
        17, startDate = start, endDate = end, source = "teslamate_archive", qualityState = "observed",
        sourceInstanceId = instance, sourceId = sourceId, chargeEnergyAdded = energy)
    private fun detail(row: ChargeData, energy: Double = 9.0) = ChargeDetail(row.chargeId,
        startDate = row.startDate, endDate = row.endDate, source = row.source,
        sourceInstanceId = row.sourceInstanceId, sourceId = row.sourceId, chargeEnergyAdded = energy)
    private fun repository(db: StatsDatabase, remote: List<ChargeData>) = UnifiedHistoryRepository(HistoryReadDependencies(
        captureScope = { scope }, getCars = { ApiResult.Success(listOf(CarData(7, vehicleUid = "synthetic-car"))) },
        getHistoryContext = { ApiResult.Success(HistoryContextData(1, 7, "synthetic-car")) },
        resolveCar = { _, _ -> context }, cachedContext = { _, _ -> context },
        localDrives = { emptyList() }, localCharges = { db.chargeSummaryDao().getAllForCar(it) },
        getDrives = { _, _, _, _ -> ApiResult.Success(emptyList()) },
        getCharges = { _, _, _, page -> ApiResult.Success(remote.drop((page - 1) * 50).take(50)) },
        persistDrives = {}, persistCharges = { db.chargeSummaryDao().upsertPreservingEvidence(it) }
    ))
    private suspend fun loaded(db: StatsDatabase, remote: List<ChargeData>): List<ChargeData> {
        val result = repository(db, remote).load(7)
        assertTrue(result is ApiResult.Success)
        return (result as ApiResult.Success).data.charges
    }

    @Test fun daoRejectsDifferentInstanceAndOneSidedInstanceWithoutMutatingReceipt() = runBlocking {
        val db = open()
        try {
            val dao = db.chargeSummaryDao()
            for ((saved, incoming) in listOf(row("A") to row("B"), row("A") to row(null),
                row(null) to row("A"), row("A") to row("A", "other-session"),
                row("A") to row("A", null), row("A", null) to row("A"))) {
                dao.upsert(saved.toSyncSummary(localId)!!)
                val before = dao.get(localId, 17)!!.apiEvidence
                assertFalse("Detail must not cross instance/session identity: ${saved.sourceInstanceId}/${incoming.sourceInstanceId}",
                    dao.mergeDetail(localId, detail(incoming)) {})
                assertEquals(before, dao.get(localId, 17)!!.apiEvidence)
            }
        } finally { db.close() }
    }

    @Test fun pagedUnifiedRefreshCannotCarryDetailProofAcrossInstanceOrOneSidedIdentityAfterReopen() = runBlocking {
        var db = open()
        try {
            for ((oldInstance, nextInstance) in listOf("A" to "B", "A" to null, null to "A")) {
                val saved = row(oldInstance)
                db.chargeSummaryDao().upsert(saved.toSyncSummary(localId)!!)
                assertTrue(db.chargeSummaryDao().mergeDetail(localId, detail(saved)) {})
val fillers = (100..162).map { id ->
                    val at = java.time.Instant.parse(start).plusSeconds(id * 3600L)
                    saved.copy(chargeId = id, sourceId = "session-$id", startDate = at.toString(),
                        endDate = at.plusSeconds(1800).toString())
                }
                val current = row(nextInstance, energy = null)
                val received = loaded(db, fillers + current).single { it.chargeId == 17 }
                assertNull("Old instance proof must not qualify a weak new receipt", received.batteryInputKwh)
                assertEquals(nextInstance, received.sourceInstanceId)
                db.close(); db = open()
                val persisted = db.chargeSummaryDao().get(localId, 17)!!.toAnalysisChargeData()
                assertNull(persisted.batteryInputKwh)
                assertEquals(nextInstance, persisted.sourceInstanceId)
                assertEquals(64, db.chargeSummaryDao().getAllForCar(localId).size)
            }
        } finally { db.close() }
    }

    @Test fun matchingInstanceWeakRefreshKeepsDetailButExplicitUnknownRevokesAndReportedZeroRemainsKnown() = runBlocking {
        val db = open()
        try {
            val saved = row("A")
            loaded(db, listOf(saved))
            assertTrue(db.chargeSummaryDao().mergeDetail(localId, detail(saved)) {})
            val weak = saved.copy(chargeEnergyAdded = null)
            assertEquals(9.0, loaded(db, listOf(weak)).single().batteryInputKwh!!, 0.0)
            val unknown = EnergyContract(batteryInput = EnergyMetric(method = "session_counter_delta",
                measurementPoint = "battery_input", source = saved.source, quality = "unknown",
                reason = "provider_revoked", startDate = start, endDate = end))
            assertNull(loaded(db, listOf(weak.copy(energyContract = unknown))).single().batteryInputKwh)
            // A separate receipt starts from positive detail; unknown is not silently revoked by a null contract.
            db.chargeSummaryDao().upsert(saved.toSyncSummary(localId)!!)
            assertTrue(db.chargeSummaryDao().mergeDetail(localId, detail(saved)) {})
            assertEquals(0.0, loaded(db, listOf(saved.copy(chargeEnergyAdded = 0.0))).single().batteryInputKwh!!, 0.0)
        } finally { db.close() }
    }

    @Test fun sameInstanceFleetCounterProofSurvivesWeakPageAndExplicitUnknownThenZeroRemainAuthoritative() = runBlocking {
        val db = open()
        try {
            val raw = row("fleet-A", energy = null).copy(source = "telemetry_mqtt")
            fun counter(value: Double?) = EnergyContract(batteryInput = EnergyMetric(
                valueKwh = value, method = "session_counter_delta", measurementPoint = "battery_input",
                source = raw.source, quality = if (value == null) "unknown" else "reported",
                startDate = start, endDate = end, coverageKind = "endpoints", coverageRatio = 1.0))
            loaded(db, listOf(raw))
            assertTrue(db.chargeSummaryDao().mergeDetail(localId,
                detail(raw).copy(chargeEnergyAdded = null, energyContract = counter(8.0))) {})
            assertEquals(8.0, loaded(db, listOf(raw)).single().batteryInputKwh!!, 0.0)
            assertNull(loaded(db, listOf(raw.copy(energyContract = counter(null)))).single().batteryInputKwh)
            assertEquals(0.0, loaded(db, listOf(raw.copy(energyContract = counter(0.0)))).single().batteryInputKwh!!, 0.0)
        } finally { db.close() }
    }

    @Test fun legacyBothUnknownRemainCompatibleWithoutInventingSourceIdentityOrFleetContract() = runBlocking {
        val db = open()
        try {
            val saved = row(null, null)
            loaded(db, listOf(saved))
            assertTrue(db.chargeSummaryDao().mergeDetail(localId, detail(saved, 0.0)) {})
            val result = loaded(db, listOf(saved.copy(chargeEnergyAdded = null))).single()
            assertEquals(0.0, result.batteryInputKwh!!, 0.0)
            assertNull(result.sourceInstanceId)
            assertNull(result.sourceId)
            assertNull(result.energyContract)
        } finally { db.close() }
    }

    @Test fun localPresentationFromAnotherInstanceCannotCertifyRawReceipt() = runBlocking {
        val db = open()
        try {
            val raw = row("A")
            val forged = HistorySummaryEvidenceCodec.withChargeDetail(
                HistorySummaryEvidenceCodec.encode(raw), raw, EnergyContract(), 91.0, null,
                raw.source, start, end, localId, row("B").copy(chargeEnergyAdded = null))
            db.chargeSummaryDao().upsert(raw.toSyncSummary(localId)!!.copy(apiEvidence = forged))
            assertNull(db.chargeSummaryDao().get(localId, 17)!!.toAnalysisChargeData().batteryInputKwh)
        } finally { db.close() }
    }
}
