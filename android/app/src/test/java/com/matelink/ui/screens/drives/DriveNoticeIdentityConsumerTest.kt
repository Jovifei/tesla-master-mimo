package com.matelink.ui.screens.drives

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.matelink.data.local.entity.DriveSummary
import com.matelink.data.repository.UnifiedHistoryRepository
import com.matelink.data.repository.VerifiedHistoryReadContext
import com.matelink.data.sync.HistoryResourceFixture
import java.io.File
import java.time.Clock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE)
class DriveNoticeIdentityConsumerTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun verifiedVehicleTransitionPublishesPendingAndIdentityTogether() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val fixture = HistoryResourceFixture(File(temp.root, "drive-notice-identity.db"))
        val gate = CompletableDeferred<Unit>()
        var page: DrivesViewModel? = null
        try {
            fixture.initialize()
            val scope = fixture.vehicleContexts.captureReadScope()
            val first = requireNotNull(fixture.vehicleContexts.cachedContextForRemote(1, scope))
            val second = requireNotNull(fixture.vehicleContexts.cachedContextForRemote(2, scope))
            val vm = DrivesViewModel(fixture.context, fixture.apiRepo,
                UnifiedHistoryRepository(fixture.apiRepo, fixture.vehicleContexts,
                    fixture.db.driveSummaryDao(), fixture.db.chargeSummaryDao()),
                fixture.geocodingRepository, fixture.settings, fixture.db.driveSummaryDao(),
                fixture.vehicleContexts, Clock.systemUTC(), SavedStateHandle())
            page = vm

            val adopt = DrivesViewModel::class.java.getDeclaredMethod("adoptDriveHistoryProof",
                VerifiedHistoryReadContext::class.java, MutableMap::class.java).apply { isAccessible = true }
            fun adoptVehicle(context: com.matelink.data.local.VehicleContext) = adopt.invoke(vm,
                VerifiedHistoryReadContext(scope, context, true, null, true), mutableMapOf<Int, DriveSummary>())

            adoptVehicle(first)
            assertTrue(vm.uiState.value.localArchiveLinkPending)
            assertEquals(first.localHistoryCarId, vm.uiState.value.historyNoticeLocalCarId)
            adoptVehicle(second)
            assertTrue(vm.uiState.value.localArchiveLinkPending)
            assertEquals(second.localHistoryCarId, vm.uiState.value.historyNoticeLocalCarId)

            fixture.onRequest = { gate.await() }
            vm.setCarId(1)
            assertFalse(vm.uiState.value.localArchiveLinkPending)
            assertNull(vm.uiState.value.historyNoticeLocalCarId)
        } finally {
            gate.complete(Unit)
            page?.viewModelScope?.coroutineContext?.get(Job)?.cancelAndJoin()
            fixture.close()
            Dispatchers.resetMain()
        }
    }
}
