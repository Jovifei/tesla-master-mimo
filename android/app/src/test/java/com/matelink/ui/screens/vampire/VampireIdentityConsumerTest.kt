package com.matelink.ui.screens.vampire

import androidx.lifecycle.ViewModelStore
import com.matelink.data.api.models.StandbyWindowData
import com.matelink.data.local.HistoryConnectionSource
import com.matelink.data.local.HistoryReadScope
import com.matelink.data.local.VehicleContext
import com.matelink.data.repository.ApiResult
import com.matelink.data.repository.UnifiedHistory
import com.matelink.data.repository.VerifiedHistoryReadContext
import com.matelink.data.repository.HISTORY_IDENTITY_UNAVAILABLE
import com.matelink.domain.analytics.StandbyRange
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VampireIdentityConsumerTest {
    private class Source : VampirePageSource {
        override val scopeChanges = MutableStateFlow<HistoryReadScope?>(HistoryReadScope(
            HistoryConnectionSource.CLOUD, "cloud", "A", "https://api.example"))
        var globalCar = 1
        var result: suspend (Int) -> ApiResult<List<StandbyWindowData>> = { car -> ApiResult.Success(listOf(window(car))) }
        var historyResult: suspend (Int) -> ApiResult<UnifiedHistory> = { ApiResult.Error("synthetic offline", 503) }
        val requested = mutableListOf<Int>()
        override suspend fun resolve(carId: Int) = ApiResult.Success(proof(carId))
        private fun proof(carId: Int): VerifiedHistoryReadContext {
            val scope = requireNotNull(scopeChanges.value)
            return VerifiedHistoryReadContext(scope, VehicleContext(carId, "${scope.accountNamespace}:$carId",
                -carId, HistoryConnectionSource.CLOUD, "cloud"), true, null, false)
        }
        override suspend fun isCurrent(proof: VerifiedHistoryReadContext) = isCurrentNow(proof)
        override fun isCurrentNow(proof: VerifiedHistoryReadContext) = scopeChanges.value == proof.scope
        override suspend fun standby(carId: Int): ApiResult<List<StandbyWindowData>> {
            requested += carId
            return result(carId)
        }
        override suspend fun history(carId: Int): ApiResult<UnifiedHistory> = historyResult(carId)
    }

    @Test fun routeCarIsUsedEvenWhenGlobalSelectionIsAnotherCar() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val source = Source().apply { globalCar = 99 }
        val vm = VampireViewModel(source)
        val store = ViewModelStore().apply { put("vampire", vm) }
        try {
            vm.load(7)
            runCurrent()
            assertEquals(listOf(7), source.requested)
            assertEquals("car 7", vm.uiState.value.idlePeriods.single().location)
        } finally { store.clear(); Dispatchers.resetMain() }
    }

    @Test fun uncancellableOldCarResultCannotReplaceNewCar() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val gate = CompletableDeferred<Unit>()
        val source = Source().apply { result = { car ->
            if (car == 1) withContext(NonCancellable) { gate.await() }
            ApiResult.Success(listOf(window(car)))
        } }
        val vm = VampireViewModel(source)
        val store = ViewModelStore().apply { put("vampire", vm) }
        try {
            vm.load(1); runCurrent()
            source.globalCar = 2
            vm.load(2); runCurrent()
            assertEquals("car 2", vm.uiState.value.idlePeriods.single().location)
            gate.complete(Unit); runCurrent()
            assertEquals("car 2", vm.uiState.value.idlePeriods.single().location)
        } finally { gate.complete(Unit); store.clear(); Dispatchers.resetMain() }
    }

    @Test fun accountScopeChangeImmediatelyRevokesPreviouslyPublishedWindows() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val source = Source()
        val vm = VampireViewModel(source)
        val store = ViewModelStore().apply { put("vampire", vm) }
        try {
            vm.load(1); runCurrent()
            assertTrue(vm.uiState.value.idlePeriods.isNotEmpty())
            source.result = { ApiResult.Error("synthetic offline", 503) }
            source.scopeChanges.value = source.scopeChanges.value!!.copy(accountNamespace = "B", sessionGeneration = 1)
            runCurrent()
            assertTrue(vm.uiState.value.idlePeriods.isEmpty())
            vm.selectWindow(StandbyRange.ALL_TIME)
            assertTrue(vm.uiState.value.idlePeriods.isEmpty())
        } finally { store.clear(); Dispatchers.resetMain() }
    }

    @Test fun failedReloadCannotBeRecalculatedBackIntoThePreviousProjection() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val source = Source()
        val vm = VampireViewModel(source)
        val store = ViewModelStore().apply { put("vampire", vm) }
        try {
            vm.load(1); runCurrent()
            source.result = { ApiResult.Error("synthetic offline", 503) }
            vm.load(1); runCurrent()
            assertTrue(vm.uiState.value.idlePeriods.isEmpty())
            vm.selectWindow(StandbyRange.ALL_TIME)
            assertTrue(vm.uiState.value.idlePeriods.isEmpty())
        } finally { store.clear(); Dispatchers.resetMain() }
    }

    @Test fun sameCarAccountAbaCannotPublishTheOldUncancellableResponse() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        val source = Source().apply { result = { car ->
            val attempt = ++calls
            if (attempt == 1) withContext(NonCancellable) { gate.await() }
            ApiResult.Success(listOf(window(car).copy(address = if (attempt == 1) "old A" else "fresh A")))
        } }
        val vm = VampireViewModel(source)
        val store = ViewModelStore().apply { put("vampire", vm) }
        try {
            vm.load(1); runCurrent()
            source.scopeChanges.value = source.scopeChanges.value!!.copy(accountNamespace = "B", sessionGeneration = 1)
            runCurrent()
            source.scopeChanges.value = source.scopeChanges.value!!.copy(accountNamespace = "A", sessionGeneration = 2)
            vm.load(1); runCurrent()
            assertEquals("fresh A", vm.uiState.value.idlePeriods.single().location)
            gate.complete(Unit); runCurrent()
            assertEquals("fresh A", vm.uiState.value.idlePeriods.single().location)
        } finally { gate.complete(Unit); store.clear(); Dispatchers.resetMain() }
    }

    @Test fun fallbackHistoryCannotBelongToAnotherVerifiedVehicleNamespace() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val source = Source().apply {
            result = { ApiResult.Success(emptyList()) }
            historyResult = {
                val context = VehicleContext(99, "A:99", -99, HistoryConnectionSource.CLOUD, "cloud")
                ApiResult.Success(UnifiedHistory(context, emptyList(), emptyList(), false, false).also {
                    it.verifiedReadContext = VerifiedHistoryReadContext(scopeChanges.value!!, context, false, null, false)
                })
            }
        }
        val vm = VampireViewModel(source)
        val store = ViewModelStore().apply { put("vampire", vm) }
        try {
            vm.load(1); runCurrent()
            assertTrue(vm.uiState.value.idlePeriods.isEmpty())
            assertEquals(HISTORY_IDENTITY_UNAVAILABLE, vm.uiState.value.error)
        } finally { store.clear(); Dispatchers.resetMain() }
    }

    companion object {
        private fun window(car: Int): StandbyWindowData {
            val end = OffsetDateTime.now(ZoneOffset.UTC).minusHours(1)
            return StandbyWindowData(end.minusHours(3).toString(), end.toString(), address = "car $car",
                durationSeconds = 10800, startBatteryLevel = 80, endBatteryLevel = 79, batteryDelta = -1)
        }
    }
}
