package com.matelink.ui.screens.drives

import androidx.lifecycle.viewModelScope
import com.matelink.data.api.models.DriveBatteryDetails
import com.matelink.data.api.models.DriveData
import com.matelink.data.api.models.ParkedDetailData
import com.matelink.data.repository.ApiResult
import com.matelink.data.repository.toLocalSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class F3ParkedReceiptRegressionTest {
    private fun drive(id: Int, start: String, end: String, battery: DriveBatteryDetails? = null) = DriveData(
        id, startDate = start, endDate = end, batteryDetails = battery, source = "teslamate_archive",
        sourceInstanceId = "synthetic-A", qualityState = "observed", lengthUnit = "km").toLocalSummary(-71)!!

    @Test fun nullableReceiptSocCannotBeReplacedByRoomPrimitiveZero() {
        val older = drive(10, "2026-10-27T00:00:00Z", "2026-10-27T00:10:00Z")
        val newer = drive(11, "2026-10-27T02:00:00Z", "2026-10-27T02:10:00Z")
        val value = requireNotNull(buildLocalParkedDetail(10, 11, older, newer))
        assertNull(value.startBatteryLevel)
        assertNull(value.endBatteryLevel)
        assertNull(value.batteryDelta)
    }

    @Test fun explicitReceiptZeroRemainsKnown() {
        val older = drive(10, "2026-10-27T00:00:00Z", "2026-10-27T00:10:00Z", DriveBatteryDetails(10, 0))
        val newer = drive(11, "2026-10-27T02:00:00Z", "2026-10-27T02:10:00Z", DriveBatteryDetails(0, 0))
        val value = requireNotNull(buildLocalParkedDetail(10, 11, older, newer))
        assertEquals(0, value.startBatteryLevel)
        assertEquals(0, value.endBatteryLevel)
        assertEquals(0, value.batteryDelta)
    }

    private fun local() = ParkedDetailData(10, 11, "2026-10-27T00:10:00Z", "2026-10-27T02:00:00Z",
        address = "local", source = "local_room_cache")

    @Test fun sameNumericPairWithForeignWindowCannotReplaceVerifiedLocalWindow() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val source = object : ParkedPageSource {
            override val scopeChanges = emptyFlow<Unit>()
            override suspend fun identity(carId: Int) = ParkedPageIdentity({ true }, -71)
            override suspend fun localDetail(localHistoryCarId: Int, olderDriveId: Int, newerDriveId: Int) = local()
            override suspend fun detail(carId: Int, olderDriveId: Int, newerDriveId: Int) =
                ApiResult.Success(local().copy(startDate = "2026-10-26T00:10:00Z", address = "foreign-window"))
        }
        val vm = ParkedDetailViewModel(source)
        try {
            vm.load(7, 10, 11); runCurrent()
            assertEquals("local", vm.uiState.value.data?.address)
            assertTrue(vm.uiState.value.degraded)
        } finally { vm.viewModelScope.cancel(); Dispatchers.resetMain() }
    }
}
