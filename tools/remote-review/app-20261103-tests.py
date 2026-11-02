from pathlib import Path
r = Path.cwd()
def put(p, text):
    q=r/p; q.parent.mkdir(parents=True,exist_ok=True); q.write_text(text)
def replace(p, old, new, n=1):
    s=(r/p).read_text(); assert s.count(old)==n, (p,old[:80],s.count(old)); put(p,s.replace(old,new))
t='android/app/src/test/java/com/matelink/domain/history/HistoryRefreshIntegrationContractTest.kt'
replace(t,'assertTrue(request.contains("historyRepository.load(id, startDateStr, endDateStr)"))','''val family = if (kind.startsWith("drives")) "loadDrives" else "loadCharges"
            assertTrue(request.contains("historyRepository.$family(id, startDateStr, endDateStr)"))''')
replace(t,'assertTrue(repository.contains("if (canReadHistory)"))','''assertTrue(repository.contains("if (canReadHistory && families.drives)"))
        assertTrue(repository.contains("if (canReadHistory && families.charges)"))''')
t='android/app/src/test/java/com/matelink/ui/screens/cost/CostRoomFirstViewModelTest.kt'
replace(t,'import com.matelink.domain.analytics.toAnalysisChargeData','''import com.matelink.domain.analytics.toAnalysisChargeData
import com.matelink.domain.analytics.toRawAnalysisChargeData''')
replace(t,'        persistDrives: suspend () -> Unit = {},','        persistCharges: suspend () -> Unit = {},')
replace(t,'        onOnlineIdentity: () -> Unit = {}','''        onOnlineIdentity: () -> Unit = {},
        remoteCharges: (suspend () -> List<ChargeData>)? = null''')
replace(t,'''            getCharges = { _, _, _, _ -> ApiResult.Success(emptyList()) },
            persistDrives = { persistDrives() },
            persistCharges = {},''','''            getCharges = { _, _, _, _ -> ApiResult.Success(remoteCharges?.invoke() ?: emptyList()) },
            persistDrives = {},
            persistCharges = { persistCharges() },''')
replace(t,'}, persistDrives = {','}, persistCharges = {')
replace(t,'''            reachedOnline.complete(Unit)
        })
        try {
            vm.load(1)
            advanceUntilIdle()
            awaitGate(reachedOnline)
            assertEquals(12.0, cachedAtOnline!!, 0.0)''','''            reachedOnline.complete(Unit)
        }, remoteCharges = {
            f.db.chargeSummaryDao().getAllForCar(f.localId(1)).map { it.toRawAnalysisChargeData() }
        })
        try {
            vm.load(1)
            advanceUntilIdle()
            awaitGate(reachedOnline)
            assertEquals(12.0, cachedAtOnline!!, 0.0)''')
s=(r/t).read_text().rstrip(); assert s.endswith('}')
put(t,s[:-1]+'''
    @Test fun partialCounterRoomReceiptPublishesSeparateCostWithoutInventingWholeEnergy() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val f = fixture("cost-partial-observed")
        f.settings.saveDefaultChargePrice(1.5)
        val start = "2026-10-20T12:00:00Z"
        val end = "2026-10-20T13:00:00Z"
        val row = ChargeData(65, startDate = start, endDate = end, source = "telemetry_mqtt",
            qualityState = "incomplete", qualityReason = "partial_or_missing_charge_counter",
            energyContract = com.matelink.data.api.models.EnergyContract(batteryInput =
                com.matelink.data.api.models.EnergyMetric(method = "session_counter_delta",
                    measurementPoint = "battery_input", source = "telemetry_mqtt", sourceField = "DCChargingEnergyIn",
                    quality = "unknown", reason = "partial_counter_window", startDate = start, endDate = end,
                    observedStartAt = "2026-10-20T12:05:00Z", observedEndAt = "2026-10-20T12:55:00Z",
                    timeBasis = "collector_received_at", coverageKind = "endpoints",
                    coverageRatio = 5.0 / 6.0, coveredEnergyKwh = 10.0)))
        f.db.chargeSummaryDao().upsert(requireNotNull(row.toSyncSummary(f.localId(1))))
        val vm = viewModel(f, { "synthetic-vehicle-1" })
        try {
            vm.load(1); advanceUntilIdle()
            val state = awaitState(vm) { !it.loading && it.partialEstimatedCoverage == 1 }
            assertEquals(15.0, state.partialEstimatedCost!!, 0.0)
            assertEquals(12.0, state.totalCost!!, 0.0)
            assertEquals(2, state.totalCharges)
            assertNull(state.totalEnergy)
            assertNull(state.pairedUnitCost)
            assertEquals(0, state.estimatedCoverage)
            assertNull(state.estimatedCost)
        } finally { vm.viewModelScope.cancel(); advanceUntilIdle(); f.close(); Dispatchers.resetMain() }
    }

    @Test fun confirmedZeroEditReprojectsOpenCostPageWithoutReloginOrRemoteReload() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val f = fixture("cost-live-room-edit")
        var onlineCalls = 0
        val vm = viewModel(f, { "synthetic-vehicle-1" }, onOnlineIdentity = { onlineCalls++ })
        try {
            vm.load(1); advanceUntilIdle()
            awaitState(vm) { !it.loading && it.totalCost == 12.0 }
            val localId = f.localId(1)
            val row = f.db.chargeSummaryDao().get(localId, 64)!!.toAnalysisChargeData()
            val before = onlineCalls
            val store = ChargeCostOverrideStore(f.db.chargeCostOverrideDao(), f.settings)
            org.junit.Assert.assertTrue(store.saveBound(localId, row, 0.0, "CNY", 0))
            advanceUntilIdle()
            val updated = awaitState(vm) { it.totalCost == 0.0 && it.manualCostCount == 1 }
            assertEquals(0.0, updated.totalCost!!, 0.0)
            assertEquals(before, onlineCalls)
            assertEquals(1, f.db.chargeSummaryDao().getAllForCar(localId).size)
        } finally { vm.viewModelScope.cancel(); advanceUntilIdle(); f.close(); Dispatchers.resetMain() }
    }
}
''')
put('android/app/src/test/java/com/matelink/data/repository/HistoryFamilyReadTest.kt','''package com.matelink.data.repository

import com.matelink.data.api.models.*
import com.matelink.data.local.*
import com.matelink.data.local.entity.ChargeSummary
import com.matelink.data.local.entity.DriveSummary
import com.matelink.data.sync.toSyncSummary
import com.matelink.domain.analytics.HistoryFreshness
import com.matelink.domain.analytics.analysisFreshness
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Exercises the actual repository orchestration, including forbidden opposite-family I/O. */
class HistoryFamilyReadTest {
    private class Fixture {
        var scope = HistoryReadScope(HistoryConnectionSource.CLOUD, "cloud", "account-a", "https://a.invalid")
        val context = VehicleContext(7, "verified-a", -7, HistoryConnectionSource.CLOUD, "cloud")
        val drive = DriveData(1, startDate = "2026-10-01T00:00:00Z", endDate = "2026-10-01T01:00:00Z",
            source = "teslamate_archive", qualityState = "observed", qualityReason = "api")
        val charge = ChargeData(2, startDate = "2026-10-01T02:00:00Z", endDate = "2026-10-01T03:00:00Z",
            source = "teslamate_archive", qualityState = "observed", qualityReason = "api", chargeEnergyAdded = 0.0)
        var localDrives: List<DriveSummary> = emptyList()
        var localCharges: List<ChargeSummary> = emptyList()
        var requestedDrives = 0; var requestedCharges = 0
        var driveReads = 0; var chargeReads = 0; var driveWrites = 0; var chargeWrites = 0
        var drives: suspend () -> ApiResult<List<DriveData>> = { ApiResult.Success(listOf(drive)) }
        var charges: suspend () -> ApiResult<List<ChargeData>> = { ApiResult.Success(listOf(charge)) }
        fun repository() = UnifiedHistoryRepository(HistoryReadDependencies(
            captureScope = { scope }, getCars = { error("unneeded cars fallback") },
            getHistoryContext = { ApiResult.Success(HistoryContextData(1, 7, "vehicle-a")) },
            resolveCar = { _, _ -> context }, cachedContext = { _, _ -> context },
            localDrives = { driveReads++; localDrives }, localCharges = { chargeReads++; localCharges },
            getDrives = { _, _, _, _ -> requestedDrives++; drives() },
            getCharges = { _, _, _, _ -> requestedCharges++; charges() },
            persistDrives = { driveWrites++ }, persistCharges = { chargeWrites++ }))
    }

    @Test fun chargePageNeverWaitsForOrWritesDrives() = runTest {
        val f = Fixture(); f.drives = { error("unrelated drive provider must not run") }
        val result = (f.repository().loadCharges(7) as ApiResult.Success).data
        assertEquals(listOf(2), result.charges.map { it.chargeId })
        assertEquals(0.0, result.charges.single().batteryInputKwh!!, 0.0)
        assertEquals(0, f.requestedDrives); assertEquals(0, f.driveReads); assertEquals(0, f.driveWrites)
        assertEquals(1, f.requestedCharges); assertEquals(1, f.chargeWrites)
        assertEquals(HistoryFreshness.FRESH, result.analysisFreshness(HistoryFamilies.CHARGES))
        assertEquals(HistoryFreshness.STALE, result.analysisFreshness(HistoryFamilies.ALL))
    }

    @Test fun drivePageNeverWaitsForOrWritesCharges() = runTest {
        val f = Fixture(); f.charges = { error("unrelated charge provider must not run") }
        val result = (f.repository().loadDrives(7) as ApiResult.Success).data
        assertEquals(listOf(1), result.drives.map { it.driveId })
        assertEquals(0, f.requestedCharges); assertEquals(0, f.chargeReads); assertEquals(0, f.chargeWrites)
        assertEquals(1, f.driveWrites)
        assertEquals(HistoryFreshness.FRESH, result.analysisFreshness(HistoryFamilies.DRIVES))
    }

    @Test fun successfulEmptyResponsePreservesArchiveButCannotMarkRetainedRecordsFresh() = runTest {
        val f = Fixture(); f.localCharges = listOf(f.charge.toSyncSummary(-7)!!)
        f.charges = { ApiResult.Success(emptyList()) }
        val result = (f.repository().loadCharges(7) as ApiResult.Success).data
        assertEquals(listOf(2), result.charges.map { it.chargeId })
        assertTrue(result.chargesFromRemote)
        assertTrue(result.chargesIncludeCachedEvidence)
        assertEquals(HistoryFreshness.STALE, result.analysisFreshness(HistoryFamilies.CHARGES))
        assertEquals(0, f.driveReads)
    }

    @Test fun cachedChargeReadDoesNotTouchDriveDaoOrProviderAndNeverClaimsFresh() = runTest {
        val f = Fixture(); f.localCharges = listOf(f.charge.toSyncSummary(-7)!!)
        val result = (f.repository().loadCached(7, HistoryFamilies.CHARGES) as ApiResult.Success).data
        assertEquals(1, result.charges.size)
        assertEquals(0, f.requestedCharges); assertEquals(0, f.requestedDrives); assertEquals(0, f.driveReads)
        assertEquals(0, f.chargeWrites)
        assertEquals(HistoryFreshness.STALE, result.analysisFreshness(HistoryFamilies.CHARGES))
    }

    @Test fun identityChangeDuringChargeReadCannotPublishOrPersistTheResponse() = runTest {
        val f = Fixture(); f.charges = {
            f.scope = f.scope.copy(accountNamespace = "account-b")
            ApiResult.Success(listOf(f.charge))
        }
        assertTrue(f.repository().loadCharges(7) is ApiResult.Error)
        assertEquals(0, f.chargeWrites)
        assertEquals(0, f.driveWrites)
    }

    @Test fun cancellationIsNotConvertedIntoAnEmptySuccessfulRefresh() = runTest {
        val f = Fixture(); f.charges = { throw CancellationException("cancelled") }
        var cancelled = false
        try { f.repository().loadCharges(7) } catch (_: CancellationException) { cancelled = true }
        assertTrue(cancelled); assertEquals(0, f.chargeWrites); assertEquals(0, f.driveWrites)
    }
}
''')
put('android/app/src/androidTest/java/com/matelink/ui/screens/cost/PartialCostSummaryAndroidTest.kt','''package com.matelink.ui.screens.cost

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.matelink.R
import com.matelink.ui.theme.MateLinkTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PartialCostSummaryAndroidTest {
    @get:Rule val compose = createComposeRule()
    @Test fun partialZeroIsVisibleButNeverLabeledAsAWholeBillOrWholeEnergy() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.setContent {
            MateLinkTheme {
                Column(Modifier.width(320.dp)) {
                    SummarySection(CostUiState(loading = false, totalCharges = 1,
                        partialEstimatedCost = 0.0, partialEstimatedCoverage = 1), "¥")
                }
            }
        }
        compose.onNodeWithText(context.getString(R.string.cost_partial_observation_estimate)).assertIsDisplayed()
        compose.onNodeWithText("≈", substring = true).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.cost_recorded_manual_total)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.cost_energy_added)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.cost_no_paired_values)).assertDoesNotExist()
    }
}
''')
put('docs/REMOTE-AUDIT-APP-20261103.md','''# App review implementation — 2026-11-03

Repository: `Jovifei/tesla-master-mimo`. Branch: `codex/app-foundation-ui-integrate-20261025`.
Baseline: `10e501541f33f8856b787b24833e14677a02792d`. Candidate: 2.1.65 / 84.

## Wired changes

Cost consumes qualified partial charging observations through actual Room, analysis, ViewModel and Compose consumers. The separate observed-part estimate preserves zero, displays approximation and never enters whole bills, whole energy or unit-price denominators. Manual fees are observed locally and reproject the open cost page without reauthentication or full history downloads.

Drive/efficiency/range/battery consumers read only drives, and charge/cost consumers read only charges. Unrelated endpoints and DAO operations are not invoked. The combined analysis path, identity/epoch fences, cancellation, raw receipts and evidence-preserving merges remain. Successful empty responses retain the archive but cannot certify retained receipts as fresh.

Battery health/status publishes before history completes. Annual aggregates are read in the existing Room transaction after the awaited refresh. Selected-tab taps do not rebuild their route, while fresh arguments still protect vehicle changes. Parking title and observed-part estimate explanations are localized. Existing page geometry, app ID, session storage and Room schema remain.

## Required verification

HistoryFamilyReadTest tests real repository reads, forbidden unrelated I/O, offline cache, empty-response retention, cancellation and scope changes. CostRoomFirstViewModelTest traverses real Room and the real ViewModel, including partial estimates and confirmed-zero reactive edits. PartialCostSummaryAndroidTest renders the actual Compose summary at 320dp. All existing Debug/Release, lint, R8, API35, Mini, Go/isolated PostgreSQL/race/TLS/Web checks remain mandatory at the final fixed SHA. Counts must be read from actual XML, not inferred from this document.

## Independent local handoff

Fetch final fixed SHA into a separate clean worktree and verify SHA/tree/parent/diff. Never reset/clean the original E-drive workspace or overwrite Owner files. Rebuild Debug/Release, both lint and R8; run isolated emulator tests. Compare persistent history, fees, settings and valid sessions during a separately authorized non-destructive upgrade. Signing/installation, production deployment, DDL and historical repair remain separate gates.

This source stage does not claim full encrypted-library restore, every-screen native Mini pixel parity, natural Fleet notifications/TPMS acceptance, or production WeChat configuration. It does not delete history, change credentials, migrate production data, or deploy services. Full-library backup and real-device/native visual acceptance require their own completed implementation/evidence; CI cannot substitute for those gates.
''')
print('Actual repository, Room/VM and API35 consumer regressions prepared.')
