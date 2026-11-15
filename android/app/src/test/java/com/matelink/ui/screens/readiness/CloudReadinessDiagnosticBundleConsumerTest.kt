package com.matelink.ui.screens.readiness

import com.matelink.data.api.models.*
import com.matelink.data.local.*
import com.matelink.data.repository.*
import com.squareup.moshi.Moshi
import java.lang.reflect.Proxy
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

/** Counts the complete screen call graph, including legacy readiness/car/status side paths. */
@OptIn(ExperimentalCoroutinesApi::class)
class CloudReadinessDiagnosticBundleConsumerTest {
    private val consumers = mutableListOf<DataReadinessViewModel>()

    @Test fun cloudOpenAndRefreshUseOneSafeGetEachAndRetainSixRowsAndMigrationContext() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val source = Source()
            val resolver = Resolver()
            val migration = Migration()
            val vm = consumer(source, resolver, migration)
            vm.setCarId(1)
            runCurrent()
            vm.refresh()
            runCurrent()
            assertEquals(emptyList<String>(), source.legacyCalls)
            assertEquals(listOf(1, 1), source.diagnosticCarIds)
            assertEquals(0, source.pairingCalls)
            assertEquals(0, source.configureCalls)
            assertEquals(0, resolver.resolveCalls)
            assertEquals(KEYS, vm.uiState.value.data!!.items.map { it.key })
            assertEquals("waiting_vehicle", vm.uiState.value.data!!.item("tpms")?.status)
            assertEquals("Fixture vehicle", vm.uiState.value.targetVehicleName)
            assertEquals(CACHED_CONTEXT, vm.uiState.value.migrationTarget)
            assertEquals("3", migration.model)
            assertEquals(CACHED_CONTEXT.stableIdentity, migration.fingerprint)
            assertEquals(1234.5, migration.odometer!!, 0.0)
        } finally { stopConsumers(); runCurrent(); Dispatchers.resetMain() }
    }

    @Test fun missingContextAnd404StayUnknownWithoutLegacyFallbackOrIdentityAllocation() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            for (result in listOf<ApiResult<FleetTelemetryDiagnostics>>(
                ApiResult.Success(diagnostic(context = null)), ApiResult.Error("unavailable", 404))) {
                val source = Source { result }
                val resolver = Resolver()
                val vm = consumer(source, resolver, Migration())
                vm.setCarId(1)
                runCurrent()
                assertEquals(emptyList<String>(), source.legacyCalls)
                assertEquals(0, resolver.resolveCalls)
                assertEquals(listOf(1), source.diagnosticCarIds)
                assertEquals(0, source.pairingCalls)
                assertEquals(0, source.configureCalls)
                // A non-null empty envelope retains all six existing rows as unknown (0/6 reported).
                assertNotNull(vm.uiState.value.data)
                assertTrue(vm.uiState.value.data!!.items.isEmpty())
                assertNotNull(vm.uiState.value.error)
                assertEquals(DiagnosticResult.UNKNOWN, vm.uiState.value.diagnosticFacts.first().result)
                assertNull(vm.uiState.value.migrationTarget)
            }
        } finally { stopConsumers(); runCurrent(); Dispatchers.resetMain() }
    }

    @Test fun selfHostedStillUsesItsExistingReadinessCarAndStatusWorkflow() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val source = Source()
            val resolver = Resolver()
            val vm = consumer(source, resolver, Migration(), HistoryConnectionSource.SELF_HOSTED)
            vm.setCarId(1)
            runCurrent()
            assertEquals(listOf("readiness", "car", "status"), source.legacyCalls)
            assertEquals(1, resolver.resolveCalls)
            assertEquals(KEYS, vm.uiState.value.data!!.items.map { it.key })
            assertEquals(0, source.pairingCalls)
            assertEquals(0, source.configureCalls)
        } finally { stopConsumers(); runCurrent(); Dispatchers.resetMain() }
    }

    @Test fun unknownOdometerUnitSourceOrClockNeverQualifiesMigrationAndNeverFetchesStatus() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            for (odometer in listOf(
                """{"value":1234.5,"unit":"mi","source":"telemetry_mqtt","observed_at":"2026-10-28T00:00:00Z"}""",
                """{"value":1234.5,"unit":"km","source":"fleet_api","observed_at":"2026-10-28T00:00:00Z"}""",
                """{"value":1234.5,"unit":"km","source":"telemetry_mqtt","observed_at":null}""",
                """{"value":1234.5,"unit":"km","source":"telemetry_mqtt","observed_at":"2099-01-01T00:00:00Z"}"""
            )) {
                val source = Source { ApiResult.Success(diagnostic(contextJson(odometer))) }
                val migration = Migration()
                val vm = consumer(source, Resolver(), migration)
                vm.setCarId(1)
                runCurrent()
                assertEquals(emptyList<String>(), source.legacyCalls)
                assertNull(migration.odometer)
                assertEquals(KEYS, vm.uiState.value.data!!.items.map { it.key })
            }
        } finally { stopConsumers(); runCurrent(); Dispatchers.resetMain() }
    }

    @Test fun localContextRemainsUsableWhenAllOfficialFleetReadsFail() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val failedOfficial = diagnostic().copy(availability = "unavailable", requests = FleetDiagnosticRequests(
                FleetDiagnosticRequest("error", 401), FleetDiagnosticRequest("not_attempted"), FleetDiagnosticRequest("not_attempted")))
            val source = Source { ApiResult.Success(failedOfficial) }
            val vm = consumer(source, Resolver(), Migration())
            vm.setCarId(1)
            runCurrent()
            assertEquals(emptyList<String>(), source.legacyCalls)
            assertEquals(KEYS, vm.uiState.value.data!!.items.map { it.key })
            assertEquals(FleetDiagnosticAvailability.UNAVAILABLE, vm.uiState.value.diagnosticFacts.first().officialFleet?.availability)
            assertEquals(0, source.configureCalls)
        } finally { stopConsumers(); runCurrent(); Dispatchers.resetMain() }
    }

    @Test fun mismatchingCarUidOrUnknownContextVersionCannotSeedAnyReadinessOrMigration() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val wrongUid = "c".repeat(64)
            for (context in listOf(
                contextJson().replace("\"car_id\":1", "\"car_id\":2"),
                contextJson().replace("\"car_id\":1,\"vehicle_uid\":\"$UID\"", "\"car_id\":1,\"vehicle_uid\":\"$wrongUid\""),
                contextJson().replace("\"availability\":\"available\"", "\"availability\":\"future_state\""),
                contextJson().replace("\"capability_version\":1", "\"capability_version\":0")
            )) {
                val source = Source { ApiResult.Success(diagnostic(context)) }
                val vm = consumer(source, Resolver(), Migration())
                vm.setCarId(1)
                runCurrent()
                assertEquals(emptyList<String>(), source.legacyCalls)
                assertTrue(vm.uiState.value.data!!.items.isEmpty())
                assertNull(vm.uiState.value.migrationTarget)
                assertEquals(DiagnosticResult.UNKNOWN, vm.uiState.value.diagnosticFacts.first().result)
            }
        } finally { stopConsumers(); runCurrent(); Dispatchers.resetMain() }
    }

    private fun consumer(source: Source, resolver: Resolver, migration: Migration,
        connection: HistoryConnectionSource = HistoryConnectionSource.CLOUD): DataReadinessViewModel {
        val evidence = object : ReadinessEvidenceSource {
            override val scopeChanges = flowOf(Unit)
            override suspend fun capture(carId: Int): ReadinessEvidenceSession {
                // Dynamic proxy permits executable RED before the scoped read-only properties exist.
                return Proxy.newProxyInstance(ReadinessEvidenceSession::class.java.classLoader,
                    arrayOf(ReadinessEvidenceSession::class.java)) { _, method, arguments ->
                    when (method.name) {
                        "getConnectionSource" -> connection
                        "getCachedContext" -> CACHED_CONTEXT
                        "getRequestFence" -> null
                        "isCurrent" -> true
                        "verifies" -> (arguments?.firstOrNull() as? DataReadiness)?.vehicleUid == UID && (arguments?.getOrNull(1) as? CarData)?.vehicleUid == UID
                        "localEvidence" -> null
                        "toString" -> "Scoped fixture evidence"
                        else -> error("Unexpected evidence method ${method.name}")
                    }
                } as ReadinessEvidenceSession
            }
        }
        return DataReadinessViewModel(source, resolver, migration, evidence).also { consumers += it }
    }
    private fun stopConsumers() { consumers.forEach { it.onScreenPaused() }; consumers.clear() }

    private class Source(val read: suspend (Int) -> ApiResult<FleetTelemetryDiagnostics> = { ApiResult.Success(diagnostic()) }) : DataReadinessDataSource {
        val legacyCalls = mutableListOf<String>()
        val diagnosticCarIds = mutableListOf<Int>()
        var pairingCalls = 0
        var configureCalls = 0
        override suspend fun getTelemetryDiagnostics(carId: Int): ApiResult<FleetTelemetryDiagnostics> {
            diagnosticCarIds += carId
            return read(carId)
        }
        override suspend fun getDataReadiness(carId: Int): ApiResult<DataReadiness> {
            legacyCalls += "readiness"
            return ApiResult.Success(READINESS)
        }
        override suspend fun getCar(carId: Int): ApiResult<CarData> {
            legacyCalls += "car"
            return ApiResult.Success(CarData(carId, vehicleUid = UID, name = "Fixture vehicle", carDetails = CarDetails(model = "3")))
        }
        override suspend fun getCarStatus(carId: Int): ApiResult<CarStatusWithUnits> {
            legacyCalls += "status"
            return ApiResult.Success(CarStatusWithUnits(CarStatus(odometer = 9999.0), Units(unitOfLength = "km")))
        }
        override suspend fun getTelemetryPairingStatus(carId: Int): ApiResult<TelemetryPairingStatus> {
            pairingCalls++
            return ApiResult.Error("unexpected setup read")
        }
        override suspend fun configureTelemetry(carId: Int): ApiResult<TelemetryConfigureResult> {
            configureCalls++
            return ApiResult.Error("unexpected setup write")
        }
    }
    private class Resolver : VehicleContextResolver {
        var resolveCalls = 0
        override suspend fun resolve(car: CarData): VehicleContext { resolveCalls++; return CACHED_CONTEXT }
        override suspend fun recordExplicitUpgradeOrigin(car: CarData) = error("unexpected identity write")
    }
    private class Migration : LegacyHistoryMigrationService {
        var model: String? = null
        var fingerprint: String? = null
        var odometer: Double? = null
        override suspend fun inspect(legacyCarId: Int, currentModel: String?, currentVehicleFingerprint: String?, currentObservedOdometer: Double?): LegacyHistoryMigrationEligibility {
            model = currentModel; fingerprint = currentVehicleFingerprint; odometer = currentObservedOdometer
            return LegacyHistoryMigrationEligibility(false, 1, 1, 1000.0)
        }
        override suspend fun migrate(legacyCarId: Int, targetHistoryCarId: Int, eligibility: LegacyHistoryMigrationEligibility): LegacyHistoryMigrationResult = error("unexpected migration")
    }
    companion object {
        private val UID = "b".repeat(64)
        private val KEYS = listOf("live_status", "location", "tpms", "drives", "charges", "battery_health")
        private val READINESS = DataReadiness(1, UID, KEYS.map { key -> DataReadinessItem(key,
            when (key) { "tpms" -> "waiting_vehicle"; "charges" -> "collecting"; "battery_health" -> "unsupported"; else -> "available" }, "telemetry_mqtt") })
        private val CACHED_CONTEXT = VehicleContext(1, "fixture-verified-identity", 31, HistoryConnectionSource.CLOUD, "cloud")
        private fun contextJson(odometer: String = """{"value":1234.5,"unit":"km","source":"telemetry_mqtt","observed_at":"${java.time.Instant.now()}"}"""): String {
            val readiness = Moshi.Builder().build().adapter(DataReadiness::class.java).toJson(READINESS)
            return """{"availability":"available","readiness":$readiness,"car":{"car_id":1,"vehicle_uid":"$UID","name":"Fixture vehicle","car_details":{"model":"3"}},"observed_odometer":$odometer}"""
        }
        private fun diagnostic(context: String? = contextJson()): FleetTelemetryDiagnostics {
            val nested = context?.let { ",\"readiness_context\":$it" }.orEmpty()
            return Moshi.Builder().build().adapter(FleetTelemetryDiagnosticsResponse::class.java).fromJson(
                """{"data":{"availability":"available","requests":{"fleet_status":{"status":"ok","http_status":200},"fleet_telemetry_config":{"status":"ok","http_status":200},"fleet_telemetry_errors":{"status":"ok","http_status":200}},"vehicle":{"paired":true},"configuration":{"synced":false}$nested}}""")!!.data!!
        }
    }
}
