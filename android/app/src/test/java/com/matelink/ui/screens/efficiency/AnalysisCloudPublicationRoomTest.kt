package com.matelink.ui.screens.efficiency

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.matelink.data.api.models.CarData
import com.matelink.data.api.models.DriveData
import com.matelink.data.api.models.DriveOdometerDetails
import com.matelink.data.api.models.HistoryContextData
import com.matelink.data.repository.ApiResult
import com.matelink.data.repository.HistoryReadDependencies
import com.matelink.data.repository.UnifiedHistoryRepository
import com.matelink.data.sync.HistoryResourceFixture
import com.matelink.data.sync.toSyncSummary
import com.matelink.domain.analytics.AnalysisHistoryRepository
import com.matelink.domain.analytics.NoDataReason
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Actual persistent identity + Room + Unified/Analysis repository + efficiency page publication. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class AnalysisCloudPublicationRoomTest {
    @get:Rule val temp = TemporaryFolder()
    private fun drive(id: Int, energy: Double) = DriveData(id,
        startDate = "2026-10-20T10:00:00Z", endDate = "2026-10-20T11:00:00Z",
        source = "teslamate_archive", qualityState = "observed", qualityReason = "api",
        energyConsumedNet = energy, odometerDetails = DriveOdometerDetails(distance = 10.0), lengthUnit = "km")

    private fun vm(f: HistoryResourceFixture, gate: CompletableDeferred<Unit>, started: CompletableDeferred<Unit>,
        uid: () -> String, rows: () -> List<DriveData>): EfficiencyViewModel {
        val history = UnifiedHistoryRepository(HistoryReadDependencies(
            captureScope = { f.vehicleContexts.captureReadScope() },
            getCars = { error("verified history must not use fallback") },
            getHistoryContext = { id -> started.complete(Unit); gate.await()
                ApiResult.Success(HistoryContextData(1, id, uid())) },
            resolveCar = { car, scope -> f.vehicleContexts.resolveVerifiedHistoryCar(car, scope) },
            cachedContext = { id, scope -> f.vehicleContexts.cachedContextForRemote(id, scope) },
            localDrives = { f.db.driveSummaryDao().getAllChronological(it) },
            localCharges = { error("efficiency must not read charges") },
            getDrives = { _, _, _, _ -> ApiResult.Success(rows()) },
            getCharges = { _, _, _, _ -> error("efficiency must not request charges") },
            persistDrives = { f.db.driveSummaryDao().upsertPreservingEvidence(it) },
            persistCharges = { error("efficiency must not write charges") },
            scopeChanges = f.vehicleContexts.historyScopeChanges))
        return EfficiencyViewModel(AnalysisHistoryRepository(history, f.vehicleContexts), f.vehicleContexts)
    }
    private suspend fun await(vm: EfficiencyViewModel, predicate: (EfficiencyUiState) -> Boolean) =
        withContext(Dispatchers.Default.limitedParallelism(1)) { withTimeout(15_000) { vm.uiState.first(predicate) } }
    private suspend fun await(gate: CompletableDeferred<Unit>) =
        withContext(Dispatchers.Default.limitedParallelism(1)) { withTimeout(15_000) { gate.await() } }

    @Test fun roomReopenThenFreshUidProofPublishesNewHistoryForSameMappedVehicle() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val f = HistoryResourceFixture(File(temp.root, "same-vehicle.db"))
        f.initialize(cloud = true)
        f.db.driveSummaryDao().upsert(drive(41, 2.0).toSyncSummary(f.localId(1))!!)
        f.reopen()
        val gate = CompletableDeferred<Unit>(); val started = CompletableDeferred<Unit>()
        val vm = vm(f, gate, started, { "synthetic-vehicle-1" }, { listOf(drive(41, 2.0), drive(42, 4.0)) })
        try {
            vm.load(1); advanceUntilIdle(); await(started)
            val cached = await(vm) { it.driveCount == 1 }
            assertEquals(200.0, cached.avgEfficiencyWhKm!!, 0.001)
            gate.complete(Unit); advanceUntilIdle()
            val fresh = await(vm) { it.driveCount == 2 }
            assertEquals(setOf(41, 42), fresh.tripPositions.map { it.driveId }.toSet())
            assertEquals(300.0, fresh.avgEfficiencyWhKm!!, 0.001)
            assertNull(fresh.error)
            assertEquals(2, f.db.driveSummaryDao().getAllChronological(f.localId(1)).size)
        } finally { gate.complete(Unit); vm.viewModelScope.cancel(); advanceUntilIdle(); f.close(); Dispatchers.resetMain() }
    }

    @Test fun sameNumericIdReboundToAnotherUidRevokesOldPageInsteadOfNormalizingAcrossVehicles() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val f = HistoryResourceFixture(File(temp.root, "rebound-vehicle.db"))
        f.initialize(cloud = true)
        val oldLocalId = f.localId(1)
        f.db.driveSummaryDao().upsert(drive(41, 2.0).toSyncSummary(oldLocalId)!!)
        val gate = CompletableDeferred<Unit>(); val started = CompletableDeferred<Unit>()
        val vm = vm(f, gate, started, { "replacement-vehicle" }, { listOf(drive(99, 3.0)) })
        try {
            vm.load(1); advanceUntilIdle(); await(started)
            await(vm) { it.driveCount == 1 }
            gate.complete(Unit); advanceUntilIdle()
            val invalidated = await(vm) { it.noDataReason == NoDataReason.SOURCE_UNAVAILABLE }
            assertEquals(0, invalidated.driveCount)
            assertNull(invalidated.avgEfficiencyWhKm)
            assertNotEquals(oldLocalId, f.localId(1))
            assertEquals(1, f.db.driveSummaryDao().getAllChronological(oldLocalId).size)
            vm.load(1); advanceUntilIdle()
            val replacement = await(vm) { it.tripPositions.any { row -> row.driveId == 99 } }
            assertEquals(listOf(99), replacement.tripPositions.map { it.driveId })
            assertEquals(300.0, replacement.avgEfficiencyWhKm!!, 0.001)
        } finally { gate.complete(Unit); vm.viewModelScope.cancel(); advanceUntilIdle(); f.close(); Dispatchers.resetMain() }
    }
}
