package com.matelink.ui.screens.drives

import androidx.lifecycle.viewModelScope
import com.matelink.data.api.models.ParkedDetailData
import com.matelink.data.repository.ApiResult
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

/** Real VM persistence orchestration; database byte/digest proof is tested separately. */
@OptIn(ExperimentalCoroutinesApi::class)
class ParkedReceiptPublicationRegressionTest {
    private fun local() = ParkedDetailData(10, 11, "2026-10-27T00:10:00Z", "2026-10-27T02:00:00Z",
        address="local", source="local_room_cache")

    @Test fun verifiedServerReceiptIsSavedAndReplayedAheadOfAnOfflineFailure() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var stored: ParkedDetailData? = null
        var offline = false
        val rich = local().copy(address="received", outsideTempAverage=20.0, sampleCount=12, coverageRatio=1.0)
        val source = object : ParkedPageSource {
            override val scopeChanges = emptyFlow<Unit>()
            override suspend fun identity(carId: Int) = ParkedPageIdentity({ true }, -71)
            override suspend fun localDetail(localHistoryCarId: Int, olderDriveId: Int, newerDriveId: Int) = local()
            override suspend fun readReceipt(identity: ParkedPageIdentity, local: ParkedDetailData) = stored
            override suspend fun saveReceipt(identity: ParkedPageIdentity, local: ParkedDetailData,
                received: ParkedDetailData, ensureCurrent: () -> Unit): Boolean {
                ensureCurrent(); stored = received; return true
            }
            override suspend fun detail(carId: Int, olderDriveId: Int, newerDriveId: Int): ApiResult<ParkedDetailData> =
                if (offline) ApiResult.Error("synthetic offline", 503) else ApiResult.Success(rich)
        }
        val first = ParkedDetailViewModel(source)
        var second: ParkedDetailViewModel? = null
        try {
            first.load(7, 10, 11); runCurrent()
            assertNotNull("a verified network receipt must reach persistent storage", stored)
            first.viewModelScope.cancel()
            offline = true
            val page = ParkedDetailViewModel(source)
            second = page
            page.load(7, 10, 11); runCurrent()
            assertEquals("received", page.uiState.value.data?.address)
            assertEquals(20.0, page.uiState.value.data?.outsideTempAverage ?: Double.NaN, 0.0)
            assertEquals(12, page.uiState.value.data?.sampleCount)
        } finally {
            first.viewModelScope.cancel(); second?.viewModelScope?.cancel(); Dispatchers.resetMain()
        }
    }
}
