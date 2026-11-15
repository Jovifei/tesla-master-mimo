package com.matelink.ui.screens.readiness

import com.matelink.data.api.models.*
import com.matelink.data.local.VehicleContext
import com.matelink.data.local.VehicleContextResolver
import com.matelink.data.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

/** Exercises the actual screen consumer: opening diagnostics must stay read-only. */
@OptIn(ExperimentalCoroutinesApi::class)
class FleetTelemetryDiagnosticsConsumerTest {
    private val consumers = mutableListOf<DataReadinessViewModel>()
    private fun newConsumer(source: DataReadinessDataSource, evidence: ReadinessEvidenceSource) =
        DataReadinessViewModel(source, Resolver, Migration, evidence).also { consumers += it }
    private fun stopConsumers() { consumers.forEach { it.onScreenPaused() }; consumers.clear() }

    @Test fun openingAndRefreshingDiagnosticsNeverReadsPairingOrConfigures() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val source = Source()
            val vm = newConsumer(source, Evidence)
            vm.setCarId(1)
            runCurrent()
            vm.refresh()
            runCurrent()
            assertEquals("pairing GET can discover/configure and is not a diagnostic read", 0, source.pairingCalls)
            assertEquals(0, source.configureCalls)
            assertEquals(listOf(1, 1), source.diagnosticCarIds)
            val official = vm.uiState.value.diagnosticFacts.first().officialFleet!!
            assertEquals(true, official.paired)
            assertEquals("waiting_vehicle", vm.uiState.value.pairing?.status)
            assertEquals(DiagnosticResult.UNKNOWN, vm.uiState.value.diagnosticFacts[2].result)
            assertFalse(vm.uiState.value.isLoading)
        } finally { stopConsumers(); runCurrent(); Dispatchers.resetMain() }
    }

    @Test fun backgroundingDropsAnUncancellableDiagnosticLoad() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val release = CompletableDeferred<Unit>()
            val source = Source(release)
            val vm = newConsumer(source, Evidence)
            vm.setCarId(1)
            runCurrent()
            vm.onScreenPaused()
            release.complete(Unit)
            runCurrent()
            assertNull("a backgrounded page must reject a late diagnostic result", vm.uiState.value.data)
            assertTrue(vm.uiState.value.diagnosticFacts.all { it.result == DiagnosticResult.UNKNOWN })
        } finally { stopConsumers(); runCurrent(); Dispatchers.resetMain() }
    }

    @Test fun oldOfficialResultCannotReplaceNewCarOrRefreshGeneration() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            for (switchCar in listOf(true, false)) {
                val release = CompletableDeferred<Unit>()
                var reads = 0
                val source = Source(diagnosticRead = {
                    if (++reads == 1) {
                        withContext(NonCancellable) { release.await() }
                        officialResult(paired = true)
                    } else officialResult(paired = false)
                })
                val vm = newConsumer(source, Evidence)
                vm.setCarId(1)
                runCurrent()
                if (switchCar) vm.setCarId(2) else vm.refresh()
                runCurrent()
                assertEquals(false, vm.uiState.value.diagnosticFacts.first().officialFleet?.paired)
                release.complete(Unit)
                runCurrent()
                assertEquals(false, vm.uiState.value.diagnosticFacts.first().officialFleet?.paired)
                assertEquals(0, source.pairingCalls)
            }
        } finally { stopConsumers(); runCurrent(); Dispatchers.resetMain() }
    }

    @Test fun resumeStartsANewOfficialReadAndRejectsTheBackgroundedResponse() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val release = CompletableDeferred<Unit>()
            var reads = 0
            val source = Source(diagnosticRead = {
                if (++reads == 1) {
                    withContext(NonCancellable) { release.await() }
                    officialResult(paired = true)
                } else officialResult(paired = false)
            })
            val vm = newConsumer(source, Evidence)
            vm.setCarId(1)
            runCurrent()
            vm.onScreenPaused()
            vm.onScreenResumed()
            runCurrent()
            assertEquals(false, vm.uiState.value.diagnosticFacts.first().officialFleet?.paired)
            release.complete(Unit)
            runCurrent()
            assertEquals(false, vm.uiState.value.diagnosticFacts.first().officialFleet?.paired)
            assertEquals(listOf(1, 1), source.diagnosticCarIds)
        } finally { stopConsumers(); runCurrent(); Dispatchers.resetMain() }
    }

    @Test fun accountOrLogoutScopeChangeClearsAndRejectsLateOfficialResult() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val release = CompletableDeferred<Unit>()
            val changes = MutableSharedFlow<Unit>(replay = 1).apply { tryEmit(Unit) }
            var scope = 1
            val evidence = object : ReadinessEvidenceSource {
                override val scopeChanges = changes
                override suspend fun capture(carId: Int): ReadinessEvidenceSession {
                    val captured = scope
                    return object : ReadinessEvidenceSession {
                        override val connectionSource = com.matelink.data.local.HistoryConnectionSource.SELF_HOSTED
                        override suspend fun isCurrent() = captured == scope
                        override fun verifies(readiness: DataReadiness?, car: com.matelink.data.api.models.CarData?) = captured == scope
                        override suspend fun localEvidence(): ReadinessLocalEvidence? = null
                    }
                }
            }
            val source = Source(diagnosticRead = {
                withContext(NonCancellable) { release.await() }
                officialResult()
            })
            val vm = newConsumer(source, evidence)
            vm.setCarId(1)
            runCurrent()
            scope++
            changes.emit(Unit)
            runCurrent()
            release.complete(Unit)
            runCurrent()
            assertNull(vm.uiState.value.data)
            assertTrue(vm.uiState.value.diagnosticFacts.all { it.officialFleet == null && it.result == DiagnosticResult.UNKNOWN })
        } finally { stopConsumers(); runCurrent(); Dispatchers.resetMain() }
    }

    @Test fun unavailableOfficialEndpointIsShownAsUnavailableWithoutSetupOrHealthyDefaults() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val source = Source(diagnosticRead = { ApiResult.Error("not available", 404) })
            val vm = newConsumer(source, Evidence)
            vm.setCarId(1)
            runCurrent()
            val official = vm.uiState.value.diagnosticFacts.first().officialFleet!!
            assertEquals(FleetDiagnosticAvailability.UNAVAILABLE, official.availability)
            assertEquals(404, official.httpStatus)
            assertNull(official.paired)
            assertNull(vm.uiState.value.pairing)
            assertEquals(0, source.pairingCalls)
            assertEquals(0, source.configureCalls)
        } finally { stopConsumers(); runCurrent(); Dispatchers.resetMain() }
    }

    @Test fun manualConfigureRetainsItsExplicitPostAndSetupRead() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val source = Source(configureSucceeds = true)
            val vm = newConsumer(source, Evidence)
            vm.setCarId(1)
            runCurrent()
            assertEquals(0, source.pairingCalls)
            vm.configureTelemetry()
            runCurrent()
            assertEquals(1, source.configureCalls)
            assertEquals(1, source.pairingCalls)
            assertEquals("pairing_required", vm.uiState.value.pairing?.status)
        } finally { stopConsumers(); runCurrent(); Dispatchers.resetMain() }
    }

    @Test fun externalPairingForACannotReadPairingOrConfigureB() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val source = Source()
            val vm = newConsumer(source, Evidence)
            vm.setCarId(1)
            runCurrent()
            vm.reportExternalPairingFlowLaunched()
            vm.setCarId(2)
            runCurrent()
            assertEquals("A consent must not trigger even a pairing read for B", emptyList<Int>(), source.pairingCarIds)
            assertEquals(emptyList<Int>(), source.configureCarIds)
            assertEquals(listOf(1, 2), source.diagnosticCarIds)
        } finally { stopConsumers(); runCurrent(); Dispatchers.resetMain() }
    }

    @Test fun sameRemoteIdInAnotherScopeCannotReuseExternalPairingConsentBeforeScopeEvent() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            var scope = 1
            val evidence = object : ReadinessEvidenceSource {
                override val scopeChanges = flowOf(Unit)
                override suspend fun capture(carId: Int): ReadinessEvidenceSession {
                    val captured = scope
                    return object : ReadinessEvidenceSession {
                        override val connectionSource = com.matelink.data.local.HistoryConnectionSource.SELF_HOSTED
                        override suspend fun isCurrent() = captured == scope
                        override fun verifies(readiness: DataReadiness?, car: com.matelink.data.api.models.CarData?) = captured == scope
                        override suspend fun localEvidence(): ReadinessLocalEvidence? = null
                    }
                }
            }
            val source = Source()
            val vm = newConsumer(source, evidence)
            vm.setCarId(1)
            runCurrent()
            vm.reportExternalPairingFlowLaunched()
            scope++ // Deliberately no flow event: the bound session must also fence the call.
            vm.refresh()
            runCurrent()
            assertEquals(emptyList<Int>(), source.pairingCarIds)
            assertEquals(emptyList<Int>(), source.configureCarIds)
        } finally { stopConsumers(); runCurrent(); Dispatchers.resetMain() }
    }

    @Test fun externalPairingReturnForTheSameCarAndScopeRetainsTheAuthorizedSetup() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val source = Source()
            val vm = newConsumer(source, Evidence)
            vm.setCarId(1)
            runCurrent()
            vm.reportExternalPairingFlowLaunched()
            vm.onScreenPaused()
            vm.onScreenResumed()
            runCurrent()
            assertEquals(listOf(1), source.pairingCarIds)
            assertEquals(listOf(1), source.configureCarIds)
        } finally { stopConsumers(); runCurrent(); Dispatchers.resetMain() }
    }

    @Test fun virtualKeyClickReadsTheUrlThenContinuesOnlyAfterAnActualSameScopeLaunch() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val url = "https://tesla.com/_ak/partner.example.test"
            val source = Source(diagnosticRead = { officialResult(paired = false) }, pairingRead = {
                ApiResult.Success(TelemetryPairingStatus("pairing_required", virtualKeyUrl = url))
            })
            val vm = newConsumer(source, Evidence)
            vm.setCarId(1)
            runCurrent()
            assertEquals("pairing_required", vm.uiState.value.pairing?.status)
            assertNull(vm.uiState.value.pairing?.virtualKeyUrl)
            assertEquals(0, source.pairingCalls)
            val launched = mutableListOf<String>()
            clickVirtualKey(vm) { launched += it; true }
            runCurrent()
            assertEquals(listOf(url), launched)
            assertEquals(listOf(1), source.pairingCarIds)
            assertEquals(0, source.configureCalls)
            vm.onScreenPaused()
            vm.onScreenResumed()
            runCurrent()
            assertEquals(listOf(1), source.configureCarIds)
        } finally { stopConsumers(); runCurrent(); Dispatchers.resetMain() }
    }

    @Test fun virtualKeyFailureDoesNotLaunchOrCreateASetupContinuation() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            for (failure in listOf("read", "url", "launch")) {
                val source = Source(pairingRead = {
                    if (failure == "read") ApiResult.Error("fixture failure") else ApiResult.Success(
                        TelemetryPairingStatus("pairing_required", virtualKeyUrl = if (failure == "url")
                            "https://evil.example.test/_ak/partner.example.test" else "https://tesla.com/_ak/partner.example.test"))
                })
                val vm = newConsumer(source, Evidence)
                vm.setCarId(1)
                runCurrent()
                var launches = 0
                clickVirtualKey(vm) { launches++; false }
                runCurrent()
                assertEquals(if (failure == "launch") 1 else 0, launches)
                assertTrue(vm.uiState.value.pairingLinkUnavailable)
                vm.onScreenPaused()
                vm.onScreenResumed()
                runCurrent()
                assertEquals(1, source.pairingCalls)
                assertEquals(0, source.configureCalls)
            }
        } finally { stopConsumers(); runCurrent(); Dispatchers.resetMain() }
    }

    @Test fun delayedKeyReadCannotLaunchAfterCarScopeOrForegroundChanges() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            for (change in listOf("car", "scope", "background")) {
                val release = CompletableDeferred<Unit>()
                var scope = 1
                val evidence = object : ReadinessEvidenceSource {
                    override val scopeChanges = flowOf(Unit)
                    override suspend fun capture(carId: Int): ReadinessEvidenceSession {
                        val captured = scope
                        return object : ReadinessEvidenceSession {
                            override val connectionSource = com.matelink.data.local.HistoryConnectionSource.SELF_HOSTED
                            override suspend fun isCurrent() = captured == scope
                            override fun verifies(readiness: DataReadiness?, car: com.matelink.data.api.models.CarData?) = captured == scope
                            override suspend fun localEvidence(): ReadinessLocalEvidence? = null
                        }
                    }
                }
                val source = Source(pairingRead = {
                    withContext(NonCancellable) { release.await() }
                    ApiResult.Success(TelemetryPairingStatus("pairing_required",
                        virtualKeyUrl = "https://tesla.com/_ak/partner.example.test"))
                })
                val vm = newConsumer(source, evidence)
                vm.setCarId(1)
                runCurrent()
                var launches = 0
                clickVirtualKey(vm) { launches++; true }
                runCurrent()
                when (change) {
                    "car" -> vm.setCarId(2)
                    "scope" -> scope++
                    "background" -> vm.onScreenPaused()
                }
                release.complete(Unit)
                runCurrent()
                assertEquals(change, 0, launches)
                assertEquals(change, 0, source.configureCalls)
            }
        } finally { stopConsumers(); runCurrent(); Dispatchers.resetMain() }
    }

    private fun clickVirtualKey(vm: DataReadinessViewModel, launch: (String) -> Boolean) {
        val method = vm.javaClass.methods.singleOrNull { it.name == "openExternalPairing" }
        assertNotNull("the existing virtual-key button needs an explicit, scoped setup read action", method)
        method!!.invoke(vm, launch)
    }

    private class Source(
        val release: CompletableDeferred<Unit>? = null,
        val configureSucceeds: Boolean = false,
        val diagnosticRead: suspend (Int) -> ApiResult<FleetTelemetryDiagnostics> = { officialResult() },
        val pairingRead: suspend (Int) -> ApiResult<TelemetryPairingStatus> = {
            ApiResult.Success(TelemetryPairingStatus("pairing_required")) }
    ) : DataReadinessDataSource {
        var pairingCalls = 0
        var configureCalls = 0
        val diagnosticCarIds = mutableListOf<Int>()
        val pairingCarIds = mutableListOf<Int>()
        val configureCarIds = mutableListOf<Int>()
        override suspend fun getDataReadiness(carId: Int): ApiResult<DataReadiness> {
            if (release != null) withContext(NonCancellable) { release.await() }
            return ApiResult.Success(DataReadiness(1, "fixture-car", emptyList()))
        }
        override suspend fun getTelemetryDiagnostics(carId: Int): ApiResult<FleetTelemetryDiagnostics> {
            diagnosticCarIds += carId
            return diagnosticRead(carId)
        }
        override suspend fun getTelemetryPairingStatus(carId: Int): ApiResult<TelemetryPairingStatus> {
            pairingCalls++
            pairingCarIds += carId
            return pairingRead(carId)
        }
        override suspend fun configureTelemetry(carId: Int): ApiResult<TelemetryConfigureResult> {
            configureCalls++
            configureCarIds += carId
            return if (configureSucceeds) ApiResult.Success(TelemetryConfigureResult()) else ApiResult.Error("unexpected write")
        }
        override suspend fun getCar(carId: Int): ApiResult<CarData> = ApiResult.Error("fixture")
        override suspend fun getCarStatus(carId: Int): ApiResult<CarStatusWithUnits> = ApiResult.Error("fixture")
    }

    companion object {
        private fun officialResult(paired: Boolean = true) = ApiResult.Success(FleetTelemetryDiagnostics(
            availability = "available", requests = FleetDiagnosticRequests(
                FleetDiagnosticRequest("ok", 200), FleetDiagnosticRequest("ok", 200), FleetDiagnosticRequest("ok", 200)),
            vehicle = FleetDiagnosticVehicle(paired = paired), configuration = FleetDiagnosticConfiguration(synced = false)))
    }

    private object Evidence : ReadinessEvidenceSource {
        override val scopeChanges = flowOf(Unit)
        override suspend fun capture(carId: Int) = object : ReadinessEvidenceSession {
            override val connectionSource = com.matelink.data.local.HistoryConnectionSource.SELF_HOSTED
            override suspend fun isCurrent() = true
            override fun verifies(readiness: DataReadiness?, car: com.matelink.data.api.models.CarData?) = true
            override suspend fun localEvidence(): ReadinessLocalEvidence? = null
        }
    }
    private object Resolver : VehicleContextResolver {
        override suspend fun resolve(car: CarData): VehicleContext = error("unexpected identity resolution")
        override suspend fun recordExplicitUpgradeOrigin(car: CarData) = error("unexpected identity write")
    }
    private object Migration : LegacyHistoryMigrationService {
        override suspend fun inspect(legacyCarId: Int, currentModel: String?, currentVehicleFingerprint: String?, currentObservedOdometer: Double?): LegacyHistoryMigrationEligibility = error("unexpected migration inspection")
        override suspend fun migrate(legacyCarId: Int, targetHistoryCarId: Int, eligibility: LegacyHistoryMigrationEligibility): LegacyHistoryMigrationResult = error("unexpected migration")
    }
}
