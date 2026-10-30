package com.matelink.ui.screens.drives

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.matelink.data.api.models.ParkedDetailData
import com.matelink.data.repository.ApiResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class ParkedDetailScopedLocalPublicationTest {
    private fun parked(address: String) = ParkedDetailData(
        olderDriveId = 10, newerDriveId = 11,
        startDate = "2026-10-23T20:59:00Z", endDate = "2026-10-23T22:20:00Z",
        address = address, source = "local_room_cache"
    )

    @Test fun scopeChangeRemovesOldLocalCardBeforeAnotherHistoryKeyPublishes() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val changes = MutableStateFlow(0)
        val first = CompletableDeferred<Unit>()
        val second = CompletableDeferred<Unit>()
        var epoch = 0
        var calls = 0
        val source = object : ParkedPageSource {
            override val scopeChanges = changes.map { Unit }
            override suspend fun identity(carId: Int): ParkedPageIdentity {
                val captured = epoch
                return ParkedPageIdentity({ captured == epoch }, -101 - captured)
            }
            override suspend fun localDetail(localHistoryCarId: Int, olderDriveId: Int, newerDriveId: Int) =
                parked("local-$localHistoryCarId")
            override suspend fun detail(carId: Int, olderDriveId: Int, newerDriveId: Int): ApiResult<ParkedDetailData> {
                val call = ++calls
                if (call == 1) first.await() else second.await()
                return ApiResult.Success(parked("cloud-$call"))
            }
        }
        val vm = ParkedDetailViewModel(source)
        try {
            vm.load(7, 10, 11)
            advanceUntilIdle()
            assertEquals("local--101", vm.uiState.value.data?.address)

            epoch++
            changes.value = epoch
            advanceUntilIdle()
            assertEquals("local--102", vm.uiState.value.data?.address)
            first.complete(Unit)
            advanceUntilIdle()
            assertEquals("local--102", vm.uiState.value.data?.address)
            second.complete(Unit)
            advanceUntilIdle()
            assertEquals("cloud-2", vm.uiState.value.data?.address)
            assertNull(vm.uiState.value.error)
        } finally {
            first.complete(Unit)
            second.complete(Unit)
            vm.viewModelScope.cancel()
            advanceUntilIdle()
            Dispatchers.resetMain()
        }
    }

    @Test fun mismatchedRemoteDrivePairCannotReplaceScopedLocalDetail() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val source = object : ParkedPageSource {
            override val scopeChanges = MutableStateFlow(0).map { Unit }
            override suspend fun identity(carId: Int) = ParkedPageIdentity({ true }, -101)
            override suspend fun localDetail(localHistoryCarId: Int, olderDriveId: Int, newerDriveId: Int) =
                parked("local")
            override suspend fun detail(carId: Int, olderDriveId: Int, newerDriveId: Int) =
                ApiResult.Success(parked("foreign").copy(olderDriveId = 999))
        }
        val vm = ParkedDetailViewModel(source)
        try {
            vm.load(7, 10, 11)
            advanceUntilIdle()
            assertEquals("local", vm.uiState.value.data?.address)
            assertEquals(true, vm.uiState.value.degraded)
            assertNull(vm.uiState.value.error)
        } finally {
            vm.viewModelScope.cancel()
            advanceUntilIdle()
            Dispatchers.resetMain()
        }
    }

    @Test fun networkExceptionRetainsScopedRoomFirstCardWithoutExposingThrowable() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val source = object : ParkedPageSource {
            override val scopeChanges = MutableStateFlow(0).map { Unit }
            override suspend fun identity(carId: Int) = ParkedPageIdentity({ true }, -101)
            override suspend fun localDetail(localHistoryCarId: Int, olderDriveId: Int, newerDriveId: Int) =
                parked("retained-room")
            override suspend fun detail(carId: Int, olderDriveId: Int, newerDriveId: Int): ApiResult<ParkedDetailData> =
                throw IllegalStateException("PRIVATE_ADDRESS_EXCEPTION_MUST_NOT_ESCAPE")
        }
        val vm = ParkedDetailViewModel(source)
        try {
            vm.load(7, 10, 11); advanceUntilIdle()
            assertEquals("retained-room", vm.uiState.value.data?.address)
            assertTrue(vm.uiState.value.degraded)
            assertFalse(vm.uiState.value.isLoading)
            assertNotNull(vm.uiState.value.syncWarning)
            assertNull(vm.uiState.value.error)
            assertFalse(vm.uiState.value.toString().contains("PRIVATE_ADDRESS_EXCEPTION"))
        } finally {
            vm.viewModelScope.cancel(); advanceUntilIdle(); Dispatchers.resetMain()
        }
    }

    @Test fun networkExceptionWithoutLocalReceiptShowsSafeUnavailableNotIdentityFailure() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val source = object : ParkedPageSource {
            override val scopeChanges = MutableStateFlow(0).map { Unit }
            override suspend fun identity(carId: Int) = ParkedPageIdentity({ true }, -101)
            override suspend fun detail(carId: Int, olderDriveId: Int, newerDriveId: Int): ApiResult<ParkedDetailData> =
                throw IllegalStateException("PRIVATE_EXCEPTION_MUST_NOT_ESCAPE")
        }
        val vm = ParkedDetailViewModel(source)
        try {
            vm.load(7, 10, 11); advanceUntilIdle()
            assertNull(vm.uiState.value.data)
            assertNotNull(vm.uiState.value.error)
            assertFalse(vm.uiState.value.error!!.contains("PRIVATE_EXCEPTION"))
        } finally {
            vm.viewModelScope.cancel(); advanceUntilIdle(); Dispatchers.resetMain()
        }
    }
}
