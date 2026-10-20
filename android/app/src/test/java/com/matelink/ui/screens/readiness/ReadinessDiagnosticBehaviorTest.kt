package com.matelink.ui.screens.readiness

import com.matelink.data.api.models.*
import com.matelink.data.local.VehicleContext
import com.matelink.data.local.VehicleContextResolver
import com.matelink.data.repository.*
import com.squareup.moshi.Moshi
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReadinessDiagnosticBehaviorTest {
    private val ready = DataReadiness(1, "synthetic-car-a", listOf(
        DataReadinessItem("live_status", "available", "fleet_api", "2026-10-10T00:00:00Z")))

    @Test fun sharedJsonFixturesDriveTheActualResolver() {
        val fixture = File("../../fixtures/safety-stage-v1.json")
        val moshi = Moshi.Builder().build()
        val root = moshi.adapter(Map::class.java).fromJson(fixture.readText())!!
        val adapter = moshi.adapter(DataReadiness::class.java)
        val cases = root["readiness_cases"] as List<*>
        assertTrue(cases.size >= 8)
        for (raw in cases) {
            val case = raw as Map<*, *>
            val data = adapter.fromJsonValue(case["readiness"])!!
            val expected = case["expected"] as Map<*, *>
            val facts = resolveReadinessDiagnostics(data, true, true).associateBy { it.step }
            for ((field, step) in listOf("receive" to DiagnosticStep.RECEIVE,
                "qualification" to DiagnosticStep.QUALIFICATION, "history" to DiagnosticStep.HISTORY)) {
                assertEquals("${case["id"]}:$field", expected[field], facts.getValue(step).result.name)
            }
        }
    }

    @Test fun proofIsIndependentAndPermissionNeverMeansDelivery() {
        val local = ReadinessLocalEvidence(latestVerifiedTpms = 10L, notificationsEnabled = true, readAt = 20L)
        val facts = resolveReadinessDiagnostics(ready, true, true, local).associateBy { it.step }
        assertEquals(DiagnosticResult.PASS, facts.getValue(DiagnosticStep.HISTORY).result)
        assertEquals(DiagnosticResult.UNKNOWN, facts.getValue(DiagnosticStep.DISPLAY).result)
        val handled = resolveReadinessDiagnostics(ready, true, true, local.copy(handledApp = 1))
        assertEquals(DiagnosticResult.PASS, handled.last().result)
        assertEquals("delivery:true:0:0:1", handled.last().detail)
        assertTrue(resolveReadinessDiagnostics(ready, false, true, local).all { it.result == DiagnosticResult.UNKNOWN })
        assertTrue(resolveReadinessDiagnostics(ready, true, false, local).all { it.result == DiagnosticResult.UNKNOWN })
    }

    @Test fun emptyHistoryAndFutureTpmsDoNotQualify() {
        val facts = resolveReadinessDiagnostics(ready, true, true,
            ReadinessLocalEvidence(latestVerifiedTpms = 21L, readAt = 20L,
                persistedDrives = 0, persistedCharges = 0)).associateBy { it.step }
        assertEquals(DiagnosticResult.UNKNOWN, facts.getValue(DiagnosticStep.QUALIFICATION).result)
        assertEquals(DiagnosticResult.WAITING, facts.getValue(DiagnosticStep.HISTORY).result)
    }

    @Test fun offlineQualifiedHistoryRetainsItsOwnScopeWithoutClaimingApiAuthorization() {
        val local = ReadinessLocalEvidence(qualifiedDrives = 1, scopeVerified = true, persistedDrives = 1, persistedCharges = 0)
        val offline = resolveReadinessDiagnostics(null, true, false, local).associateBy { it.step }
        assertEquals(DiagnosticResult.UNKNOWN, offline.getValue(DiagnosticStep.AUTHORIZATION).result)
        assertEquals(DiagnosticResult.PASS, offline.getValue(DiagnosticStep.HISTORY).result)
        val mismatch = resolveReadinessDiagnostics(ready, true, false, local)
        assertTrue(mismatch.all { it.result == DiagnosticResult.UNKNOWN })
    }

    @Test fun persistedUnknownEnergyIsHistoryEvidenceWithoutQualificationPromotion() {
        val facts = resolveReadinessDiagnostics(ready, true, true,
            ReadinessLocalEvidence(persistedDrives = 5, persistedCharges = 2)).associateBy { it.step }
        assertEquals(DiagnosticResult.PASS, facts.getValue(DiagnosticStep.HISTORY).result)
        assertEquals(DiagnosticResult.UNKNOWN, facts.getValue(DiagnosticStep.QUALIFICATION).result)
        assertEquals("history:0:5:2", facts.getValue(DiagnosticStep.HISTORY).detail)
    }

    @Test fun explicitPermissionBlockerWinsDespiteValidVehicleIdentity() {
        val data = ready.copy(items = ready.items + DataReadinessItem("tpms", "permission_required", "fleet_api"))
        val facts = resolveReadinessDiagnostics(data, true, true).associateBy { it.step }
        assertEquals(DiagnosticResult.WAITING, facts.getValue(DiagnosticStep.AUTHORIZATION).result)
        assertEquals("permission_required", facts.getValue(DiagnosticStep.AUTHORIZATION).detail)
        assertEquals(DiagnosticResult.PASS, facts.getValue(DiagnosticStep.VEHICLE).result)
        assertEquals(DiagnosticResult.WAITING, withDiagnosticPermissionBlocker(
            resolveReadinessDiagnostics(ready, true, true)).first().result)
    }

    @Test fun oldReceiptIsHistoricalEvidenceAndUnknownSourceCannotProveReceipt() {
        val facts = resolveReadinessDiagnostics(ready, true, true,
            ReadinessLocalEvidence(readAt = java.time.Instant.parse("2026-10-15T00:00:00Z").toEpochMilli()))
        assertTrue(facts.single { it.step == DiagnosticStep.RECEIVE }.olderReceipt)
        val unknownSource = ready.copy(items = ready.items.map { it.copy(source = "future_unknown_provider") })
        assertEquals(DiagnosticResult.UNKNOWN, resolveReadinessDiagnostics(unknownSource, true, true)
            .single { it.step == DiagnosticStep.RECEIVE }.result)
    }

    @Test fun viewModelPublishesRealResolverEvidenceAndNoDiagnosticWrite() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val source = Source(ready)
            val evidence = Evidence()
            val vm = DataReadinessViewModel(source, Resolver, Migration, evidence)
            vm.setCarId(1)
            runCurrent()
            assertFalse(vm.uiState.value.isLoading)
            assertEquals(DiagnosticResult.PASS, vm.uiState.value.diagnosticFacts[4].result)
            assertEquals(0, source.configureCalls)
            assertEquals(1, source.readinessCalls)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun openingDiagnosticWithPairingRequiredDoesNotConfigureVehicle() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val source = Source(ready, pairingStatus = TelemetryPairingStatus(
                status = "pairing_required", configSynced = false, updatedAt = null))
            val vm = DataReadinessViewModel(source, Resolver, Migration, Evidence())
            vm.setCarId(1)
            runCurrent()
            assertFalse(vm.uiState.value.isLoading)
            assertEquals(0, source.configureCalls)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun verifiedVehicleResolutionRefreshesDiagnosticIdentityEvidence() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            var captures = 0
            val evidence = object : ReadinessEvidenceSource {
                override val scopeChanges = flowOf(Unit)
                override suspend fun capture(carId: Int): ReadinessEvidenceSession {
                    captures++
                    val verified = captures >= 2
                    return object : ReadinessEvidenceSession {
                        override suspend fun isCurrent() = true
                        override fun verifies(readiness: DataReadiness?) = verified
                        override suspend fun localEvidence() = ReadinessLocalEvidence(scopeVerified = verified)
                    }
                }
            }
            val resolver = object : VehicleContextResolver {
                override suspend fun resolve(car: CarData) = VehicleContext(
                    car.carId, "verified", 31,
                    com.matelink.data.local.HistoryConnectionSource.CLOUD, "cloud")
                override suspend fun recordExplicitUpgradeOrigin(car: CarData) = false
            }
            val migration = object : LegacyHistoryMigrationService {
                override suspend fun inspect(legacyCarId: Int, currentModel: String?,
                    currentVehicleFingerprint: String?, currentObservedOdometer: Double?) =
                    LegacyHistoryMigrationEligibility(false, 0, 0, null)
                override suspend fun migrate(legacyCarId: Int, targetHistoryCarId: Int,
                    eligibility: LegacyHistoryMigrationEligibility): LegacyHistoryMigrationResult =
                    error("diagnostic must not migrate")
            }
            val source = Source(ready, carData = CarData(1, vehicleUid = "synthetic-car-a"))
            val vm = DataReadinessViewModel(source, resolver, migration, evidence)
            vm.setCarId(1)
            runCurrent()
            assertEquals(2, captures)
            assertEquals(DiagnosticResult.PASS, vm.uiState.value.diagnosticFacts[0].result)
            assertEquals(0, source.configureCalls)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun accountOrOriginChangeDropsLateUncancellableResultAndClearsPriorFacts() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val first = CompletableDeferred<Unit>()
            val source = Source(ready, first)
            val evidence = Evidence()
            val vm = DataReadinessViewModel(source, Resolver, Migration, evidence)
            vm.setCarId(1)
            runCurrent()
            evidence.current = false
            evidence.scopeChanges.emit(Unit)
            runCurrent()
            first.complete(Unit)
            runCurrent()
            assertNull(vm.uiState.value.data)
            assertTrue(vm.uiState.value.diagnosticFacts.all { it.result == DiagnosticResult.UNKNOWN })
            assertEquals(0, source.configureCalls)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun sameRemoteIdCannotReusePriorCarResultAfterSwitch() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val first = CompletableDeferred<Unit>()
            val source = Source(ready, first)
            val vm = DataReadinessViewModel(source, Resolver, Migration, Evidence())
            vm.setCarId(1)
            runCurrent()
            vm.setCarId(2)
            runCurrent()
            first.complete(Unit)
            runCurrent()
            assertEquals("car-2", vm.uiState.value.data?.vehicleUid)
        } finally { Dispatchers.resetMain() }
    }

    private class Evidence : ReadinessEvidenceSource {
        override val scopeChanges = MutableSharedFlow<Unit>(replay = 1).apply { tryEmit(Unit) }
        var current = true
        override suspend fun capture(carId: Int) = object : ReadinessEvidenceSession {
            override suspend fun isCurrent() = current
            override fun verifies(readiness: DataReadiness?) = current
            override suspend fun localEvidence() = ReadinessLocalEvidence(qualifiedDrives = 2, persistedDrives = 2, persistedCharges = 0)
        }
    }

    private class Source(
        val ready: DataReadiness,
        val block: CompletableDeferred<Unit>? = null,
        val pairingStatus: TelemetryPairingStatus? = null,
        val carData: CarData? = null
    ) : DataReadinessDataSource {
        var configureCalls = 0
        var readinessCalls = 0
        override suspend fun getDataReadiness(carId: Int): ApiResult<DataReadiness> {
            readinessCalls++
            if (carId == 1 && block != null) withContext(NonCancellable) { block.await() }
            return ApiResult.Success(if (carId == 2) ready.copy(vehicleUid = "car-2") else ready)
        }
        override suspend fun getTelemetryPairingStatus(carId: Int): ApiResult<TelemetryPairingStatus> =
            pairingStatus?.let { ApiResult.Success(it) } ?: ApiResult.Error("fixture")
        override suspend fun configureTelemetry(carId: Int): ApiResult<TelemetryConfigureResult> {
            configureCalls++
            return ApiResult.Error("unexpected write")
        }
        override suspend fun getCar(carId: Int): ApiResult<CarData> =
            carData?.let { ApiResult.Success(it) } ?: ApiResult.Error("fixture")
        override suspend fun getCarStatus(carId: Int): ApiResult<CarStatusWithUnits> = ApiResult.Error("fixture")
    }

    private object Resolver : VehicleContextResolver {
        override suspend fun resolve(car: CarData): VehicleContext = error("no car returned")
        override suspend fun recordExplicitUpgradeOrigin(car: CarData) = error("diagnostic write")
    }
    private object Migration : LegacyHistoryMigrationService {
        override suspend fun inspect(legacyCarId: Int, currentModel: String?, currentVehicleFingerprint: String?, currentObservedOdometer: Double?): LegacyHistoryMigrationEligibility = error("no car returned")
        override suspend fun migrate(legacyCarId: Int, targetHistoryCarId: Int, eligibility: LegacyHistoryMigrationEligibility): LegacyHistoryMigrationResult = error("diagnostic migration")
    }
}
