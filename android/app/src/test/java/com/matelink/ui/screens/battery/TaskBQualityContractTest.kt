package com.matelink.ui.screens.battery

import com.matelink.data.repository.ApiResult
import com.matelink.data.api.models.BatteryHealth
import com.matelink.data.api.models.CarStatus
import com.matelink.ui.screens.stats.ScopedUiLoader
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TaskBQualityContractTest {
    @Test
    fun batteryLoadCancelsAndGuardsPriorJobsAndRethrowsCancellation() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val loader = ScopedUiLoader(scope)
        val state = MutableStateFlow(BatteryUiState())
        val oldGate = CompletableDeferred<Unit>()
        var account = "A"
        var cancellationObserved = false
        fun load(capacity: Double, status: String, waiting: Boolean) {
            val captured = account
            loader.load(
                proof = { suspend { account == captured } },
                fetch = { request ->
                    if (waiting) try {
                        oldGate.await()
                    } catch (e: CancellationException) {
                        cancellationObserved = true
                        throw e
                    }
                    request.requireCurrent()
                    Pair(ApiResult.Success(BatteryHealth(maxCapacity = capacity)), CarStatus(displayName = status))
                },
                onSuccess = { (health, carStatus) ->
                    state.value = state.value.withHealthStatus(health, carStatus, null, null)
                },
                onFailure = { error("Cancellation must not become a page error") }
            )
        }
        try {
            load(60.0, "old-A", true)
            advanceUntilIdle()
            account = "B"
            load(75.0, "current-B", false)
            advanceUntilIdle()
            assertTrue(cancellationObserved)
            assertEquals(75.0, state.value.batteryHealth?.maxCapacity!!, 0.0)
            assertEquals("current-B", state.value.carStatus?.displayName)
            assertFalse(state.value.isLoading)
            assertEquals(null, state.value.error)
        } finally { scope.cancel() }
    }

    @Test
    fun lateOldHealthStatusAndExceptionCannotOverwriteCurrentBatteryPage() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val loader = ScopedUiLoader(scope)
        val state = MutableStateFlow(BatteryUiState())
        val oldGate = CompletableDeferred<Unit>()
        val errorGate = CompletableDeferred<Unit>()
        var account = "A"
        try {
            loader.load(
                proof = { suspend { account == "A" } },
                fetch = { request ->
                    withContext(NonCancellable) { oldGate.await() }
                    request.requireCurrent()
                    Pair(ApiResult.Success(BatteryHealth(maxCapacity = 60.0)), CarStatus(displayName = "old-A"))
                },
                onSuccess = { (health, status) -> state.value = state.value.withHealthStatus(health, status, null, null) },
                onFailure = { error("Old response must not publish") }
            )
            advanceUntilIdle()
            account = "B"
            loader.load(
                proof = { suspend { account == "B" } },
                fetch = { request ->
                    request.requireCurrent()
                    Pair(ApiResult.Success(BatteryHealth(maxCapacity = 75.0)), CarStatus(displayName = "current-B"))
                },
                onSuccess = { (health, status) -> state.value = state.value.withHealthStatus(health, status, null, null) },
                onFailure = { error("Current response must succeed") }
            )
            advanceUntilIdle()
            oldGate.complete(Unit)
            advanceUntilIdle()
            assertEquals("current-B", state.value.carStatus?.displayName)
            assertEquals(75.0, state.value.batteryHealth?.maxCapacity!!, 0.0)

            account = "A"
            loader.load<Pair<ApiResult<BatteryHealth>, CarStatus>>(
                proof = { suspend { account == "A" } },
                fetch = { withContext(NonCancellable) { errorGate.await(); throw IllegalStateException("late") } },
                onSuccess = { error("Old failing request cannot succeed") },
                onFailure = { state.value = state.value.copy(error = it.message) }
            )
            advanceUntilIdle()
            account = "B"
            loader.load(
                proof = { suspend { account == "B" } },
                fetch = { request ->
                    request.requireCurrent()
                    Pair(ApiResult.Success(BatteryHealth(maxCapacity = 80.0)), CarStatus(displayName = "new-B"))
                },
                onSuccess = { (health, status) -> state.value = state.value.withHealthStatus(health, status, null, null) },
                onFailure = { error("Current response must succeed") }
            )
            advanceUntilIdle()
            errorGate.complete(Unit)
            advanceUntilIdle()
            assertEquals("new-B", state.value.carStatus?.displayName)
            assertEquals(80.0, state.value.batteryHealth?.maxCapacity!!, 0.0)
            assertEquals(null, state.value.error)
        } finally { scope.cancel() }
    }

    @Test
    fun readinessLoadCancelsAndGuardsPriorJobsAndRethrowsCancellation() {
        val source = File("src/main/java/com/matelink/ui/screens/readiness/DataReadinessViewModel.kt").readText()

        assertTrue(source.contains("loadJob?.cancel()"))
        assertTrue(source.contains("loadGeneration"))
        assertTrue(source.contains("catch (e: CancellationException)"))
        assertTrue(source.contains("throw e"))
    }

    @Test
    fun repositoryRethrowsCancellationBeforeMappingOtherExceptions() {
        val source = File("src/main/java/com/matelink/data/repository/TeslamateRepository.kt").readText()

        val executeWithFallback = source
            .substringAfter("private suspend fun <T> executeWithFallback")
            .substringBefore("suspend fun testConnection")
        val cancellationCatch = executeWithFallback.indexOf("catch (e: CancellationException)")
        val genericCatch = executeWithFallback.indexOf("catch (e: Exception)")
        assertTrue(cancellationCatch >= 0)
        assertTrue(genericCatch > cancellationCatch)
        assertTrue(executeWithFallback.substring(cancellationCatch, genericCatch).contains("throw e"))
    }

    @Test
    fun legacyReadinessCompatibilityRequiresAnExplicitSelfHostedGate() {
        val source = File("src/main/java/com/matelink/data/repository/TeslamateRepository.kt").readText()

        assertTrue(source.contains("allowLegacyCompatibility"))
        assertTrue(source.contains("ConnectionMode.SELF_HOSTED"))
    }

    @Test
    fun transportAndAuthFailuresNeverBecomeCollectingBatteryHealth() {
        val failures = listOf(
            ApiResult.Error("unauthorized", 401),
            ApiResult.Error("rate limited", 429),
            ApiResult.Error("server failure", 503),
            ApiResult.Error("Server is temporarily unreachable"),
            ApiResult.Error("Server returned unrecognised data")
        )

        failures.forEach { failure ->
            assertNotEquals(BatteryHealthAvailability.COLLECTING, classifyBatteryHealth(failure, null))
        }
    }

    @Test
    fun batteryReadinessIsStartedIndependentlyFromHealthAndStatusRendering() {
        val source = File("src/main/java/com/matelink/ui/screens/battery/BatteryViewModel.kt").readText()

        assertTrue(source.contains("async"))
        assertTrue(source.contains("getDataReadiness"))
        assertTrue(source.contains("await"))
    }

    @Test
    fun batteryHealthDoesNotInventCapacityRangeOrEfficiencyFallbacks() {
        val source = File("src/main/java/com/matelink/ui/screens/battery/BatteryViewModel.kt").readText()

        assertFalse(source.contains("?: 60.0"))
        assertFalse(source.contains("?: 145.0"))
        assertFalse(source.contains("?: 435.0"))
        assertFalse(source.contains("?: 399.0"))
        assertFalse(source.contains("else 91.7"))
    }
}
