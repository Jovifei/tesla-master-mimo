package com.matelink.ui.screens.mileage

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.matelink.data.api.models.BatteryHealth
import com.matelink.data.api.models.ChargeData
import com.matelink.data.api.models.DriveData
import com.matelink.data.api.models.DriveOdometerDetails
import com.matelink.data.api.models.DriveRange
import com.matelink.data.api.models.HistoryContextData
import com.matelink.data.local.ChargeCostOverrideStore
import com.matelink.data.repository.ApiResult
import com.matelink.data.repository.CarStatusWithUnits
import com.matelink.data.repository.HistoryReadDependencies
import com.matelink.data.repository.UnifiedHistoryRepository
import com.matelink.data.repository.VerifiedHistoryReadContext
import com.matelink.data.sync.HistoryResourceFixture
import com.matelink.data.sync.toSyncSummary
import com.matelink.domain.analytics.AnalysisHistoryRepository
import com.matelink.domain.analytics.HistoryFreshness
import com.matelink.domain.analytics.captureAnalysisPageIdentity
import com.matelink.ui.screens.range.RangePageIdentity
import com.matelink.ui.screens.range.RangePageSource
import com.matelink.ui.screens.range.RangeViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDate
import java.time.YearMonth

/** Disk Room -> production history repositories -> real page VMs, with remote identity pending. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class MileageRoomPersistenceTest {
    @get:Rule val temp = TemporaryFolder()

    // Room uses real executors. Unconfined resumes page consumers when their actual DAO reads finish.
    @Before fun setMain() { Dispatchers.setMain(Dispatchers.Unconfined) }
    @After fun resetMain() { Dispatchers.resetMain() }

    private fun charge(id: Int, day: Int, cost: Double = 12.0) = ChargeData(
        id, startDate = "2026-10-${day}T10:00:00Z", endDate = "2026-10-${day}T11:00:00Z",
        cost = cost, currencyCode = "CNY", source = "teslamate_archive",
        sourceInstanceId = "synthetic-archive", sourceRecordId = "charge-$id",
        qualityState = "observed", qualityReason = "api"
    )

    private fun drive(id: Int, day: Int) = DriveData(
        id, startDate = "2026-10-${day}T08:00:00Z", endDate = "2026-10-${day}T09:00:00Z",
        odometerDetails = DriveOdometerDetails(distance = 20.0),
        rangeRated = DriveRange(startRange = 300.0, endRange = 275.0), lengthUnit = "km",
        source = "teslamate_archive", qualityState = "observed", qualityReason = "api"
    )

    private suspend fun fixture(name: String, charges: List<ChargeData>): HistoryResourceFixture {
        val f = HistoryResourceFixture(File(temp.root, "$name.db"))
        f.initialize(cloud = true)
        f.settings.saveCurrency("CNY")
        val localId = f.localId(1)
        f.db.driveSummaryDao().upsertAll(listOf(drive(41, 14), drive(42, 15)).map {
            requireNotNull(it.toSyncSummary(localId))
        })
        f.db.chargeSummaryDao().upsertAll(charges.map { requireNotNull(it.toSyncSummary(localId)) })
        return f
    }

    private class PendingHistory(val fixture: HistoryResourceFixture) {
        val started = CompletableDeferred<Unit>()
        val remote = CompletableDeferred<ApiResult<HistoryContextData>>()
        val repository = UnifiedHistoryRepository(HistoryReadDependencies(
            captureScope = { fixture.vehicleContexts.captureReadScope() },
            getCars = { error("Cloud cached read must not discover cars") },
            getHistoryContext = { started.complete(Unit); remote.await() },
            resolveCar = { car, scope -> fixture.vehicleContexts.resolveVerifiedHistoryCar(car, scope) },
            cachedContext = { id, scope -> fixture.vehicleContexts.cachedContextForRemote(id, scope) },
            localDrives = { fixture.db.driveSummaryDao().getAllChronological(it) },
            localCharges = { fixture.db.chargeSummaryDao().getAllForCar(it) },
            getDrives = { _, _, _, _ -> error("Pending identity must not fetch drives") },
            getCharges = { _, _, _, _ -> error("Pending identity must not fetch charges") },
            persistDrives = { fixture.db.driveSummaryDao().upsertPreservingEvidence(it) },
            persistCharges = { fixture.db.chargeSummaryDao().upsertPreservingEvidence(it) },
            scopeChanges = fixture.vehicleContexts.historyScopeChanges
        ))

        fun mileage(): MileageViewModel {
            val overrides = ChargeCostOverrideStore(fixture.db.chargeCostOverrideDao(), fixture.settings)
            return MileageViewModel(object : MileagePageSource {
                override val scopeChanges = fixture.vehicleContexts.historyScopeChanges.map { Unit }
                override suspend fun identity(carId: Int) = fixture.vehicleContexts.captureAnalysisPageIdentity(carId)
                override suspend fun loadCached(carId: Int) = repository.loadCached(carId)
                override suspend fun history(carId: Int) = repository.load(carId)
                override suspend fun status(carId: Int): ApiResult<CarStatusWithUnits> = awaitCancellation()
                override suspend fun isContextCurrent(proof: VerifiedHistoryReadContext) = repository.isContextCurrent(proof)
                override fun isContextCurrentNow(proof: VerifiedHistoryReadContext) =
                    fixture.vehicleContexts.isHistoryContextCurrentNow(proof.scope, proof.context)
                override suspend fun currencyCode() = fixture.settings.settings.first().currencyCode
                override suspend fun manualRecords() = overrides.getAllRecords()
            }, Dispatchers.Unconfined)
        }

        fun range(): RangeViewModel {
            val analysis = AnalysisHistoryRepository(repository, fixture.vehicleContexts)
            return RangeViewModel(object : RangePageSource {
                override val scopeChanges = fixture.vehicleContexts.historyScopeChanges.map { Unit }
                override suspend fun identity(carId: Int): RangePageIdentity {
                    val scope = fixture.vehicleContexts.captureReadScope()
                    val context = requireNotNull(fixture.vehicleContexts.cachedContextForRemote(carId, scope))
                    return RangePageIdentity(context) {
                        fixture.vehicleContexts.isHistoryContextCurrentNow(scope, context)
                    }
                }
                override suspend fun loadCached(carId: Int) = analysis.loadCached(carId)
                override suspend fun history(carId: Int) = analysis.load(carId)
                override suspend fun health(carId: Int): ApiResult<BatteryHealth> = awaitCancellation()
                override suspend fun status(carId: Int): ApiResult<CarStatusWithUnits> = awaitCancellation()
            })
        }

        suspend fun assertRemotePending() {
            withTimeout(15_000) { started.await() }
            assertFalse("Cached projection must publish while real repository load is pending", remote.isCompleted)
            assertTrue("Room-first path must not call the HTTP fixture", fixture.calls.isEmpty())
        }
    }

    private suspend fun daily(vm: MileageViewModel): List<DailyMileage> {
        vm.selectYear(2026)
        withTimeout(15_000) { vm.uiState.first { it.selectedYear == 2026 && it.monthlyData.isNotEmpty() } }
        vm.selectMonth(YearMonth.of(2026, 10))
        return withTimeout(15_000) { vm.uiState.first { it.dailyData.size == 2 } }.dailyData
    }

    @Test fun reopenedRoomPublishesPositiveAndZeroManualMileageCostsBeforeRemoteIdentityReturns() = runBlocking {
        val paid = charge(61, 14)
        val free = charge(62, 15)
        val f = fixture("mileage-manual-reopen", listOf(paid, free))
        var vm: MileageViewModel? = null
        try {
            val store = ChargeCostOverrideStore(f.db.chargeCostOverrideDao(), f.settings)
            assertTrue(store.saveBound(f.localId(1), paid, 7.5, "CNY", 0))
            assertTrue(store.saveBound(f.localId(1), free, 0.0, "CNY", 0))
            f.reopen()
            assertEquals(2, f.db.chargeCostOverrideDao().getAll().size)
            val pending = PendingHistory(f)
            val page = pending.mileage().also { vm = it }
            page.setCarId(1)
            val state = withTimeout(15_000) { page.uiState.first { !it.isLoading && it.allCharges.size == 2 } }
            pending.assertRemotePending()
            assertEquals(HistoryFreshness.STALE, state.historyFreshness)
            assertEquals(listOf(41, 42), state.allDrives.map { it.driveId }.sorted())
            assertEquals(40.0, state.totalLifetimeDistance, 0.0)
            assertEquals(7.5, state.totalLifetimeEnergyCost!!, 0.0)
            assertNull(state.error)
            val days = daily(page).associateBy { it.date }
            assertEquals(7.5, days.getValue(LocalDate.of(2026, 10, 14)).totalEnergyCost!!, 0.0)
            assertEquals(0.0, days.getValue(LocalDate.of(2026, 10, 15)).totalEnergyCost!!, 0.0)
        } finally {
            vm?.viewModelScope?.cancel()
            f.close()
        }
    }

    @Test fun reopenedRoomDoesNotApplyAnotherCarsOrMismatchedSourcesManualMileageCost() = runBlocking {
        val foreignCar = charge(61, 14)
        val mismatchedSource = charge(62, 15)
        val f = fixture("mileage-foreign-manual-reopen", listOf(foreignCar, mismatchedSource))
        var vm: MileageViewModel? = null
        try {
            val store = ChargeCostOverrideStore(f.db.chargeCostOverrideDao(), f.settings)
            assertTrue(store.saveBound(f.localId(2), foreignCar, 99.0, "CNY", 0))
            assertTrue(store.saveBound(f.localId(1), mismatchedSource, 88.0, "CNY", 0))
            val invalid = f.db.chargeCostOverrideDao().getAll().single { it.carId == f.localId(1) }
            // Keep the matching session key so qualification must reject the foreign source metadata.
            f.db.chargeCostOverrideDao().upsert(invalid.copy(sourceKind = "foreign_provider"))
            f.reopen()
            val pending = PendingHistory(f)
            val page = pending.mileage().also { vm = it }
            page.setCarId(1)
            withTimeout(15_000) { page.uiState.first { !it.isLoading && it.allCharges.size == 2 } }
            pending.assertRemotePending()
            val days = daily(page).associateBy { it.date }
            assertEquals(12.0, days.getValue(LocalDate.of(2026, 10, 14)).totalEnergyCost!!, 0.0)
            assertNull(days.getValue(LocalDate.of(2026, 10, 15)).totalEnergyCost)
            assertEquals(12.0, page.uiState.value.totalLifetimeEnergyCost!!, 0.0)
            assertEquals(HistoryFreshness.STALE, page.uiState.value.historyFreshness)
        } finally {
            vm?.viewModelScope?.cancel()
            f.close()
        }
    }

    @Test fun reopenedRoomPublishesRangeTripsThroughAnalysisRepositoryWhileRemoteRemainsPending() = runBlocking {
        val f = fixture("range-history-reopen", listOf(charge(61, 14)))
        var vm: RangeViewModel? = null
        try {
            f.reopen()
            val pending = PendingHistory(f)
            val page = pending.range().also { vm = it }
            page.setCarId(1)
            val state = withTimeout(15_000) { page.uiState.first { !it.isLoading && it.tripCount == 2 } }
            pending.assertRemotePending()
            assertEquals(HistoryFreshness.STALE, state.historyFreshness)
            assertEquals(listOf(41, 42), state.trips.map { it.driveId }.sorted())
            assertEquals(40.0, state.totalDistanceKm, 0.0)
            assertEquals(20.0, state.avgDeviationPercent!!, 0.0)
            assertNull(state.error)
            assertNull(state.noDataReason)
        } finally {
            vm?.viewModelScope?.cancel()
            f.close()
        }
    }
}
