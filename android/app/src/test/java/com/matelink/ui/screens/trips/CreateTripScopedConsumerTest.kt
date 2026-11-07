package com.matelink.ui.screens.trips

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.matelink.data.api.models.DriveData
import com.matelink.data.local.entity.SavedTripLeg
import com.matelink.data.sync.HistoryResourceFixture
import com.matelink.data.sync.toSyncSummary
import com.matelink.domain.LegRef
import com.matelink.domain.TripDetector
import com.matelink.domain.TripRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class CreateTripScopedConsumerTest {
    @get:Rule val temporary = TemporaryFolder()

    private suspend fun fixture(name: String): HistoryResourceFixture {
        val f = HistoryResourceFixture(File(temporary.root, "$name.db"))
        f.initialize(cloud = true)
        val row = DriveData(7, startDate = "2026-10-14T10:00:00Z",
            endDate = "2026-10-14T11:00:00Z", durationMin = 60,
            source = "teslamate_archive", qualityState = "observed")
        f.db.driveSummaryDao().upsert(requireNotNull(row.toSyncSummary(f.localId(1))))
        return f
    }

    private fun viewModel(f: HistoryResourceFixture) = CreateTripViewModel(
        TripRepository(f.db.driveSummaryDao(), f.db.chargeSummaryDao(), f.db.aggregateDao(),
            f.db.savedTripDao(), TripDetector()), f.db.aggregateDao(), f.apiRepo, f.vehicleContexts)

    @Test fun candidatePickerReadsTheVerifiedLocalVehicleNamespace() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val f = fixture("create-trip-scoped")
        val vm = viewModel(f)
        try {
            vm.start(1)
            vm.onDatePicked(LocalDate.of(2026, 10, 14))
            advanceUntilIdle()
            val state = withContext(Dispatchers.Default) {
                withTimeout(15_000) { vm.uiState.first { it.eligibleLegs != null } }
            }
            assertEquals(listOf(7), state.eligibleLegs!!.drives.map { it.driveId })
            assertEquals(setOf(f.localId(1)), state.eligibleLegs!!.drives.map { it.carId }.toSet())
        } finally {
            vm.viewModelScope.cancel(); f.close(); Dispatchers.resetMain()
        }
    }

    @Test fun accountChangeClearsTheOldDraftBeforeAnotherVehicleCanUseIt() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val f = fixture("create-trip-account")
        val vm = viewModel(f)
        try {
            vm.start(1)
            advanceUntilIdle()
            vm.setName("private old draft")
            f.sessions.save("synthetic-new", "synthetic-new-refresh", 3600, "other-account")
            advanceUntilIdle()
            assertEquals("", vm.uiState.value.name)
            assertEquals(emptyList<LegRef>(), vm.uiState.value.draftLegs)
            assertNull(vm.uiState.value.preview)
        } finally {
            vm.viewModelScope.cancel(); f.close(); Dispatchers.resetMain()
        }
    }
}
