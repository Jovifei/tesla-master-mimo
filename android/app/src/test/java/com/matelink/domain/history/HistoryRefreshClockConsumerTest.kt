package com.matelink.domain.history

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.matelink.data.local.ChargeCostOverrideStore
import com.matelink.data.repository.UnifiedHistoryRepository
import com.matelink.data.sync.HistoryResourceFixture
import com.matelink.ui.screens.charges.ChargesViewModel
import com.matelink.ui.screens.drives.DrivesViewModel
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE)
class HistoryRefreshClockConsumerTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun bothRealViewModelsRefreshTheirDefaultWindowAcrossLocalMidnight() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val fixture = HistoryResourceFixture(File(temp.root, "history-clock.db"))
        var now = Instant.parse("2026-10-03T15:50:00Z")
        val clock = object : Clock() {
            override fun getZone(): ZoneId = ZoneId.of("Asia/Shanghai")
            override fun withZone(zone: ZoneId): Clock = Clock.fixed(now, zone)
            override fun instant(): Instant = now
        }
        var drives: DrivesViewModel? = null
        var charges: ChargesViewModel? = null
        try {
            fixture.initialize(cloud = true)
            val history = UnifiedHistoryRepository(fixture.apiRepo, fixture.vehicleContexts,
                fixture.db.driveSummaryDao(), fixture.db.chargeSummaryDao())
            val drivePage = DrivesViewModel(fixture.context, fixture.apiRepo, history, fixture.geocodingRepository,
                fixture.settings, fixture.db.driveSummaryDao(), fixture.vehicleContexts, clock, SavedStateHandle()).also { drives = it }
            val chargePage = ChargesViewModel(fixture.context, fixture.apiRepo, history, fixture.db.chargeSummaryDao(),
                fixture.settings, ChargeCostOverrideStore(fixture.db.chargeCostOverrideDao(), fixture.settings),
                fixture.db.aggregateDao(), fixture.vehicleContexts, clock, SavedStateHandle()).also { charges = it }
            drivePage.setCarId(1); chargePage.setCarId(1)
            assertEquals(LocalDate.of(2026, 9, 27), drivePage.uiState.value.startDate)
            assertEquals(LocalDate.of(2026, 10, 3), drivePage.uiState.value.endDate)
            assertEquals(LocalDate.of(2026, 9, 4), chargePage.uiState.value.startDate)
            assertEquals(LocalDate.of(2026, 10, 3), chargePage.uiState.value.endDate)

            now = Instant.parse("2026-10-03T16:10:00Z")
            drivePage.refresh(); chargePage.refresh()
            assertEquals(LocalDate.of(2026, 9, 28), drivePage.uiState.value.startDate)
            assertEquals(LocalDate.of(2026, 10, 4), drivePage.uiState.value.endDate)
            assertEquals(LocalDate.of(2026, 9, 5), chargePage.uiState.value.startDate)
            assertEquals(LocalDate.of(2026, 10, 4), chargePage.uiState.value.endDate)
        } finally {
            drives?.viewModelScope?.cancel(); charges?.viewModelScope?.cancel()
            runCurrent(); fixture.close(); Dispatchers.resetMain()
        }
    }
}
