package com.matelink.data.repository

import android.app.Application
import com.matelink.data.api.models.CarData
import com.matelink.data.api.models.ChargeData
import com.matelink.data.api.models.DriveData
import com.matelink.data.api.models.DriveOdometerDetails
import com.matelink.data.api.models.HistoryContextData
import com.matelink.data.local.HistoryReadScope
import com.matelink.data.local.VehicleContext
import com.matelink.data.local.entity.SyncState
import com.matelink.data.sync.HistoryResourceFixture
import com.matelink.data.sync.toSyncSummary
import com.matelink.domain.analytics.HistorySummaryEvidenceCodec
import com.matelink.domain.analytics.toAnalysisDriveData
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Actual history orchestration and disk Room; all provider responses are synthetic local ports. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE)
class Oct28IndependentHistoryLoadTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun successfulOct28DriveReceiptsSurviveAnUnrelatedChargeTimeout() = runBlocking {
        val fixture = Oct28IndependentHistoryFixture(File(temp.root, "independent-timeout.db"))
        try {
            fixture.initialize()
            val result = fixture.loadUntilChargeTimeout()

            fixture.assertUnavailableChargeWasNotPromoted(result)
            fixture.assertOct28DrivesPersistedInTheVerifiedCar()
            // Reopening the file must preserve the real receipts, not just this read's list.
            fixture.resources.reopen()
            assertEquals(fixture.originalScope, fixture.resources.vehicleContexts.captureReadScope())
            fixture.assertOct28DrivesPersistedInTheVerifiedCar()
        } finally { fixture.close() }
    }

    @Test fun identityChangeAsDriveResponseCompletesRejectsItBeforeAnyRoomWrite() = runBlocking {
        val fixture = Oct28IndependentHistoryFixture(File(temp.root, "independent-identity.db"))
        try {
            fixture.initialize()
            fixture.afterDriveResponse = {
                fixture.resources.settings.saveServerUrl("https://synthetic-other-origin.example")
            }

            val result = fixture.repository().load(Oct28IndependentHistoryFixture.REMOTE_CAR)

            assertTrue(result is ApiResult.Error)
            assertEquals(HISTORY_IDENTITY_UNAVAILABLE, (result as ApiResult.Error).message)
            assertEquals(1, fixture.driveCalls)
            assertEquals(0, fixture.chargeCalls)
            assertEquals(listOf(fixture.oldDrive.driveId), fixture.resources.db.driveSummaryDao()
                .getAllChronological(fixture.originalContext.localHistoryCarId).map { it.driveId })
            assertEquals(fixture.otherCarBefore, fixture.resources.db.driveSummaryDao()
                .getAllChronological(fixture.otherLocalCar))
            assertNull(fixture.resources.vehicleContexts.cachedContextForRemote(
                Oct28IndependentHistoryFixture.REMOTE_CAR))
        } finally { fixture.close() }
    }

    @Test fun explicitCancellationOfWaitingChargePropagatesWithoutInventingChargeSuccess() = runBlocking {
        val fixture = Oct28IndependentHistoryFixture(File(temp.root, "independent-cancel.db"))
        try {
            fixture.initialize()
            val load = async { fixture.repository().load(Oct28IndependentHistoryFixture.REMOTE_CAR) }
            withTimeout(10_000) { fixture.chargeEntered.await() }
            load.cancel(CancellationException("synthetic_explicit_cancel"))
            try {
                load.await()
                fail("Explicit caller cancellation must propagate, never become cached/empty success")
            } catch (_: CancellationException) { }

            assertTrue(load.isCancelled)
            assertTrue(fixture.chargeCancelled.isCompleted)
            fixture.assertUnavailableChargeWasNotPromoted(null)
        } finally { fixture.close() }
    }

    @Test fun normalChargeResponseAllowsBothHistoriesAndPreservesVehicleIsolation() = runBlocking {
        val fixture = Oct28IndependentHistoryFixture(File(temp.root, "independent-control.db"))
        try {
            fixture.initialize()
            fixture.holdCharges = false

            val result = fixture.repository().load(Oct28IndependentHistoryFixture.REMOTE_CAR)

            assertTrue(result is ApiResult.Success)
            val history = (result as ApiResult.Success).data
            assertTrue(history.drivesFromRemote)
            assertTrue(history.chargesFromRemote)
            assertNull(history.drivesSyncError)
            assertNull(history.chargesSyncError)
            assertEquals(fixture.newDrives.map { it.driveId }.toSet() + fixture.oldDrive.driveId,
                history.drives.map { it.driveId }.toSet())
            assertEquals(setOf(fixture.oldCharge.chargeId, fixture.newCharge.chargeId),
                history.charges.map { it.chargeId }.toSet())
            fixture.assertOct28DrivesPersistedInTheVerifiedCar()
            assertEquals(setOf(fixture.oldCharge.chargeId, fixture.newCharge.chargeId),
                fixture.resources.db.chargeSummaryDao().getAllForCar(fixture.originalContext.localHistoryCarId)
                    .map { it.chargeId }.toSet())
        } finally { fixture.close() }
    }
}

/** Shared only by the two Oct 28 regression files; production has no added test seam. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class Oct28IndependentHistoryFixture(database: File) {
    companion object { const val REMOTE_CAR = 1 }

    val resources = HistoryResourceFixture(database)
    lateinit var originalScope: HistoryReadScope
    lateinit var originalContext: VehicleContext
    var otherLocalCar: Int = 0
        private set
    lateinit var otherCarBefore: List<com.matelink.data.local.entity.DriveSummary>
        private set
    private lateinit var chargeBefore: List<com.matelink.data.local.entity.ChargeSummary>
    private lateinit var syncBefore: SyncState
    val chargeEntered = CompletableDeferred<Unit>()
    val chargeCancelled = CompletableDeferred<Unit>()
    var holdCharges = true
    var identityError: ApiResult.Error? = null
    var afterDriveResponse: suspend () -> Unit = {}
    var driveCalls = 0
        private set
    var chargeCalls = 0
        private set

    private fun drive(id: Int, start: String, end: String) = DriveData(id,
        startDate = start, endDate = end, durationMin = 12,
        source = "teslamate_archive", sourceInstanceId = "synthetic-oct28-instance",
        sourceRecordId = "synthetic-drive-$id", qualityState = "observed", lengthUnit = "km",
        odometerDetails = DriveOdometerDetails(distance = 6.0),
        startAddress = "synthetic start", endAddress = "synthetic end")

    val oldDrive = drive(27, "2026-10-27T01:00:00Z", "2026-10-27T01:12:00Z")
    // Separate completed sessions at about 08, 19 and 21 in Asia/Shanghai.
    val newDrives = listOf(
        drive(2801, "2026-10-28T00:10:00Z", "2026-10-28T00:22:00Z"),
        drive(2802, "2026-10-28T11:10:00Z", "2026-10-28T11:22:00Z"),
        drive(2803, "2026-10-28T13:10:00Z", "2026-10-28T13:22:00Z")
    )
    val oldCharge = ChargeData(41, startDate = "2026-10-25T00:00:00Z",
        endDate = "2026-10-25T01:00:00Z", source = "teslamate_archive",
        sourceInstanceId = "synthetic-oct28-instance", sourceRecordId = "synthetic-charge-41",
        qualityState = "observed")
    val newCharge = oldCharge.copy(chargeId = 42, startDate = "2026-10-28T02:00:00Z",
        endDate = "2026-10-28T03:00:00Z", sourceRecordId = "synthetic-charge-42")

    suspend fun initialize() {
        // The existing fixture intercepts this origin's Retrofit client. The VM's optional units
        // read therefore also stays inside the local interceptor instead of a cloud API client.
        resources.initialize()
        SettingsRepository(resources.context).setMockMode(false)
        originalScope = resources.vehicleContexts.captureReadScope()
        originalContext = requireNotNull(resources.vehicleContexts.cachedContextForRemote(REMOTE_CAR))
        otherLocalCar = resources.localId(2)
        resources.db.driveSummaryDao().upsert(oldDrive.toSyncSummary(originalContext.localHistoryCarId)!!)
        // A colliding numeric drive ID belonging to another verified vehicle must stay untouched.
        resources.db.driveSummaryDao().upsert(newDrives.first().copy(
            startDate = "2026-10-20T00:00:00Z", endDate = "2026-10-20T00:12:00Z")
            .toSyncSummary(otherLocalCar)!!)
        resources.db.chargeSummaryDao().upsert(oldCharge.toSyncSummary(originalContext.localHistoryCarId)!!)
        syncBefore = SyncState(originalContext.localHistoryCarId, lastDriveSyncAt = 27,
            lastChargeSyncAt = 25, summariesSynced = false, detailsSynced = false)
        resources.db.syncStateDao().upsert(syncBefore)
        otherCarBefore = resources.db.driveSummaryDao().getAllChronological(otherLocalCar)
        chargeBefore = resources.db.chargeSummaryDao().getAllForCar(originalContext.localHistoryCarId)
    }

    fun repository() = UnifiedHistoryRepository(HistoryReadDependencies(
        captureScope = { resources.vehicleContexts.captureReadScope() },
        getCars = { identityError ?: ApiResult.Success(listOf(CarData(REMOTE_CAR, vehicleUid = "synthetic-vehicle-1"))) },
        getHistoryContext = { id ->
            check(id == REMOTE_CAR)
            identityError ?: ApiResult.Success(HistoryContextData(1, id, "synthetic-vehicle-1"))
        },
        resolveCar = { car, scope -> resources.vehicleContexts.resolveVerifiedHistoryCar(car, scope) },
        cachedContext = { id, scope -> resources.vehicleContexts.cachedVerifiedHistoryContext(id, scope) },
        localDrives = { resources.db.driveSummaryDao().getAllChronological(it) },
        localCharges = { resources.db.chargeSummaryDao().getAllForCar(it) },
        getDrives = { id, _, _, page ->
            check(id == REMOTE_CAR && page == 1)
            driveCalls++
            afterDriveResponse()
            ApiResult.Success(newDrives)
        },
        getCharges = { id, _, _, page ->
            check(id == REMOTE_CAR && page == 1)
            chargeCalls++
            chargeEntered.complete(Unit)
            if (holdCharges) {
                try { awaitCancellation() }
                finally { chargeCancelled.complete(Unit) }
            } else ApiResult.Success(listOf(newCharge))
        },
        persistDrives = { resources.db.driveSummaryDao().upsertPreservingEvidence(it) },
        persistCharges = { resources.db.chargeSummaryDao().upsertPreservingEvidence(it) }
    ))

    /** The real caller budget expires in virtual time only after the charge port is entered. */
    suspend fun loadUntilChargeTimeout(): ApiResult<UnifiedHistory>? = coroutineScope {
        val dispatcher = UnconfinedTestDispatcher()
        val load = async(dispatcher) {
            withTimeoutOrNull(20_000) { repository().load(REMOTE_CAR) }
        }
        withTimeout(10_000) { chargeEntered.await() }
        dispatcher.scheduler.advanceTimeBy(20_001)
        dispatcher.scheduler.runCurrent()
        load.await()
    }

    suspend fun assertOct28DrivesPersistedInTheVerifiedCar() {
        val localCar = originalContext.localHistoryCarId
        val saved = resources.db.driveSummaryDao().getAllChronological(localCar).associateBy { it.driveId }
        assertEquals("A slow charge endpoint must not discard three already successful drive receipts",
            newDrives.map { it.driveId }.toSet() + oldDrive.driveId, saved.keys)
        for (wire in newDrives) {
            val row = requireNotNull(saved[wire.driveId])
            assertEquals(localCar, row.carId)
            assertEquals(HistorySummaryEvidenceCodec.encode(wire),
                HistorySummaryEvidenceCodec.sourceEvidence(row.apiEvidence))
            val decoded = requireNotNull(HistorySummaryEvidenceCodec.decodeDrive(requireNotNull(row.apiEvidence)))
            assertEquals(wire.lengthUnit, decoded.lengthUnit)
            assertEquals(wire.odometerDetails, decoded.odometerDetails)
            val analysis = row.toAnalysisDriveData()
            assertEquals(wire.lengthUnit, analysis.lengthUnit)
            assertEquals(wire.distanceKm!!, analysis.distanceKm!!, 1e-9)
        }
        assertEquals(otherCarBefore, resources.db.driveSummaryDao().getAllChronological(otherLocalCar))
    }

    suspend fun assertUnavailableChargeWasNotPromoted(result: ApiResult<UnifiedHistory>?) {
        assertEquals(1, chargeCalls)
        assertTrue("The suspended charge port must observe cancellation", chargeCancelled.isCompleted)
        assertEquals(chargeBefore, resources.db.chargeSummaryDao().getAllForCar(originalContext.localHistoryCarId))
        assertEquals(syncBefore, resources.db.syncStateDao().get(originalContext.localHistoryCarId))
        if (result != null) {
            assertTrue("Independent successful drives may return partial success", result is ApiResult.Success)
            val history = (result as ApiResult.Success).data
            assertFalse("A cancelled charge read cannot become authoritative empty history", history.chargesFromRemote)
            assertNotNull(history.chargesSyncError)
            assertEquals(listOf(oldCharge.chargeId), history.charges.map { it.chargeId })
        }
    }

    fun close() = resources.close()
}
