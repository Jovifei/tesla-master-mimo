package com.matelink.data.repository

import android.app.Application
import androidx.room.Room
import com.matelink.data.api.models.CarData
import com.matelink.data.api.models.ChargeData
import com.matelink.data.api.models.ChargeDetail
import com.matelink.data.api.models.EnergyContract
import com.matelink.data.api.models.EnergyMetric
import com.matelink.data.api.models.HistoryContextData
import com.matelink.data.local.HistoryConnectionSource
import com.matelink.data.local.HistoryReadScope
import com.matelink.data.local.StatsDatabase
import com.matelink.data.local.VehicleContext
import com.matelink.data.local.cloudOriginVehicleStableIdentity
import com.matelink.data.sync.toSyncSummary
import com.matelink.domain.analytics.ChargeCostSource
import com.matelink.domain.analytics.HistorySummaryEvidenceCodec
import com.matelink.domain.analytics.resolveChargeCostFromTotal
import com.matelink.domain.analytics.toAnalysisChargeData
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Synthetic receipts through the real history page loader, Room DAO and list cost resolver. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
class Charge64PageRoomConsumerTest {
    @get:Rule val temp = TemporaryFolder()

    private val remoteCarId = 7
    private val localCarId = -71
    private val rate = 1.14
    private val origin = "https://api.example"
    private val scope = HistoryReadScope(HistoryConnectionSource.CLOUD, "cloud", "account-a", origin)
    private val vehicle = VehicleContext(remoteCarId,
        cloudOriginVehicleStableIdentity("account-a", origin, "vehicle-a"),
        localCarId, HistoryConnectionSource.CLOUD, "cloud")

    private fun open() = Room.databaseBuilder(RuntimeEnvironment.getApplication(),
        StatsDatabase::class.java, File(temp.root, "charge-64.db").absolutePath)
        .allowMainThreadQueries().build()

    private fun charge(id: Int): ChargeData {
        val start = Instant.parse("2026-10-10T00:00:00Z").plusSeconds(id * 3600L)
        val energy = when (id) {
            17 -> 24.0
            46 -> 22.98
            else -> 4.0 + id * 0.125
        }
        return ChargeData(id, startDate = start.toString(),
            endDate = start.plusSeconds(39 * 60L).toString(),
            durationMin = 39, chargeEnergyAdded = energy,
            source = "teslamate_archive", qualityState = "observed")
    }

    private fun detail(row: ChargeData, energy: Double): ChargeDetail = ChargeDetail(
        chargeId = row.chargeId, startDate = row.startDate, endDate = row.endDate,
        source = row.source, durationMin = row.durationMin, chargeEnergyAdded = energy
    )

    private inner class Fixture(var db: StatsDatabase) {
        val original = (1..64).map(::charge)
        var remote = original
        var online = true
        var errorPage: Int? = null
        var cancelPage: Int? = null
        val requestedPages = mutableListOf<Int>()

        fun repository() = UnifiedHistoryRepository(HistoryReadDependencies(
            captureScope = { scope },
            getCars = { ApiResult.Success(listOf(CarData(remoteCarId, vehicleUid = "vehicle-a"))) },
            getHistoryContext = {
                if (online) ApiResult.Success(HistoryContextData(1, remoteCarId, "vehicle-a"))
                else ApiResult.Error("offline", 503)
            },
            resolveCar = { car, expected ->
                check(car.carId == remoteCarId && car.vehicleUid == "vehicle-a" && expected == scope)
                vehicle
            },
            cachedContext = { id, expected -> vehicle.takeIf { id == remoteCarId && expected == scope } },
            localDrives = { emptyList() },
            localCharges = { db.chargeSummaryDao().getAllForCar(it) },
            getDrives = { _, _, _, _ -> ApiResult.Success(emptyList()) },
            getCharges = { id, _, _, page ->
                check(id == remoteCarId)
                requestedPages += page
                if (page == cancelPage) throw CancellationException("request cancelled")
                if (page == errorPage) ApiResult.Error("page unavailable", 503)
                else ApiResult.Success(remote.drop((page - 1) * 50).take(50))
            },
            persistDrives = {},
            persistCharges = { db.chargeSummaryDao().upsertPreservingEvidence(it) }
        ))
    }

    private suspend fun loaded(repository: UnifiedHistoryRepository): UnifiedHistory {
        val result = repository.load(remoteCarId)
        assertTrue(result is ApiResult.Success)
        return (result as ApiResult.Success).data
    }

    private fun energy(charges: List<ChargeData>): Double =
        charges.mapNotNull { it.batteryInputKwh }.sum()

    private fun estimatedCost(charges: List<ChargeData>): Double = charges.mapNotNull { row ->
        val resolved = resolveChargeCostFromTotal(null, false, false, row.cost,
            row.batteryInputKwh, rate)
        assertEquals(ChargeCostSource.ESTIMATE, resolved.source)
        resolved.cost
    }.sum()

    @Test fun sixtyFourPagedChargesRetainDetailEnergyAndDefaultEstimateAcrossWeakRefreshAndReopen() = runBlocking {
        var db = open()
        val fixture = Fixture(db)
        try {
            val first = loaded(fixture.repository())
            assertEquals(listOf(1, 2), fixture.requestedPages)
            assertEquals(64, first.charges.size)
            assertEquals(64, db.chargeSummaryDao().count(localCarId))
            assertEquals(fixture.original.sumOf { it.chargeEnergyAdded!! }, energy(first.charges), 1e-9)
            assertEquals(64, first.charges.mapNotNull { it.batteryInputKwh }.size)

            val target = fixture.original.first { it.chargeId == 17 }
            val rawBefore = HistorySummaryEvidenceCodec.sourceJson(
                db.chargeSummaryDao().get(localCarId, 17)!!.apiEvidence)
            assertTrue(db.chargeSummaryDao().mergeDetail(localCarId, detail(target, 24.02)) {})
            fixture.remote = fixture.original.map {
                if (it.chargeId == 17 || it.chargeId == 46) it.copy(chargeEnergyAdded = null) else it
            }
            fixture.requestedPages.clear()
            val refreshed = loaded(fixture.repository())
            assertEquals(listOf(1, 2), fixture.requestedPages)
            assertEquals(64, refreshed.charges.size)
            assertEquals(24.02, refreshed.charges.single { it.chargeId == 17 }.batteryInputKwh!!, 1e-9)
            assertEquals(22.98, refreshed.charges.single { it.chargeId == 46 }.batteryInputKwh!!, 1e-9)
            val fixtureTotal = fixture.original.sumOf {
                if (it.chargeId == 17) 24.02 else it.chargeEnergyAdded!!
            }
            assertEquals(fixtureTotal, energy(refreshed.charges), 1e-9)
            assertEquals(fixtureTotal * rate, estimatedCost(refreshed.charges), 1e-9)
            assertEquals(rawBefore, HistorySummaryEvidenceCodec.sourceJson(
                db.chargeSummaryDao().get(localCarId, 17)!!.apiEvidence))

            fixture.errorPage = 2
            fixture.requestedPages.clear()
            val partial = loaded(fixture.repository())
            assertEquals(listOf(1, 2), fixture.requestedPages)
            assertEquals("history_partial", partial.chargesSyncError)
            assertEquals(64, partial.charges.size)
            assertEquals(fixtureTotal, energy(partial.charges), 1e-9)

            db.close()
            db = open()
            fixture.db = db
            fixture.online = false
            fixture.requestedPages.clear()
            val offline = loaded(fixture.repository())
            assertTrue(fixture.requestedPages.isEmpty())
            assertEquals("history_cached", offline.chargesSyncError)
            assertEquals(64, offline.charges.size)
            assertEquals(64, db.chargeSummaryDao().count(localCarId))
            assertEquals(fixtureTotal, energy(offline.charges), 1e-9)
            assertEquals(fixtureTotal * rate, estimatedCost(offline.charges), 1e-9)
        } finally { db.close() }
    }

    @Test fun authoritativeUnknownRevokesDetailWhileCorrectedZeroAndInstantMatchedDetailStayKnown() = runBlocking {
        val db = open()
        try {
            val dao = db.chargeSummaryDao()
            val first = charge(1)
            val second = charge(2)
            dao.upsertPreservingEvidence(listOf(first, second).map { it.toSyncSummary(localCarId)!! })
            assertTrue(dao.mergeDetail(localCarId, detail(first, 10.2)) {})
            val shifted = detail(first, 99.0).copy(endDate =
                Instant.parse(first.endDate!!).plusSeconds(1).toString())
            val beforeRejected = dao.get(localCarId, 1)!!.apiEvidence
            assertFalse(dao.mergeDetail(localCarId, shifted) {})
            assertFalse(dao.mergeDetail(localCarId, detail(first, 99.0).copy(source = "telemetry_mqtt")) {})
            assertEquals(beforeRejected, dao.get(localCarId, 1)!!.apiEvidence)

            val plusEight = ZoneOffset.ofHours(8)
            val sameInstant = detail(second, 8.2).copy(
                startDate = Instant.parse(second.startDate!!).atOffset(plusEight).toString(),
                endDate = Instant.parse(second.endDate!!).atOffset(plusEight).toString())
            assertTrue(dao.mergeDetail(localCarId, sameInstant) {})
            val unknown = first.copy(chargeEnergyAdded = null, energyContract = EnergyContract(
                batteryInput = EnergyMetric(quality = "unknown", reason = "provider_revoked",
                    source = first.source, method = "session_counter_delta",
                    measurementPoint = "battery_input", startDate = first.startDate,
                    endDate = first.endDate)))
            val zero = second.copy(chargeEnergyAdded = 0.0)
            dao.upsertPreservingEvidence(listOf(unknown, zero).map { it.toSyncSummary(localCarId)!! })
            assertNull(dao.get(localCarId, 1)!!.toAnalysisChargeData().batteryInputKwh)
            val zeroValue = dao.get(localCarId, 2)!!.toAnalysisChargeData().batteryInputKwh
            assertEquals(0.0, zeroValue!!, 0.0)
            val zeroEstimate = resolveChargeCostFromTotal(null, false, false, null, zeroValue, rate)
            assertEquals(ChargeCostSource.ESTIMATE, zeroEstimate.source)
            assertEquals(0.0, zeroEstimate.cost!!, 0.0)
        } finally { db.close() }
    }

    @Test fun cancelledSecondPageAndInterruptedDetailTransactionDoNotPublishPartialProof() = runBlocking {
        var db = open()
        val fixture = Fixture(db)
        try {
            val dao = db.chargeSummaryDao()
            val row = charge(17)
            dao.upsert(row.toSyncSummary(localCarId)!!)
            val before = dao.get(localCarId, 17)!!.apiEvidence
            var fences = 0
            try {
                dao.mergeDetail(localCarId, detail(row, 24.02)) {
                    fences++
                    if (fences == 2) throw CancellationException("scope changed during write")
                }
                fail("transaction must roll back after the second identity fence")
            } catch (_: CancellationException) { }
            assertEquals(2, fences)
            assertEquals(before, dao.get(localCarId, 17)!!.apiEvidence)

            fixture.cancelPage = 2
            try {
                fixture.repository().load(remoteCarId)
                fail("cancelled page must stop the history load")
            } catch (_: CancellationException) { }
            assertEquals(listOf(1, 2), fixture.requestedPages)
            assertEquals(1, dao.count(localCarId))
            assertEquals(before, dao.get(localCarId, 17)!!.apiEvidence)

            db.close()
            db = open()
            fixture.db = db
            assertEquals(before, db.chargeSummaryDao().get(localCarId, 17)!!.apiEvidence)
        } finally { db.close() }
    }
}
