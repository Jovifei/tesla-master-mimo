package com.matelink.ui.screens.readiness

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.matelink.data.api.models.CarData
import com.matelink.data.api.models.DataReadiness
import com.matelink.data.api.models.TelemetryConfigureResult
import com.matelink.data.api.models.TelemetryPairingStatus
import com.matelink.data.local.VehicleContext
import com.matelink.data.local.VehicleContextResolver
import com.matelink.data.repository.ApiResult
import com.matelink.data.repository.CarStatusWithUnits
import com.matelink.data.repository.DataReadinessDataSource
import com.matelink.data.repository.LegacyHistoryMigrationEligibility
import com.matelink.data.repository.LegacyHistoryMigrationResult
import com.matelink.data.repository.LegacyHistoryMigrationService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Task 2 contract tests deliberately resolve the new production types at runtime.
 * This keeps the RED phase executable before those types exist.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class TelemetryPairingContractTest {
    @Before
    fun installMainDispatcher() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun resetMainDispatcher() {
        Dispatchers.resetMain()
    }

    @Test
    fun pairingEnvelopeParsesNullableConfigSyncedWithoutPromotingUnknownToSynced() {
        val responseClass = productionClass("com.matelink.data.api.models.TelemetryPairingResponse")
        val adapter = Moshi.Builder()
            .add(KotlinJsonAdapterFactory())
            .build()
            .adapter<Any>(responseClass)

        val falseResponse = adapter.fromJson(
            """{"data":{"status":"waiting_vehicle","virtual_key_url":"https://tesla.com/_ak/partner.example.com","updated_at":"2026-08-31T00:00:00Z","config_synced":false}}"""
        )!!
        val falseStatus = getter(falseResponse, "getData")!!
        assertEquals("waiting_vehicle", getter(falseStatus, "getStatus"))
        assertEquals(false, getter(falseStatus, "getConfigSynced"))

        val unknownResponse = adapter.fromJson("""{"data":{"status":"collecting"}}""")!!
        val unknownStatus = getter(unknownResponse, "getData")!!
        assertNull(getter(unknownStatus, "getConfigSynced"))
    }

    @Test
    fun virtualKeyUrlAllowsOnlyOfficialTeslaHttpsAkPath() {
        assertEquals(
            "https://tesla.com/_ak/partner.example.com",
            callPresentation("officialTeslaVirtualKeyUrlOrNull", "https://tesla.com/_ak/partner.example.com")
        )
        assertEquals(
            "https://www.tesla.com/_ak/partner.example.com",
            callPresentation("officialTeslaVirtualKeyUrlOrNull", "https://www.tesla.com/_ak/partner.example.com")
        )
        listOf(
            "http://tesla.com/_ak/partner.example.com",
            "https://evil.example/_ak/partner.example.com",
            "https://tesla.com/other/partner.example.com",
            "https://tesla.com/_ak/partner.example.com/extra",
            "https://tesla.com/_ak/partner.example.com?redirect=https://evil.example",
            "https://tesla.com@evil.example/_ak/partner.example.com",
            "https://tesla.com/_ak/%2Fpartner.example.com"
        ).forEach { candidate ->
            assertNull("must reject $candidate", callPresentation("officialTeslaVirtualKeyUrlOrNull", candidate))
        }
    }

    @Test
    fun virtualKeyUrlRequiresTheExactRawAkPathWithoutDuplicateSeparators() {
        assertEquals(
            "https://tesla.com/_ak/partner.example.com",
            callPresentation("officialTeslaVirtualKeyUrlOrNull", "https://tesla.com/_ak/partner.example.com")
        )
        assertNull(
            callPresentation("officialTeslaVirtualKeyUrlOrNull", "https://tesla.com//_ak/partner.example.com")
        )
        assertNull(
            callPresentation("officialTeslaVirtualKeyUrlOrNull", "https://tesla.com/_ak//partner.example.com")
        )
    }

    @Test
    fun pairingAndConfigureErrorsMapToLocalizedCategoriesNotRawCodes() {
        assertEquals("PAIRING_REQUIRED", callPresentation("telemetrySetupPresentation", "pairing_required", null).toString())
        assertEquals("WAITING_VEHICLE", callPresentation("telemetrySetupPresentation", "waiting_vehicle", false).toString())
        assertEquals("PERMISSION_REQUIRED", callPresentation("telemetrySetupPresentation", "permission_required", null).toString())
        assertEquals("BILLING_BLOCKED", callPresentation("telemetrySetupPresentation", "billing_blocked", null).toString())
        assertEquals("TELEMETRY_ERROR", callPresentation("telemetrySetupPresentation", "telemetry_error", null).toString())
        assertEquals("TELEMETRY_NOT_CONFIGURED", callPresentation("telemetrySetupPresentation", "telemetry_not_configured", null).toString())
        assertEquals("COLLECTING", callPresentation("telemetrySetupPresentation", "collecting", true).toString())
        assertEquals("COLLECTING", callPresentation("telemetrySetupPresentation", "collecting", null).toString())
        assertEquals("AVAILABLE", callPresentation("telemetrySetupPresentation", "available", true).toString())
        assertEquals("WAITING_VEHICLE", callPresentation("telemetrySetupPresentation", "available", null).toString())
        assertEquals("SYNCED", callPresentation("telemetryConfigSyncPresentation", true).toString())
        assertEquals("PENDING", callPresentation("telemetryConfigSyncPresentation", false).toString())
        assertEquals("UNKNOWN", callPresentation("telemetryConfigSyncPresentation", null).toString())
    }

    @Test
    fun waitingOrPendingTelemetryKeepsAnExplicitManualConfigureAction() {
        assertEquals(
            "CONFIGURE",
            callPresentation("telemetryConfigureActionPresentation", "waiting_vehicle", false).toString()
        )
        assertEquals(
            "CONFIGURE",
            callPresentation("telemetryConfigureActionPresentation", "waiting_vehicle", null).toString()
        )
        assertEquals(
            "CONFIGURE",
            callPresentation("telemetryConfigureActionPresentation", "available", null).toString()
        )
        assertEquals(
            "CONFIGURE",
            callPresentation("telemetryConfigureActionPresentation", "pairing_required", null).toString()
        )
        assertEquals(
            "NONE",
            callPresentation("telemetryConfigureActionPresentation", "permission_required", false).toString()
        )
        assertEquals(
            "NONE",
            callPresentation("telemetryConfigureActionPresentation", "billing_blocked", false).toString()
        )
        assertEquals(
            "NONE",
            callPresentation("telemetryConfigureActionPresentation", "telemetry_error", false).toString()
        )
        assertEquals(
            "NONE",
            callPresentation("telemetryConfigureActionPresentation", "telemetry_not_configured", false).toString()
        )
        assertEquals(
            "NONE",
            callPresentation("telemetryConfigureActionPresentation", "available", true).toString()
        )
    }

    @Test
    fun switchingCarsKeepsOldConfigureLeaseUntilItsCancelledPostFinallyExits() = runBlocking {
        val source = BlockingReadinessSource()
        val viewModel = DataReadinessViewModel(
            repository = source,
            vehicleContextRepository = NoopVehicleContextResolver,
            migrationRepository = NoopLegacyHistoryMigrationService
        )
        try {
            viewModel.setCarId(1)
            viewModel.configureTelemetry()
            withTimeout(1_000L) { source.firstConfigureStarted.await() }

            viewModel.setCarId(2)
            viewModel.configureTelemetry() // B must be rejected while cancelled A is still in flight.
            viewModel.configureTelemetry() // The third attempt must also be rejected.

            assertEquals(listOf(1), source.configureCarIds)
            assertEquals(1, source.peakConcurrentConfigures)
            assertFalse(source.secondConfigureStarted.isCompleted)

            source.allowFirstConfigureToReturn.complete(Unit)
            withTimeout(1_000L) { source.firstConfigureExited.await() }

            viewModel.configureTelemetry()
            withTimeout(1_000L) { source.secondConfigureStarted.await() }
            assertEquals(listOf(1, 2), source.configureCarIds)
            assertEquals(1, source.peakConcurrentConfigures)

            source.allowSecondConfigureToReturn.complete(Unit)
            withTimeout(1_000L) { source.secondConfigureExited.await() }
        } finally {
            source.allowFirstConfigureToReturn.complete(Unit)
            source.allowSecondConfigureToReturn.complete(Unit)
        }
    }

    @Test
    fun switchingCarsDoesNotLetTheCancelledConfigureFinallyReleaseTheNewCarLease() = runBlocking {
        val gate = productionClass("com.matelink.ui.screens.readiness.TelemetryConfigureGate")
            .getDeclaredConstructor()
            .newInstance()
        val calls = ControllableConfigureCalls()

        val oldLease = startGate(gate, generation = 1L)
            ?: throw AssertionError("first configure must acquire a lease")
        val oldConfigure = launch {
            try {
                calls.configure(carId = 1)
            } finally {
                withContext(NonCancellable) {
                    calls.oldFinallyMayFinish.await()
                    finishGate(gate, oldLease)
                }
            }
        }
        withTimeout(1_000L) { calls.firstCallStarted.await() }

        oldConfigure.cancel()
        withTimeout(1_000L) { calls.firstCallCancelled.await() }

        // setCarId(2) cancels/revokes only the lease held by car 1. Its delayed
        // finally must not be able to release the later car 2 lease.
        finishGate(gate, oldLease)
        val newLease = startGate(gate, generation = 2L)
            ?: throw AssertionError("replacement configure must acquire a lease")
        val newConfigure = launch {
            try {
                calls.configure(carId = 2)
            } finally {
                finishGate(gate, newLease)
            }
        }
        withTimeout(1_000L) { calls.secondCallStarted.await() }

        assertEquals("cancelled and replacement calls must not overlap", 1, calls.peakConcurrentCalls)
        calls.oldFinallyMayFinish.complete(Unit)
        oldConfigure.join()

        assertNull("old car finally must not release the new car lease", startGate(gate, generation = 2L))

        calls.secondCallMayFinish.complete(Unit)
        newConfigure.join()
        assertNotNull("new car lease should release when its own configure finishes", startGate(gate, generation = 2L))
    }

    @Test
    fun pollingPolicyUsesFiveSecondIntervalsForAtMostThirtySecondsAndRejectsStaleOrInactivePage() {
        val policy = productionClass("com.matelink.ui.screens.readiness.TelemetryPollingPolicy")
            .getDeclaredConstructor()
            .newInstance()
        val nextDelay = policy.javaClass.getMethod("nextDelayMs", Long::class.javaPrimitiveType)
        val shouldContinue = policy.javaClass.getMethod(
            "shouldContinue",
            Long::class.javaPrimitiveType,
            Long::class.javaPrimitiveType,
            Long::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType
        )

        (0L..25_000L step 5_000L).forEach { elapsed ->
            assertEquals(5_000L, nextDelay.invoke(policy, elapsed))
            assertTrue(shouldContinue.invoke(policy, elapsed, 4L, 4L, true) as Boolean)
        }
        assertNull(nextDelay.invoke(policy, 30_000L))
        assertTrue(shouldContinue.invoke(policy, 30_000L, 4L, 4L, true) as Boolean)
        assertFalse(shouldContinue.invoke(policy, 5_000L, 4L, 5L, true) as Boolean)
        assertFalse(shouldContinue.invoke(policy, 5_000L, 4L, 4L, false) as Boolean)
    }

    @Test
    fun telemetryPollingCancelsASuspendedRequestAtTheThirtySecondDeadlineAndNeverPollsAgain() = runTest {
        Dispatchers.resetMain()
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val source = SlowPollingReadinessSource()
        val viewModel = DataReadinessViewModel(
            repository = source,
            vehicleContextRepository = NoopVehicleContextResolver,
            migrationRepository = NoopLegacyHistoryMigrationService
        )

        viewModel.setCarId(1)
        runCurrent()
        viewModel.configureTelemetry()
        runCurrent()

        assertTrue("the telemetry poll must start", source.suspendedPollStarted.isCompleted)
        assertEquals(2, source.pairingStatusCalls)

        advanceTimeBy(TelemetryPollingPolicy.MAXIMUM_WINDOW_MS)
        runCurrent()

        assertEquals("the suspended request must be cancelled at the deadline", 1, source.cancelledPollRequests)
        assertEquals(2, source.pairingStatusCalls)
        assertEquals("polling timeout must never invoke configure automatically", 1, source.configureCalls)

        advanceTimeBy(TelemetryPollingPolicy.POLL_INTERVAL_MS)
        runCurrent()
        assertEquals("no poll request may start after the deadline", 2, source.pairingStatusCalls)
        assertEquals("the explicit initial tap remains the only configure invocation", 1, source.configureCalls)
    }

    @Test
    fun viewModelCancelsPollingOnPauseAndCarSwitchAndGuardsPolledResultsByGeneration() {
        val source = java.io.File("src/main/java/com/matelink/ui/screens/readiness/DataReadinessViewModel.kt").readText()

        assertTrue(source.contains("pollJob?.cancel()"))
        assertTrue(source.contains("pollingPolicy.shouldContinue"))
        assertTrue(source.contains("pollGeneration"))
    }

    private fun productionClass(name: String): Class<*> = try {
        Class.forName(name)
    } catch (_: ClassNotFoundException) {
        throw AssertionError("Task 2 production type is missing: $name")
    }

    private fun getter(instance: Any, name: String): Any? = instance.javaClass.getMethod(name).invoke(instance)

    private fun callPresentation(name: String, vararg arguments: Any?): Any? {
        val owner = productionClass("com.matelink.ui.screens.readiness.TelemetryPairingPresentationKt")
        val parameterTypes = arguments.map {
            when (it) {
                is String -> String::class.java
                is Boolean -> Boolean::class.javaObjectType
                else -> Boolean::class.javaObjectType
            }
        }.toTypedArray()
        return owner.getMethod(name, *parameterTypes).invoke(null, *arguments)
    }

    private fun startGate(gate: Any, generation: Long): Any? {
        val method = gate.javaClass.methods.singleOrNull {
            it.name == "tryStart" && it.parameterTypes.contentEquals(arrayOf(Long::class.javaPrimitiveType))
        }
        assertNotNull("TelemetryConfigureGate must issue a generation-owned lease", method)
        return method!!.invoke(gate, generation)
    }

    private fun finishGate(gate: Any, lease: Any) {
        val method = gate.javaClass.methods.singleOrNull {
            it.name == "finish" && it.parameterTypes.size == 1
        }
        assertNotNull("TelemetryConfigureGate must require the acquired lease to finish", method)
        method!!.invoke(gate, lease)
    }

    private class ControllableConfigureCalls {
        val firstCallStarted = CompletableDeferred<Unit>()
        val firstCallCancelled = CompletableDeferred<Unit>()
        val secondCallStarted = CompletableDeferred<Unit>()
        val oldFinallyMayFinish = CompletableDeferred<Unit>()
        val secondCallMayFinish = CompletableDeferred<Unit>()
        private var activeCalls = 0
        var peakConcurrentCalls = 0
            private set

        suspend fun configure(carId: Int) {
            activeCalls += 1
            peakConcurrentCalls = maxOf(peakConcurrentCalls, activeCalls)
            try {
                when (carId) {
                    1 -> {
                        firstCallStarted.complete(Unit)
                        awaitCancellation()
                    }
                    2 -> {
                        secondCallStarted.complete(Unit)
                        secondCallMayFinish.await()
                    }
                    else -> error("unexpected car: $carId")
                }
            } finally {
                activeCalls -= 1
                if (carId == 1) firstCallCancelled.complete(Unit)
            }
        }
    }

    private class BlockingReadinessSource : DataReadinessDataSource {
        val firstConfigureStarted = CompletableDeferred<Unit>()
        val secondConfigureStarted = CompletableDeferred<Unit>()
        val firstConfigureExited = CompletableDeferred<Unit>()
        val secondConfigureExited = CompletableDeferred<Unit>()
        val allowFirstConfigureToReturn = CompletableDeferred<Unit>()
        val allowSecondConfigureToReturn = CompletableDeferred<Unit>()
        val configureCarIds = mutableListOf<Int>()
        var peakConcurrentConfigures = 0
            private set
        private var activeConfigures = 0

        override suspend fun getDataReadiness(carId: Int): ApiResult<DataReadiness> = awaitCancellation()

        override suspend fun getTelemetryPairingStatus(carId: Int): ApiResult<TelemetryPairingStatus> = awaitCancellation()

        override suspend fun configureTelemetry(carId: Int): ApiResult<TelemetryConfigureResult> {
            configureCarIds += carId
            activeConfigures += 1
            peakConcurrentConfigures = maxOf(peakConcurrentConfigures, activeConfigures)
            try {
                when (carId) {
                    1 -> {
                        firstConfigureStarted.complete(Unit)
                        withContext(NonCancellable) { allowFirstConfigureToReturn.await() }
                        return ApiResult.Success(TelemetryConfigureResult())
                    }
                    2 -> {
                        secondConfigureStarted.complete(Unit)
                        withContext(NonCancellable) { allowSecondConfigureToReturn.await() }
                        return ApiResult.Success(TelemetryConfigureResult())
                    }
                    else -> error("unexpected car: $carId")
                }
            } finally {
                activeConfigures -= 1
                if (carId == 1) firstConfigureExited.complete(Unit) else secondConfigureExited.complete(Unit)
            }
        }

        override suspend fun getCar(carId: Int): ApiResult<CarData> = error("load should remain suspended")

        override suspend fun getCarStatus(carId: Int): ApiResult<CarStatusWithUnits> = error("load should remain suspended")
    }

    private class SlowPollingReadinessSource : DataReadinessDataSource {
        val suspendedPollStarted = CompletableDeferred<Unit>()
        var pairingStatusCalls = 0
            private set
        var configureCalls = 0
            private set
        var cancelledPollRequests = 0
            private set

        override suspend fun getDataReadiness(carId: Int): ApiResult<DataReadiness> = awaitCancellation()

        override suspend fun getTelemetryPairingStatus(carId: Int): ApiResult<TelemetryPairingStatus> {
            pairingStatusCalls += 1
            if (pairingStatusCalls == 1) awaitCancellation()
            suspendedPollStarted.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                cancelledPollRequests += 1
            }
        }

        override suspend fun configureTelemetry(carId: Int): ApiResult<TelemetryConfigureResult> {
            configureCalls += 1
            return ApiResult.Success(TelemetryConfigureResult())
        }

        override suspend fun getCar(carId: Int): ApiResult<CarData> = error("load should remain suspended")

        override suspend fun getCarStatus(carId: Int): ApiResult<CarStatusWithUnits> = error("load should remain suspended")
    }

    private object NoopVehicleContextResolver : VehicleContextResolver {
        override suspend fun resolve(car: CarData): VehicleContext = error("load should remain suspended")

        override suspend fun recordExplicitUpgradeOrigin(car: CarData): Boolean = error("not used")
    }

    private object NoopLegacyHistoryMigrationService : LegacyHistoryMigrationService {
        override suspend fun inspect(
            legacyCarId: Int,
            currentModel: String?,
            currentVehicleFingerprint: String?,
            currentObservedOdometer: Double?
        ): LegacyHistoryMigrationEligibility = error("load should remain suspended")

        override suspend fun migrate(
            legacyCarId: Int,
            targetHistoryCarId: Int,
            eligibility: LegacyHistoryMigrationEligibility
        ): LegacyHistoryMigrationResult = error("not used")
    }
}
