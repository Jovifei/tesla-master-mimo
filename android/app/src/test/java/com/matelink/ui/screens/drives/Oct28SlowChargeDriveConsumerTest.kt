package com.matelink.ui.screens.drives

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.matelink.data.repository.ApiResult
import com.matelink.data.repository.Oct28IndependentHistoryFixture
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Actual list VM reopens the same scoped disk cache after the unrelated endpoint timed out. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE)
class Oct28SlowChargeDriveConsumerTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun samePendingChargeReadPublishesTheSuccessfulOct28DrivesBeforeItCompletes() = runBlocking {
        val fixture = Oct28IndependentHistoryFixture(File(temp.root, "independent-pending-consumer.db"))
        Dispatchers.setMain(UnconfinedTestDispatcher())
        var page: DrivesViewModel? = null
        try {
            fixture.initialize()
            fixture.resources.status = { 503 }
            val viewModel = DrivesViewModel(fixture.resources.context, fixture.resources.apiRepo,
                fixture.repository(), fixture.resources.geocodingRepository, fixture.resources.settings,
                fixture.resources.db.driveSummaryDao(), fixture.resources.vehicleContexts,
                Clock.fixed(Instant.parse("2026-10-28T14:00:00Z"), ZoneId.of("Asia/Shanghai")),
                SavedStateHandle())
            page = viewModel
            viewModel.setCarId(Oct28IndependentHistoryFixture.REMOTE_CAR)
            withTimeout(10_000) { fixture.chargeEntered.await() }
            fixture.assertOct28DrivesPersistedInTheVerifiedCar()

            val expected = fixture.newDrives.map { it.driveId }.toSet() + fixture.oldDrive.driveId
            // Allow Room invalidation delivery without advancing the VM's virtual 20s timeout.
            // The charge request remains the same unfinished request throughout this assertion.
            val state = withTimeoutOrNull(10_000) {
                viewModel.uiState.first { it.drives.map { drive -> drive.driveId }.toSet() == expected }
            } ?: viewModel.uiState.value
            assertEquals(1, fixture.driveCalls)
            assertEquals(1, fixture.chargeCalls)
            assertFalse("Charge must still be pending when the new drive list publishes",
                fixture.chargeCancelled.isCompleted)
            assertEquals("Persisted successful drives must publish while this same charge read is pending",
                expected, state.drives.map { it.driveId }.toSet())
        } finally {
            page?.viewModelScope?.cancel()
            if (fixture.chargeEntered.isCompleted) withTimeout(10_000) { fixture.chargeCancelled.await() }
            fixture.close()
            Dispatchers.resetMain()
        }
    }

    @Test fun sameScopeListReopenReadsTheThreeOct28DrivesDespiteChargeTimeout() = runBlocking {
        val fixture = Oct28IndependentHistoryFixture(File(temp.root, "independent-drive-consumer.db"))
        Dispatchers.setMain(UnconfinedTestDispatcher())
        var page: DrivesViewModel? = null
        try {
            fixture.initialize()
            val result = fixture.loadUntilChargeTimeout()
            fixture.assertUnavailableChargeWasNotPromoted(result)

            fixture.resources.reopen()
            assertEquals(fixture.originalScope, fixture.resources.vehicleContexts.captureReadScope())
            // The reopen must succeed from Room; another remote read cannot repair this assertion.
            fixture.identityError = ApiResult.Error("synthetic_identity_unavailable", 503)
            fixture.resources.status = { 503 }
            val viewModel = DrivesViewModel(fixture.resources.context, fixture.resources.apiRepo,
                fixture.repository(), fixture.resources.geocodingRepository, fixture.resources.settings,
                fixture.resources.db.driveSummaryDao(), fixture.resources.vehicleContexts,
                Clock.fixed(Instant.parse("2026-10-28T14:00:00Z"), ZoneId.of("Asia/Shanghai")),
                SavedStateHandle())
            page = viewModel
            viewModel.setCarId(Oct28IndependentHistoryFixture.REMOTE_CAR)
            val state = withTimeout(10_000) {
                viewModel.uiState.first { it.historySyncWarning == "history_cached" && !it.isLoading }
            }

            assertEquals("The same verified vehicle's Room-first list must show 08/19/21 drives",
                fixture.newDrives.map { it.driveId }.toSet() + fixture.oldDrive.driveId,
                state.drives.map { it.driveId }.toSet())
            assertEquals(1, fixture.driveCalls)
            assertEquals(1, fixture.chargeCalls)
            fixture.assertOct28DrivesPersistedInTheVerifiedCar()
        } finally {
            page?.viewModelScope?.cancel()
            fixture.close()
            Dispatchers.resetMain()
        }
    }
}
