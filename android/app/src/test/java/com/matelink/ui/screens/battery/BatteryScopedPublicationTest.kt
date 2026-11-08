package com.matelink.ui.screens.battery

import com.matelink.data.api.models.BatteryHealth
import com.matelink.data.repository.ApiResult
import com.matelink.ui.screens.stats.ScopedUiLoader
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
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BatteryScopedPublicationTest {
    @Test fun firstHealthStatusPublicationRejectsLateAtoBtoAResponse() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val loader = ScopedUiLoader(scope)
        val state = MutableStateFlow(BatteryUiState())
        val oldGate = CompletableDeferred<Unit>()
        var account = "A"
        fun load(capacity: Double, waiting: Boolean) {
            val captured = account
            loader.load(
                proof = { suspend { account == captured } },
                fetch = { request ->
                    if (waiting) withContext(NonCancellable) { oldGate.await() }
                    request.requireCurrent() // Same fence used before the VM's first health/status publication.
                    ApiResult.Success(BatteryHealth(maxCapacity = capacity))
                },
                onSuccess = { state.value = state.value.withHealthStatus(it, null, null, null) },
                onFailure = { error("Unexpected current failure") }
            )
        }
        try {
            load(60.0, true)
            advanceUntilIdle()
            account = "B"
            load(70.0, false)
            advanceUntilIdle()
            account = "A"
            load(75.0, false)
            advanceUntilIdle()
            oldGate.complete(Unit)
            advanceUntilIdle()
            assertEquals(75.0, state.value.batteryHealth?.maxCapacity!!, 0.0)
            assertFalse(state.value.isLoading)
        } finally { scope.cancel() }
    }
}
