package com.matelink.ui.screens.drives

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.matelink.R
import com.matelink.data.api.TeslamateApi
import com.matelink.data.api.models.DriveData
import com.matelink.data.api.models.DriveOdometerDetails
import com.matelink.data.local.ConnectionMode
import com.matelink.data.repository.UnifiedHistoryRepository
import com.matelink.data.repository.AmapReverseGeocoder
import com.matelink.data.repository.ChineseLocation
import com.matelink.data.repository.ChineseLocationAvailability
import com.matelink.data.sync.HistoryResourceFixture
import com.matelink.data.sync.toSyncSummary
import com.matelink.domain.analytics.HistorySummaryEvidenceCodec
import com.matelink.ui.components.historyReadWarningMessageRes
import com.matelink.di.TeslamateApiFactory
import com.squareup.moshi.Moshi
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.junit.runners.model.MultipleFailureException
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.startCoroutineUninterceptedOrReturn

/** Synthetic October 28 receipts exercise the actual VM and a reopened temporary Room database. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE)
class Oct28LiveRoomDriveConsumerTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun threeNewSameScopeDrivesUpdateTheActualListAndSummaryWithoutRefresh() = runBlocking {
        withObservedPage("oct28-new-ids.db") { fixture, page, localCarId ->
            val additions = listOf(
                drive(101, "2026-10-28", 8, 3.0),
                drive(102, "2026-10-28", 19, 4.0),
                drive(103, "2026-10-28", 21, 5.0)
            )
            fixture.db.driveSummaryDao().upsertAll(additions.map { requireNotNull(it.toSyncSummary(localCarId)) })
            val stored = fixture.db.driveSummaryDao().getAllChronologicalForAdjacency(localCarId)
            for (addition in additions) {
                val row = stored.single { it.driveId == addition.driveId }
                val receipt = requireNotNull(HistorySummaryEvidenceCodec.decodeDrive(requireNotNull(row.apiEvidence)))
                assertEquals(localCarId, row.carId)
                assertEquals(addition.source, receipt.source)
                assertEquals(addition.sourceInstanceId, receipt.sourceInstanceId)
                assertEquals(addition.startDate, receipt.startDate)
                assertEquals(addition.endDate, receipt.endDate)
            }

            // No setCarId, date-filter change, manual refresh, or simulated lifecycle restart.
            val expectedIds = listOf(103, 102, 101, CONTROL_ID, BASE_ID)
            val updated = withTimeoutOrNull(3_000) {
                page.uiState.first { state -> state.drives.map { it.driveId } == expectedIds }
            } ?: page.uiState.value
            assertEquals("new same-scope Room IDs must reach the actual list", expectedIds,
                updated.drives.map { it.driveId })
            assertEquals(5, updated.summary.totalDrives)
            assertEquals(17.0, requireNotNull(updated.summary.totalDistanceKm), 0.0001)
            assertEquals(53, updated.summary.totalDurationMin)
            assertEquals(expectedIds, updated.historySequence.map { it.driveId })
        }
    }

    @Test fun anotherLocalVehicleCannotPublishEvenWhenItsNumericDriveIdCollides() = runBlocking {
        withObservedPage("oct28-other-car.db") { fixture, page, localCarId ->
            val otherLocalCarId = fixture.localId(2)
            assertTrue(localCarId != otherLocalCarId)
            val foreign = listOf(
                drive(BASE_ID, "2026-10-28", 8, 90.0),
                drive(201, "2026-10-28", 19, 91.0),
                drive(202, "2026-10-28", 21, 92.0)
            )
            fixture.db.driveSummaryDao().upsertAll(
                foreign.map { requireNotNull(it.toSyncSummary(otherLocalCarId)) } +
                    requireNotNull(control(4.0).toSyncSummary(localCarId))
            )
            val updated = awaitControl(page, 4.0)
            assertEquals(setOf(BASE_ID, CONTROL_ID), updated.drives.map { it.driveId }.toSet())
            assertEquals(base().startDate, updated.drives.single { it.driveId == BASE_ID }.startDate)
            assertEquals(2, updated.summary.totalDrives)
            assertEquals(6.0, requireNotNull(updated.summary.totalDistanceKm), 0.0001)
            assertFalse(updated.historySequence.any { it.driveId == 201 || it.driveId == 202 })
            assertEquals(3, fixture.db.driveSummaryDao().getAllChronologicalForAdjacency(otherLocalCarId).size)
        }
    }

    @Test fun sameNumericIdWithAnotherSourceCannotReplaceTheDisplayedSession() = runBlocking {
        withObservedPage("oct28-other-source.db") { fixture, page, localCarId ->
            assertReplacementRejected(fixture, page, localCarId, base().copy(
                source = "fleet_telemetry", sourceInstanceId = "synthetic-fleet-B",
                odometerDetails = DriveOdometerDetails(distance = 99.0)
            ))
        }
    }

    @Test fun sameNumericIdWithAnotherWindowCannotReplaceTheDisplayedSession() = runBlocking {
        withObservedPage("oct28-other-window.db") { fixture, page, localCarId ->
            assertReplacementRejected(fixture, page, localCarId,
                drive(BASE_ID, "2026-10-28", 21, 99.0))
        }
    }

    @Test fun aNewQuarantinedMiddleRowBlocksParkingWithoutBecomingAVisibleRoute() = runBlocking {
        withObservedPage("oct28-new-quarantine.db") { fixture, page, localCarId ->
            assertTrue(BASE_ID to CONTROL_ID in page.uiState.value.parkedPairs)
            val hidden = drive(90, "2026-10-27", 13, 1.0).copy(qualityState = "quarantined")
            fixture.db.driveSummaryDao().upsertAll(listOf(
                requireNotNull(hidden.toSyncSummary(localCarId)),
                requireNotNull(control(4.0).toSyncSummary(localCarId))
            ))
            val updated = awaitControl(page, 4.0)
            assertEquals(listOf(CONTROL_ID, 90, BASE_ID), updated.historySequence.map { it.driveId })
            assertFalse(updated.drives.any { it.driveId == 90 })
            assertFalse(BASE_ID to CONTROL_ID in updated.parkedPairs)
            assertTrue(selectVisibleDriveHistoryItems(updated.historySequence, updated.drives, updated.parkedPairs)
                .none { it is DriveHistoryItem.Parked })
            assertEquals(2, updated.summary.totalDrives)
            assertEquals(6.0, requireNotNull(updated.summary.totalDistanceKm), 0.0001)
        }
    }

    @Test fun aNewlyAdmittedIdKeepsItsFirstSourceInstanceAndWindowBaseline() = runBlocking {
        withObservedPage("oct28-new-id-baseline.db") { fixture, page, localCarId ->
            val admitted = drive(301, "2026-10-28", 8, 3.0)
            fixture.db.driveSummaryDao().upsertAll(listOf(requireNotNull(admitted.toSyncSummary(localCarId))))
            withTimeout(10_000) { page.uiState.first { state -> state.drives.any { it.driveId == 301 } } }
            val replacements = listOf(
                admitted.copy(source = "fleet_telemetry"),
                admitted.copy(sourceInstanceId = "other-archive-instance"),
                drive(301, "2026-10-28", 21, 99.0),
                admitted.copy(sourceRecordId = "other-physical-record")
            )
            replacements.forEachIndexed { index, replacement ->
                val controlDistance = 4.0 + index
                fixture.db.driveSummaryDao().upsertAll(listOf(
                    requireNotNull(replacement.toSyncSummary(localCarId)),
                    requireNotNull(control(controlDistance).toSyncSummary(localCarId))
                ))
                val updated = awaitControl(page, controlDistance)
                val actual = updated.drives.single { it.driveId == 301 }
                assertEquals(admitted.source, actual.source)
                assertEquals(admitted.sourceInstanceId, actual.sourceInstanceId)
                assertEquals(admitted.sourceRecordId, actual.sourceRecordId)
                assertEquals(admitted.startDate, actual.startDate)
                assertEquals(admitted.endDate, actual.endDate)
                assertEquals(3.0, requireNotNull(actual.distanceKm), 0.0001)
                assertEquals(3, updated.summary.totalDrives)
                assertEquals(5.0 + controlDistance, requireNotNull(updated.summary.totalDistanceKm), 0.0001)
            }
        }
    }

    @Test fun sourceLessReceiptsPreserveRealZeroAndUnknownFieldsWithoutInventingASource() = runBlocking {
        withObservedPage("oct28-legacy-zero-unknown.db") { fixture, page, localCarId ->
            val zero = drive(401, "2026-10-28", 8, 0.0).copy(
                source = null, sourceInstanceId = null, qualityState = "unknown", durationMin = 0,
                endDate = "2026-10-28T08:00:00+08:00")
            val unknown = drive(402, "2026-10-28", 19, 1.0).copy(
                source = null, sourceInstanceId = null, qualityState = "unknown",
                durationMin = null, odometerDetails = null)
            fixture.db.driveSummaryDao().upsertAll(listOf(zero, unknown).map {
                requireNotNull(it.toSyncSummary(localCarId))
            })
            val updated = withTimeout(10_000) {
                page.uiState.first { state -> state.drives.any { it.driveId == 401 } && state.drives.any { it.driveId == 402 } }
            }
            val actualZero = updated.drives.single { it.driveId == 401 }
            val actualUnknown = updated.drives.single { it.driveId == 402 }
            assertNull(actualZero.source)
            assertNull(actualUnknown.source)
            assertEquals(0.0, requireNotNull(actualZero.distanceKm), 0.0)
            assertEquals(0, actualZero.durationMin)
            assertNull(actualUnknown.distanceKm)
            assertNull(actualUnknown.durationMin)
            assertNull(actualZero.netEnergyKwh)
            assertNull(actualUnknown.netEnergyKwh)
            assertEquals(2, updated.summary.totalDrives)
            assertEquals(5.0, requireNotNull(updated.summary.totalDistanceKm), 0.0001)
        }
    }

    @Test fun aNewIdNeedsAMatchingReceiptAndUnverifiedRowsCannotProveParking() = runBlocking {
        withObservedPage("oct28-mismatched-receipts.db") { fixture, page, localCarId ->
            assertTrue(BASE_ID to CONTROL_ID in page.uiState.value.parkedPairs)
            val wrongId = requireNotNull(drive(501, "2026-10-28", 8, 3.0).toSyncSummary(localCarId))
                .copy(driveId = 601)
            val wrongWindow = requireNotNull(drive(602, "2026-10-28", 19, 4.0).toSyncSummary(localCarId))
                .copy(startDate = "2026-10-28T20:00:00+08:00", endDate = "2026-10-28T20:10:00+08:00")
            val noReceipt = requireNotNull(drive(603, "2026-10-28", 21, 5.0).toSyncSummary(localCarId))
                .copy(apiEvidence = null)
            fixture.db.driveSummaryDao().upsertAll(listOf(wrongId, wrongWindow, noReceipt,
                requireNotNull(control(4.0).toSyncSummary(localCarId))))
            val updated = awaitControl(page, 4.0)
            assertEquals(setOf(BASE_ID, CONTROL_ID), updated.drives.map { it.driveId }.toSet())
            assertEquals(2, updated.summary.totalDrives)
            assertEquals(6.0, requireNotNull(updated.summary.totalDistanceKm), 0.0001)
            assertTrue(updated.parkedPairs.isEmpty())
        }
    }

    @Test fun freshIdentityDiscoveryPublishesDrivesWhileChargesWaitAndAfterTheirTimeout() = runBlocking {
        val scheduler = TestCoroutineScheduler()
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        val fixture = HistoryResourceFixture(File(temp.root, "oct28-genuinely-unmapped.db"))
        val identityEntered = CompletableDeferred<Unit>()
        val identityGate = CompletableDeferred<Unit>()
        val chargesEntered = CompletableDeferred<Unit>()
        val chargeGate = CompletableDeferred<Unit>()
        var page: DrivesViewModel? = null
        try {
            // Unlike initialize(), this never resolves or stores any vehicle mapping.
            initializeWithoutVehicleMappings(fixture)
            assertNull(fixture.vehicleContexts.cachedContextForRemote(1))
            assertNull(fixture.vehicleContexts.cachedContextForRemote(2))
            val remote = drive(701, "2026-10-28", 8, 3.0)
            fixture.detailResponse = { path -> if (path.endsWith("/drives")) driveResponse(remote) else null }
            fixture.onRequest = { path ->
                when {
                    path.endsWith("/cars") -> { identityEntered.complete(Unit); identityGate.await() }
                    path.endsWith("/charges") -> { chargesEntered.complete(Unit); chargeGate.await() }
                }
            }
            // Unmapped numeric IDs are not authority to read a differently scoped local row.
            fixture.db.driveSummaryDao().upsertAll(listOf(requireNotNull(
                drive(999, "2026-10-28", 19, 99.0).toSyncSummary(1))))
            val vm = createPage(fixture)
            page = vm
            vm.setCarId(1)
            withTimeout(10_000) { identityEntered.await() }
            assertNull(fixture.vehicleContexts.cachedContextForRemote(1))
            assertTrue(vm.uiState.value.drives.isEmpty())
            assertFalse(fixture.calls.any { it.endsWith("/drives") })
            identityGate.complete(Unit)
            withTimeout(10_000) { chargesEntered.await() }
            val context = requireNotNull(fixture.vehicleContexts.cachedContextForRemote(1))
            assertTrue(context.localHistoryCarId < 0)
            assertEquals(listOf(701), fixture.db.driveSummaryDao()
                .getAllChronologicalForAdjacency(context.localHistoryCarId).map { it.driveId })
            val displayed = withTimeoutOrNull(3_000) {
                vm.uiState.first { it.drives.map { drive -> drive.driveId } == listOf(701) }
            } ?: vm.uiState.value
            assertFalse(chargeGate.isCompleted)
            assertEquals("a first verified mapping must subscribe before the charge result", listOf(701),
                displayed.drives.map { it.driveId })
            assertEquals(1, displayed.summary.totalDrives)
            assertEquals(3.0, requireNotNull(displayed.summary.totalDistanceKm), 0.0001)
            assertFalse(displayed.drives.any { it.driveId == 999 })

            // Exercise the real VM timeout without spending twenty seconds of wall time.
            scheduler.advanceTimeBy(20_001)
            scheduler.runCurrent()
            val timedOut = withTimeout(3_000) { vm.uiState.first { it.historySyncWarning != null } }
            assertFalse(chargeGate.isCompleted)
            assertEquals(listOf(701), timedOut.drives.map { it.driveId })
            assertEquals(1, timedOut.summary.totalDrives)
            assertEquals(3.0, requireNotNull(timedOut.summary.totalDistanceKm), 0.0001)
            assertEquals("history_sync_timeout", timedOut.historySyncWarning)
            assertEquals(R.string.history_sync_timeout,
                historyReadWarningMessageRes(requireNotNull(timedOut.historySyncWarning)))
        } finally {
            identityGate.complete(Unit)
            chargeGate.complete(Unit)
            page?.viewModelScope?.coroutineContext?.get(Job)?.cancelAndJoin()
            fixture.close()
            Dispatchers.resetMain()
        }
    }

    @Test fun rejectedRemoteIdCollisionsCannotEnrichTheOldSessionsAddresses() = runBlocking {
        val collisions = listOf(
            base().copy(source = "fleet_telemetry"),
            base().copy(sourceInstanceId = "remote-other-instance"),
            drive(BASE_ID, "2026-10-28", 21, 99.0)
        )
        collisions.forEachIndexed { index, collision ->
            Dispatchers.setMain(UnconfinedTestDispatcher())
            val fixture = HistoryResourceFixture(File(temp.root, "oct28-remote-address-collision-$index.db"))
            val drivesEntered = CompletableDeferred<Unit>()
            val drivesGate = CompletableDeferred<Unit>()
            var page: DrivesViewModel? = null
            try {
                fixture.initialize()
                val localCarId = fixture.localId(1)
                val original = base().copy(startAddress = "Original session origin", endAddress = "Original session destination")
                fixture.db.driveSummaryDao().upsertAll(listOf(requireNotNull(original.toSyncSummary(localCarId))))
                val foreign = collision.copy(startAddress = "Other session private origin", endAddress = "Other session private destination")
                fixture.detailResponse = { path -> if (path.endsWith("/drives")) driveResponse(foreign) else null }
                fixture.onRequest = { path -> if (path.endsWith("/drives")) { drivesEntered.complete(Unit); drivesGate.await() } }
                val vm = createPage(fixture)
                page = vm
                vm.setCarId(1)
                withTimeout(10_000) { drivesEntered.await() }
                withTimeout(10_000) { vm.uiState.first { it.drives.singleOrNull()?.startAddress == original.startAddress } }
                drivesGate.complete(Unit)
                awaitInitialLoad(vm)
                val actual = vm.uiState.value.drives.single()
                assertEquals(original.source, actual.source)
                assertEquals(original.sourceInstanceId, actual.sourceInstanceId)
                assertEquals(original.startDate, actual.startDate)
                assertEquals(original.endDate, actual.endDate)
                assertEquals(original.startAddress, actual.startAddress)
                assertEquals(original.endAddress, actual.endAddress)
                assertEquals(2.0, requireNotNull(actual.distanceKm), 0.0001)
            } finally {
                drivesGate.complete(Unit)
                page?.viewModelScope?.coroutineContext?.get(Job)?.cancelAndJoin()
                fixture.close()
                Dispatchers.resetMain()
            }
        }
    }

    @Test fun successfulCloudUidRemappingToAnEmptyVehicleDropsThePreviousVehicleAndBaseline() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val fixture = HistoryResourceFixture(File(temp.root, "oct28-cloud-uid-remap.db"))
        val contextEntered = CompletableDeferred<Unit>()
        val contextGate = CompletableDeferred<Unit>()
        var page: DrivesViewModel? = null
        try {
            fixture.initialize(cloud = true)
            val originalLocalId = fixture.localId(1)
            fixture.db.driveSummaryDao().upsertAll(listOf(requireNotNull(base().toSyncSummary(originalLocalId))))
            fixture.detailResponse = { path ->
                if (path.endsWith("/history-context"))
                    """{"data":{"capability_version":1,"car_id":1,"vehicle_uid":"new-vehicle-B"}}""" else null
            }
            fixture.onRequest = { path -> if (path.endsWith("/history-context")) { contextEntered.complete(Unit); contextGate.await() } }
            val vm = createPage(fixture)
            page = vm
            vm.setCarId(1)
            withTimeout(10_000) { contextEntered.await() }
            withTimeout(10_000) { vm.uiState.first { it.drives.any { drive -> drive.driveId == BASE_ID } } }
            assertEquals(originalLocalId, fixture.localId(1))
            contextGate.complete(Unit)
            awaitInitialLoad(vm)
            val reboundLocalId = fixture.localId(1)
            assertTrue(originalLocalId != reboundLocalId)
            assertTrue(fixture.db.driveSummaryDao().getAllChronologicalForAdjacency(reboundLocalId).isEmpty())
            val rebound = vm.uiState.value
            assertTrue("vehicle B's empty history must not retain vehicle A's cards", rebound.drives.isEmpty())
            assertTrue(rebound.historySequence.isEmpty())
            assertTrue(rebound.driveMetrics.isEmpty())
            assertTrue(rebound.parkedPairs.isEmpty())
            assertEquals(0, rebound.summary.totalDrives)
            // Old rows remain intact, but their baseline cannot reject B's legitimate same numeric ID.
            assertEquals(listOf(BASE_ID), fixture.db.driveSummaryDao()
                .getAllChronologicalForAdjacency(originalLocalId).map { it.driveId })
            val vehicleBDrive = drive(BASE_ID, "2026-10-28", 21, 4.0).copy(sourceInstanceId = "vehicle-B-collector")
            fixture.db.driveSummaryDao().upsertAll(listOf(requireNotNull(vehicleBDrive.toSyncSummary(reboundLocalId))))
            val updated = withTimeoutOrNull(3_000) {
                vm.uiState.first { it.drives.singleOrNull()?.sourceInstanceId == vehicleBDrive.sourceInstanceId }
            } ?: vm.uiState.value
            assertEquals("B's legitimate same numeric drive ID must not inherit A's baseline", listOf(BASE_ID),
                updated.drives.map { it.driveId })
            assertEquals(vehicleBDrive.sourceInstanceId, updated.drives.single().sourceInstanceId)
            assertEquals(vehicleBDrive.startDate, updated.drives.single().startDate)
            assertEquals(4.0, requireNotNull(updated.summary.totalDistanceKm), 0.0001)
        } finally {
            contextGate.complete(Unit)
            page?.viewModelScope?.coroutineContext?.get(Job)?.cancelAndJoin()
            fixture.close()
            Dispatchers.resetMain()
        }
    }

    @Test fun uidRemappingAdoptsEmptyAndNonEmptyVehicleBeforeSlowChargesComplete() = runBlocking {
        val failures = mutableListOf<Throwable>()
        for (hasRemoteDrive in listOf(false, true)) {
            Dispatchers.setMain(UnconfinedTestDispatcher())
            val fixture = HistoryResourceFixture(File(temp.root, "oct28-remap-slow-charge-$hasRemoteDrive.db"))
            val contextEntered = CompletableDeferred<Unit>()
            val contextGate = CompletableDeferred<Unit>()
            val chargesEntered = CompletableDeferred<Unit>()
            val chargeGate = CompletableDeferred<Unit>()
            var page: DrivesViewModel? = null
            try {
                fixture.initialize(cloud = true)
                val originalLocalId = fixture.localId(1)
                fixture.db.driveSummaryDao().upsertAll(listOf(base(), control(1.0)).map {
                    requireNotNull(it.toSyncSummary(originalLocalId))
                })
                val vehicleBDrive = drive(BASE_ID, "2026-10-28", 21, 4.0)
                    .copy(sourceInstanceId = "slow-charge-vehicle-B")
                fixture.detailResponse = { path ->
                    when {
                        path.endsWith("/history-context") ->
                            """{"data":{"capability_version":1,"car_id":1,"vehicle_uid":"slow-charge-vehicle-B"}}"""
                        path.endsWith("/drives") && hasRemoteDrive -> driveResponse(vehicleBDrive)
                        else -> null
                    }
                }
                fixture.onRequest = { path ->
                    when {
                        path.endsWith("/history-context") -> { contextEntered.complete(Unit); contextGate.await() }
                        path.endsWith("/charges") -> { chargesEntered.complete(Unit); chargeGate.await() }
                    }
                }
                val vm = createPage(fixture)
                page = vm
                vm.setCarId(1)
                withTimeout(10_000) { contextEntered.await() }
                withTimeout(10_000) { vm.uiState.first { it.drives.any { drive -> drive.driveId == BASE_ID } } }
                // Prove A's own observer has consumed its last change before B is discovered.
                fixture.db.driveSummaryDao().upsertAll(listOf(requireNotNull(control(3.0).toSyncSummary(originalLocalId))))
                val vehicleA = awaitControl(vm, 3.0)
                assertEquals(setOf(BASE_ID, CONTROL_ID), vehicleA.drives.map { it.driveId }.toSet())
                assertEquals(originalLocalId, fixture.localId(1))
                contextGate.complete(Unit)
                withTimeout(10_000) { chargesEntered.await() }
                val reboundLocalId = fixture.localId(1)
                assertTrue(originalLocalId != reboundLocalId)
                val expectedIds = if (hasRemoteDrive) listOf(BASE_ID) else emptyList()
                assertEquals(expectedIds, fixture.db.driveSummaryDao()
                    .getAllChronologicalForAdjacency(reboundLocalId).map { it.driveId })
                val rebound = withTimeoutOrNull(3_000) {
                    vm.uiState.first { state ->
                        state.drives.map { it.driveId } == expectedIds &&
                            (!hasRemoteDrive || state.drives.single().sourceInstanceId == vehicleBDrive.sourceInstanceId)
                    }
                } ?: vm.uiState.value
                assertFalse(chargeGate.isCompleted)
                assertEquals("UID-B must replace A before charges finish; remote drive=$hasRemoteDrive", expectedIds,
                    rebound.drives.map { it.driveId })
                assertFalse(rebound.historySequence.any { it.sourceInstanceId == base().sourceInstanceId })
                if (hasRemoteDrive) {
                    assertEquals(vehicleBDrive.sourceInstanceId, rebound.drives.single().sourceInstanceId)
                    assertEquals(vehicleBDrive.startDate, rebound.drives.single().startDate)
                    assertEquals(1, rebound.summary.totalDrives)
                    assertEquals(4.0, requireNotNull(rebound.summary.totalDistanceKm), 0.0001)
                } else {
                    assertEquals(0, rebound.summary.totalDrives)
                    assertTrue(rebound.driveMetrics.isEmpty())
                    assertTrue(rebound.parkedPairs.isEmpty())
                    // Even an initially empty B must already have its own live subscription.
                    fixture.db.driveSummaryDao().upsertAll(listOf(requireNotNull(vehicleBDrive.toSyncSummary(reboundLocalId))))
                    val later = withTimeoutOrNull(3_000) {
                        vm.uiState.first { it.drives.singleOrNull()?.sourceInstanceId == vehicleBDrive.sourceInstanceId }
                    } ?: vm.uiState.value
                    assertEquals("an initially empty B must consume a later same-ID Room row while charges wait",
                        listOf(BASE_ID), later.drives.map { it.driveId })
                    assertEquals(vehicleBDrive.sourceInstanceId, later.drives.single().sourceInstanceId)
                    assertEquals(4.0, requireNotNull(later.summary.totalDistanceKm), 0.0001)
                }
                assertFalse(chargeGate.isCompleted)
                assertEquals(setOf(BASE_ID, CONTROL_ID), fixture.db.driveSummaryDao()
                    .getAllChronologicalForAdjacency(originalLocalId).map { it.driveId }.toSet())
            } catch (failure: AssertionError) {
                failures += failure
            } finally {
                contextGate.complete(Unit)
                chargeGate.complete(Unit)
                page?.viewModelScope?.coroutineContext?.get(Job)?.cancelAndJoin()
                fixture.close()
                Dispatchers.resetMain()
            }
        }
        MultipleFailureException.assertEmpty(failures)
    }

    @Test fun refreshProgressSurvivesLiveRoomUpdatesUntilSuccessOrTimeout() = runBlocking {
        val failures = mutableListOf<Throwable>()
        for (timesOut in listOf(false, true)) {
            val scheduler = TestCoroutineScheduler()
            Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
            val fixture = HistoryResourceFixture(File(temp.root, "oct28-refresh-progress-$timesOut.db"))
            val chargesEntered = CompletableDeferred<Unit>()
            val chargeGate = CompletableDeferred<Unit>()
            var page: DrivesViewModel? = null
            try {
                fixture.initialize()
                val localCarId = fixture.localId(1)
                fixture.db.driveSummaryDao().upsertAll(listOf(base(), control(1.0)).map {
                    requireNotNull(it.toSyncSummary(localCarId))
                })
                val vm = createPage(fixture)
                page = vm
                vm.setCarId(1)
                awaitInitialLoad(vm)
                fixture.db.driveSummaryDao().upsertAll(listOf(requireNotNull(control(3.0).toSyncSummary(localCarId))))
                awaitControl(vm, 3.0)
                fixture.onRequest = { path ->
                    if (path.endsWith("/charges")) { chargesEntered.complete(Unit); chargeGate.await() }
                }
                vm.refresh()
                withTimeout(10_000) { chargesEntered.await() }
                fixture.db.driveSummaryDao().upsertAll(listOf(
                    requireNotNull(control(4.0).toSyncSummary(localCarId)),
                    requireNotNull(drive(701, "2026-10-28", 21, 5.0).toSyncSummary(localCarId))
                ))
                val updated = awaitControl(vm, 4.0)
                assertEquals(listOf(701, CONTROL_ID, BASE_ID), updated.drives.map { it.driveId })
                assertEquals(3, updated.summary.totalDrives)
                assertEquals(11.0, requireNotNull(updated.summary.totalDistanceKm), 0.0001)
                assertFalse(chargeGate.isCompleted)
                assertTrue("Room publication must not finish the pending refresh; timeout=$timesOut", updated.isRefreshing)
                val finished = if (timesOut) {
                    scheduler.advanceTimeBy(20_001)
                    scheduler.runCurrent()
                    withTimeout(3_000) { vm.uiState.first { it.historySyncWarning == "history_sync_timeout" } }
                } else {
                    chargeGate.complete(Unit)
                    awaitInitialLoad(vm)
                    vm.uiState.value
                }
                assertFalse(finished.isLoading)
                assertFalse(finished.isRefreshing)
                assertFalse(finished.isFilterLoading)
                assertFalse(finished.isLoadingMore)
                assertEquals(listOf(701, CONTROL_ID, BASE_ID), finished.drives.map { it.driveId })
                if (timesOut) assertFalse(chargeGate.isCompleted)
                else assertNull(finished.historySyncWarning)
            } catch (failure: AssertionError) {
                failures += failure
            } finally {
                chargeGate.complete(Unit)
                page?.viewModelScope?.coroutineContext?.get(Job)?.cancelAndJoin()
                fixture.close()
                Dispatchers.resetMain()
            }
        }
        MultipleFailureException.assertEmpty(failures)
    }

    @Test
    @Config(shadows = [Oct28GatedAmapReverseShadow::class])
    fun lateSameSessionGeocodingCannotOverwriteNewerRoomAddressCorrections() = runBlocking {
        withGatedGeocoding("oct28-late-geocode-room-correction.db") { fixture, page, localCarId, remote, gate ->
            val corrected = remote.copy(startAddress = "Corrected origin B", endAddress = "Corrected destination B")
            val row = requireNotNull(corrected.toSyncSummary(localCarId))
            val receipt = requireNotNull(HistorySummaryEvidenceCodec.decodeDrive(requireNotNull(row.apiEvidence)))
            assertEquals(remote.source, receipt.source)
            assertEquals(remote.sourceInstanceId, receipt.sourceInstanceId)
            assertEquals(remote.startDate, receipt.startDate)
            assertEquals(remote.endDate, receipt.endDate)
            fixture.db.driveSummaryDao().upsertAll(listOf(row))
            val displayed = withTimeout(10_000) {
                page.uiState.first { state -> state.drives.singleOrNull()?.let {
                    it.startAddress == corrected.startAddress && it.endAddress == corrected.endAddress
                } == true }
            }
            assertFalse(gate.isCompleted)
            assertEquals(corrected.startAddress, displayed.drives.single().startAddress)
            assertEquals(corrected.endAddress, displayed.drives.single().endAddress)
            gate.complete(Unit)
            awaitInitialLoad(page)
            val afterOldLookup = page.uiState.value.drives.single()
            assertEquals("an older same-session start lookup must not overwrite the Room correction",
                corrected.startAddress, afterOldLookup.startAddress)
            assertEquals("an older same-session end lookup must not overwrite the Room correction",
                corrected.endAddress, afterOldLookup.endAddress)
            val persisted = requireNotNull(fixture.db.driveSummaryDao().get(localCarId, remote.driveId))
            assertEquals(corrected.startAddress, persisted.startAddress)
            assertEquals(corrected.endAddress, persisted.endAddress)
        }
    }

    @Test
    @Config(shadows = [Oct28GatedAmapReverseShadow::class])
    fun missingAddressesStillAcceptSameSessionGeocodingWhenRoomHasNoCorrection() = runBlocking {
        withGatedGeocoding("oct28-geocode-missing-address-positive.db") { _, page, _, remote, gate ->
            val pending = page.uiState.value.drives.single()
            assertEquals(remote.driveId, pending.driveId)
            assertNull(pending.startAddress)
            assertNull(pending.endAddress)
            gate.complete(Unit)
            awaitInitialLoad(page)
            val filled = page.uiState.value.drives.single()
            assertEquals(remote.source, filled.source)
            assertEquals(remote.sourceInstanceId, filled.sourceInstanceId)
            assertEquals(remote.startDate, filled.startDate)
            assertEquals(remote.endDate, filled.endDate)
            assertEquals("Older geocoded origin A", filled.startAddress)
            assertEquals("Older geocoded destination A", filled.endAddress)
        }
    }

    private suspend fun withGatedGeocoding(
        databaseName: String,
        body: suspend (HistoryResourceFixture, DrivesViewModel, Int, DriveData, CompletableDeferred<Unit>) -> Unit
    ) {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val fixture = HistoryResourceFixture(File(temp.root, databaseName))
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        var page: DrivesViewModel? = null
        try {
            fixture.initialize()
            val localCarId = fixture.localId(1)
            val remote = drive(BASE_ID, "2026-10-28", 8, 2.0).copy(
                startAddress = null, endAddress = null,
                startLatitude = 31.2, startLongitude = 121.4, endLatitude = 31.3, endLongitude = 121.5)
            fixture.db.driveSummaryDao().upsertAll(listOf(requireNotNull(remote.toSyncSummary(localCarId))))
            fixture.detailResponse = { path -> if (path.endsWith("/drives")) driveResponse(remote) else null }
            Oct28GatedAmapReverseShadow.lookup = { latitude, longitude ->
                val isStart = latitude == remote.startLatitude && longitude == remote.startLongitude
                if (isStart) { entered.complete(Unit); gate.await() }
                ChineseLocation(if (isStart) "Older geocoded origin A" else "Older geocoded destination A",
                    "CN", "China", "Synthetic region", "Synthetic city")
            }
            val vm = createPage(fixture)
            page = vm
            vm.setCarId(1)
            withTimeout(10_000) { entered.await() }
            val visible = withTimeout(10_000) {
                vm.uiState.first { it.drives.singleOrNull()?.driveId == remote.driveId }
            }
            assertEquals(remote.source, visible.drives.single().source)
            assertEquals(remote.sourceInstanceId, visible.drives.single().sourceInstanceId)
            assertEquals(remote.startDate, visible.drives.single().startDate)
            assertEquals(remote.endDate, visible.drives.single().endDate)
            assertFalse(gate.isCompleted)
            body(fixture, vm, localCarId, remote, gate)
        } finally {
            gate.complete(Unit)
            page?.viewModelScope?.coroutineContext?.get(Job)?.cancelAndJoin()
            Oct28GatedAmapReverseShadow.lookup = { _, _ -> null }
            fixture.close()
            Dispatchers.resetMain()
        }
    }

    private fun createPage(fixture: HistoryResourceFixture) = DrivesViewModel(fixture.context, fixture.apiRepo,
        UnifiedHistoryRepository(fixture.apiRepo, fixture.vehicleContexts,
            fixture.db.driveSummaryDao(), fixture.db.chargeSummaryDao()),
        fixture.geocodingRepository, fixture.settings, fixture.db.driveSummaryDao(), fixture.vehicleContexts,
        Clock.fixed(Instant.parse("2026-10-28T13:30:00Z"), ZoneId.of("Asia/Shanghai")), SavedStateHandle())

    private suspend fun awaitInitialLoad(page: DrivesViewModel) {
        val loader = DrivesViewModel::class.java.getDeclaredField("latestLoad")
            .apply { isAccessible = true }.get(page)
        val loadJob = loader.javaClass.getDeclaredField("job").apply { isAccessible = true }
        withTimeout(10_000) {
            while (loadJob.get(loader) == null) delay(10)
            (loadJob.get(loader) as Job).join()
        }
    }

    private fun driveResponse(drive: DriveData): String {
        val raw = Moshi.Builder().build().adapter(DriveData::class.java).toJson(drive)
        return """{"data":{"units":{"unit_of_length":"km"},"drives":[$raw]}}"""
    }

    private suspend fun initializeWithoutVehicleMappings(fixture: HistoryResourceFixture) {
        val url = "https://fresh.example"
        fixture.modes.set(ConnectionMode.SELF_HOSTED)
        fixture.settings.saveServerUrl(url)
        fixture.apiFactory.create(url)
        val moshi = Moshi.Builder().build()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val path = chain.request().url.encodedPath
            fixture.calls.add(path)
            runBlocking { fixture.onRequest(path) }
            val body = fixture.detailResponse?.invoke(path) ?: when {
                path.endsWith("/drives") -> """{"data":{"drives":[]}}"""
                path.endsWith("/charges") -> """{"data":{"charges":[]}}"""
                else -> """{"data":{"cars":[{"car_id":1,"vehicle_uid":"fresh-vehicle-A"}]}}"""
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(fixture.status(path)).message("Synthetic")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val fake = Retrofit.Builder().baseUrl("$url/").client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi)).build().create(TeslamateApi::class.java)
        @Suppress("UNCHECKED_CAST")
        val cache = TeslamateApiFactory::class.java.getDeclaredField("apiCache")
            .apply { isAccessible = true }.get(fixture.apiFactory) as MutableMap<Any, TeslamateApi>
        assertTrue(cache.isNotEmpty())
        cache.keys.toList().forEach { cache[it] = fake }
        fixture.reopen()
    }

    private suspend fun assertReplacementRejected(
        fixture: HistoryResourceFixture,
        page: DrivesViewModel,
        localCarId: Int,
        replacement: DriveData
    ) {
        fixture.db.driveSummaryDao().upsertAll(listOf(
            requireNotNull(replacement.toSyncSummary(localCarId)),
            requireNotNull(control(4.0).toSyncSummary(localCarId))
        ))
        // A compatible control row in the same transaction proves this emission was consumed.
        val updated = awaitControl(page, 4.0)
        val actual = updated.drives.single { it.driveId == BASE_ID }
        assertEquals(base().source, actual.source)
        assertEquals(base().sourceInstanceId, actual.sourceInstanceId)
        assertEquals(base().startDate, actual.startDate)
        assertEquals(base().endDate, actual.endDate)
        assertEquals(2.0, requireNotNull(actual.distanceKm), 0.0001)
        assertEquals(2, updated.summary.totalDrives)
        assertEquals(6.0, requireNotNull(updated.summary.totalDistanceKm), 0.0001)
        updated.driveMetrics[BASE_ID]?.let { metrics ->
            assertEquals(base().source, metrics.historySource)
            assertEquals(base().startDate, metrics.startDate)
            assertEquals(base().endDate, metrics.endDate)
        }
    }

    private suspend fun withObservedPage(
        databaseName: String,
        body: suspend (HistoryResourceFixture, DrivesViewModel, Int) -> Unit
    ) {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val fixture = HistoryResourceFixture(File(temp.root, databaseName))
        var page: DrivesViewModel? = null
        try {
            fixture.initialize()
            val localCarId = fixture.localId(1)
            fixture.db.driveSummaryDao().upsertAll(listOf(
                requireNotNull(base().toSyncSummary(localCarId)),
                requireNotNull(control(1.0).toSyncSummary(localCarId))
            ))
            fixture.reopen()
            val vm = DrivesViewModel(fixture.context, fixture.apiRepo,
                UnifiedHistoryRepository(fixture.apiRepo, fixture.vehicleContexts,
                    fixture.db.driveSummaryDao(), fixture.db.chargeSummaryDao()),
                fixture.geocodingRepository, fixture.settings, fixture.db.driveSummaryDao(),
                fixture.vehicleContexts,
                Clock.fixed(Instant.parse("2026-10-28T13:30:00Z"), ZoneId.of("Asia/Shanghai")),
                SavedStateHandle())
            page = vm
            vm.setCarId(1)
            // Cache publication clears loading flags before the remote stage finishes.
            // Finish this initial network load before testing later Room-only mutations.
            val loader = DrivesViewModel::class.java.getDeclaredField("latestLoad")
                .apply { isAccessible = true }.get(vm)
            val loadJob = loader.javaClass.getDeclaredField("job").apply { isAccessible = true }
            withTimeout(10_000) {
                while (loadJob.get(loader) == null) delay(10)
                (loadJob.get(loader) as Job).join()
            }
            // Prove the actual live observer with a compatible Room update.
            val observer = DrivesViewModel::class.java.getDeclaredField("metricsObservation")
                .apply { isAccessible = true }
            withTimeout(10_000) {
                while ((observer.get(vm) as? Job)?.isActive != true) delay(10)
            }
            fixture.db.driveSummaryDao().upsertAll(listOf(requireNotNull(control(3.0).toSyncSummary(localCarId))))
            val ready = awaitControl(vm, 3.0)
            assertEquals(DriveDateFilter.LAST_7_DAYS, ready.dateFilter)
            assertEquals(DriveDistanceFilter.ALL, ready.distanceFilter)
            assertEquals("2026-10-22", ready.startDate.toString())
            assertEquals("2026-10-28", ready.endDate.toString())
            assertEquals(2, ready.summary.totalDrives)
            assertEquals(5.0, requireNotNull(ready.summary.totalDistanceKm), 0.0001)
            assertNotNull(ready.driveMetrics[BASE_ID])
            assertTrue(fixture.calls.any { it.endsWith("/drives") })
            assertTrue(fixture.calls.any { it.endsWith("/charges") })
            body(fixture, vm, localCarId)
        } finally {
            page?.viewModelScope?.coroutineContext?.get(Job)?.cancelAndJoin()
            fixture.close()
            Dispatchers.resetMain()
        }
    }

    private suspend fun awaitControl(page: DrivesViewModel, distanceKm: Double): DrivesUiState =
        withTimeout(10_000) {
            page.uiState.first { state -> state.drives.any { it.driveId == CONTROL_ID && it.distanceKm == distanceKm } }
        }

    private fun base() = drive(BASE_ID, "2026-10-27", 12, 2.0).copy(
        durationMin = 12, endDate = "2026-10-27T12:12:00+08:00")

    private fun control(distanceKm: Double) =
        drive(CONTROL_ID, "2026-10-27", 14, distanceKm).copy(
            durationMin = 11, endDate = "2026-10-27T14:11:00+08:00")

    private fun drive(id: Int, day: String, hour: Int, distanceKm: Double): DriveData {
        val time = hour.toString().padStart(2, '0')
        return DriveData(
            driveId = id, startDate = "${day}T${time}:00:00+08:00", endDate = "${day}T${time}:10:00+08:00",
            durationMin = 10, odometerDetails = DriveOdometerDetails(distance = distanceKm), lengthUnit = "km",
            source = "teslamate_archive", sourceInstanceId = "synthetic-archive-A", qualityState = "observed",
            startAddress = "Synthetic origin", endAddress = "Synthetic destination"
        )
    }

    private companion object {
        const val BASE_ID = 10
        const val CONTROL_ID = 11
    }
}

/** Only the provider boundary is controlled; the VM, Room and geocoding repository stay real. */
@Implements(value = AmapReverseGeocoder::class, isInAndroidSdk = false)
class Oct28GatedAmapReverseShadow {
    @Implementation
    @Suppress("UNUSED_PARAMETER")
    fun currentAvailability(continuation: Continuation<ChineseLocationAvailability>): Any = ChineseLocationAvailability.READY

    @Implementation
    fun reverse(latitude: Double, longitude: Double, continuation: Continuation<ChineseLocation?>): Any? {
        val pending: suspend () -> ChineseLocation? = { lookup(latitude, longitude) }
        return pending.startCoroutineUninterceptedOrReturn(continuation)
    }

    companion object {
        @Volatile var lookup: suspend (Double, Double) -> ChineseLocation? = { _, _ -> null }
    }
}
