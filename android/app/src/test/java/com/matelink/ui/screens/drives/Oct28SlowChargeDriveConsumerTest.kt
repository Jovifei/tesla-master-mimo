package com.matelink.ui.screens.drives

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.matelink.data.repository.ApiResult
import com.matelink.data.repository.ChineseLocation
import com.matelink.data.repository.HistoryDetailRepository
import com.matelink.data.repository.Oct28IndependentHistoryFixture
import com.matelink.data.repository.verifiedDetailIdentity
import com.matelink.data.api.models.DriveOdometerDetails
import com.matelink.data.sync.toSyncSummary
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
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

    @Test
    @Config(shadows = [Oct28GatedAmapReverseShadow::class])
    fun cachedDriveAddressResolvesWhileUnrelatedChargeReadIsStillPending() = runBlocking {
        val fixture = Oct28IndependentHistoryFixture(File(temp.root, "pending-charge-cached-address.db"))
        Dispatchers.setMain(UnconfinedTestDispatcher())
        var page: DrivesViewModel? = null
        try {
            fixture.initialize()
            fixture.resources.status = { 503 }
            val cached = fixture.oldDrive.copy(startAddress = null, endAddress = "已有终点",
                startLatitude = 31.2, startLongitude = 121.4)
            fixture.resources.db.driveSummaryDao().upsert(
                cached.toSyncSummary(fixture.originalContext.localHistoryCarId)!!)
            Oct28GatedAmapReverseShadow.lookup = { _, _ ->
                ChineseLocation("缓存行程起点", "CN", "China", "Synthetic", "Synthetic")
            }
            val vm = DrivesViewModel(fixture.resources.context, fixture.resources.apiRepo,
                fixture.repository(), fixture.resources.geocodingRepository, fixture.resources.settings,
                fixture.resources.db.driveSummaryDao(), fixture.resources.vehicleContexts,
                Clock.fixed(Instant.parse("2026-10-28T14:00:00Z"), ZoneId.of("Asia/Shanghai")), SavedStateHandle())
            page = vm
            vm.setCarId(Oct28IndependentHistoryFixture.REMOTE_CAR)
            withTimeout(10_000) { fixture.chargeEntered.await() }
            val state = withTimeout(10_000) {
                vm.uiState.first { it.drives.any { drive -> drive.driveId == cached.driveId && drive.startAddress == "缓存行程起点" } }
            }
            assertEquals("缓存行程起点", state.drives.first { it.driveId == cached.driveId }.startAddress)
            assertFalse(fixture.chargeCancelled.isCompleted)
        } finally {
            page?.viewModelScope?.coroutineContext?.get(Job)?.cancelAndJoin()
            if (fixture.chargeEntered.isCompleted) withTimeout(10_000) { fixture.chargeCancelled.await() }
            Oct28GatedAmapReverseShadow.lookup = { _, _ -> null }
            fixture.close()
            Dispatchers.resetMain()
        }
    }

    @Test
    @Config(shadows = [Oct28GatedAmapReverseShadow::class])
    fun cachedDriveAddressSurvivesUnrelatedChargeFailure() = runBlocking {
        val fixture = Oct28IndependentHistoryFixture(File(temp.root, "failed-charge-cached-address.db"))
        Dispatchers.setMain(UnconfinedTestDispatcher())
        var page: DrivesViewModel? = null
        try {
            fixture.initialize()
            fixture.holdCharges = false
            fixture.chargeFailure = ApiResult.Error("synthetic_charge_failure", 503)
            fixture.resources.status = { 503 }
            val cached = fixture.oldDrive.copy(startAddress = null, endAddress = "已有终点",
                startLatitude = 31.2, startLongitude = 121.4)
            fixture.resources.db.driveSummaryDao().upsert(
                cached.toSyncSummary(fixture.originalContext.localHistoryCarId)!!)
            Oct28GatedAmapReverseShadow.lookup = { _, _ ->
                ChineseLocation("缓存行程起点", "CN", "China", "Synthetic", "Synthetic")
            }
            val vm = DrivesViewModel(fixture.resources.context, fixture.resources.apiRepo,
                fixture.repository(), fixture.resources.geocodingRepository, fixture.resources.settings,
                fixture.resources.db.driveSummaryDao(), fixture.resources.vehicleContexts,
                Clock.fixed(Instant.parse("2026-10-28T14:00:00Z"), ZoneId.of("Asia/Shanghai")), SavedStateHandle())
            page = vm
            vm.setCarId(Oct28IndependentHistoryFixture.REMOTE_CAR)
            withTimeout(10_000) { fixture.chargeEntered.await() }
            withTimeout(10_000) {
                vm.uiState.first { it.drives.any { drive -> drive.driveId == cached.driveId && drive.startAddress == "缓存行程起点" } }
            }
            assertEquals(1, fixture.chargeCalls)
        } finally {
            page?.viewModelScope?.coroutineContext?.get(Job)?.cancelAndJoin()
            Oct28GatedAmapReverseShadow.lookup = { _, _ -> null }
            fixture.close()
            Dispatchers.resetMain()
        }
    }

    @Test
    @Config(shadows = [Oct28GatedAmapReverseShadow::class])
    fun newVerifiedDriveGetsAddressAndEnergyBeforeChargeReadCompletes() = runBlocking {
        val fixture = Oct28IndependentHistoryFixture(File(temp.root, "new-drive-before-charge.db"))
        Dispatchers.setMain(UnconfinedTestDispatcher())
        var page: DrivesViewModel? = null
        try {
            fixture.initialize()
            val id = 3001
            val start = "2026-10-28T13:20:00Z"
            val end = "2026-10-28T13:20:20Z"
            val incoming = fixture.newDrives.first().copy(driveId = id, startDate = start, endDate = end,
                durationMin = 1, sourceRecordId = "synthetic-drive-$id",
                odometerDetails = DriveOdometerDetails(distance = 2.0),
                startAddress = null, endAddress = "已有终点", startLatitude = 31.2, startLongitude = 121.4)
            val records = (listOf(fixture.oldDrive) + fixture.newDrives + incoming).associateBy { it.driveId }
            fun matchingDetail(path: String) = records.values.firstOrNull { path.endsWith("/drives/${it.driveId}") }
            fixture.resources.status = { path -> if (matchingDetail(path) != null) 200 else 503 }
            fixture.resources.detailResponse = { path -> matchingDetail(path)?.let { drive ->
                val points = if (drive.driveId == id)
                    """[{"date":"$start","power":36},{"date":"$end","power":36}]"""
                else "[]"
                """{"data":{"units":{"unit_of_length":"km"},"drive":{
                "drive_id":${drive.driveId},"start_date":"${drive.startDate}","end_date":"${drive.endDate}",
                "duration_min":${drive.durationMin},"source":"${drive.source}",
                "source_instance_id":"${drive.sourceInstanceId}","source_record_id":"${drive.sourceRecordId}",
                "odometer_details":{"odometer_distance":2.0},"drive_details":$points}}}""".trimIndent()
            } }
            Oct28GatedAmapReverseShadow.lookup = { _, _ ->
                ChineseLocation("新行程起点", "CN", "China", "Synthetic", "Synthetic")
            }
            val vm = DrivesViewModel(fixture.resources.context, fixture.resources.apiRepo,
                fixture.repository(), fixture.resources.geocodingRepository, fixture.resources.settings,
                fixture.resources.db.driveSummaryDao(), fixture.resources.vehicleContexts,
                Clock.fixed(Instant.parse("2026-10-28T14:00:00Z"), ZoneId.of("Asia/Shanghai")),
                SavedStateHandle(), fixture.resources.db, HistoryDetailRepository(fixture.resources.db.historyDetailDao()))
            page = vm
            vm.setCarId(Oct28IndependentHistoryFixture.REMOTE_CAR)
            withTimeout(10_000) { fixture.chargeEntered.await() }
            fixture.resources.db.driveSummaryDao().upsert(incoming.toSyncSummary(fixture.originalContext.localHistoryCarId)!!)
            val state = withTimeout(20_000) {
                vm.uiState.first { it.drives.any { drive -> drive.driveId == id && drive.startAddress == "新行程起点" } &&
                    it.driveMetrics[id]?.energyKwh != null }
            }
            assertEquals(0.2, state.driveMetrics[id]!!.energyKwh!!, 1e-9)
            assertTrue(synchronized(fixture.resources.calls) {
                fixture.resources.calls.any { it.endsWith("/drives/$id") }
            })
            assertFalse("The unrelated charge request must still be pending", fixture.chargeCancelled.isCompleted)
        } finally {
            page?.viewModelScope?.coroutineContext?.get(Job)?.cancelAndJoin()
            if (fixture.chargeEntered.isCompleted) withTimeout(10_000) { fixture.chargeCancelled.await() }
            Oct28GatedAmapReverseShadow.lookup = { _, _ -> null }
            fixture.close()
            Dispatchers.resetMain()
        }
    }

    @Test fun verifiedCachedDriveGetsEnergyBeforeUnrelatedChargeCompletes() = runBlocking {
        val fixture = Oct28IndependentHistoryFixture(File(temp.root, "cached-drive-energy-before-charge.db"))
        Dispatchers.setMain(UnconfinedTestDispatcher())
        var page: DrivesViewModel? = null
        try {
            fixture.initialize()
            val id = fixture.oldDrive.driveId
            val start = "2026-10-28T13:30:00Z"
            val end = "2026-10-28T13:30:20Z"
            val cached = fixture.oldDrive.copy(startDate = start, endDate = end, durationMin = 1,
                odometerDetails = DriveOdometerDetails(distance = 2.0))
            val row = cached.toSyncSummary(fixture.originalContext.localHistoryCarId)!!
            fixture.resources.db.driveSummaryDao().upsert(row)
            val detailStore = HistoryDetailRepository(fixture.resources.db.historyDetailDao())
            val identity = row.verifiedDetailIdentity(fixture.originalContext)!!
            assertNull(detailStore.readDrive(identity).detail)
            fixture.resources.status = { path -> if (path.endsWith("/drives/$id")) 200 else 503 }
            fixture.resources.detailResponse = { path -> if (path.endsWith("/drives/$id")) """
                {"data":{"units":{"unit_of_length":"km"},"drive":{
                "drive_id":$id,"start_date":"$start","end_date":"$end","duration_min":1,
                "source":"teslamate_archive","source_instance_id":"synthetic-oct28-instance",
                "source_record_id":"synthetic-drive-$id","odometer_details":{"odometer_distance":2.0},
                "drive_details":[{"date":"$start","power":36},{"date":"$end","power":36}]}}}
            """.trimIndent() else null }
            val vm = DrivesViewModel(fixture.resources.context, fixture.resources.apiRepo,
                fixture.repository(), fixture.resources.geocodingRepository, fixture.resources.settings,
                fixture.resources.db.driveSummaryDao(), fixture.resources.vehicleContexts,
                Clock.fixed(Instant.parse("2026-10-28T14:00:00Z"), ZoneId.of("Asia/Shanghai")),
                SavedStateHandle(), fixture.resources.db, detailStore)
            page = vm
            vm.setCarId(Oct28IndependentHistoryFixture.REMOTE_CAR)
            withTimeout(10_000) { fixture.chargeEntered.await() }
            val state = withTimeout(20_000) { vm.uiState.first { it.driveMetrics[id]?.energyKwh != null } }
            assertEquals(0.2, state.driveMetrics[id]!!.energyKwh!!, 1e-9)
            assertFalse("The unrelated charge request must still be pending", fixture.chargeCancelled.isCompleted)
            assertEquals(1, fixture.resources.calls.count { it.endsWith("/drives/$id") })
        } finally {
            page?.viewModelScope?.coroutineContext?.get(Job)?.cancelAndJoin()
            if (fixture.chargeEntered.isCompleted) withTimeout(10_000) { fixture.chargeCancelled.await() }
            fixture.close()
            Dispatchers.resetMain()
        }
    }
}
