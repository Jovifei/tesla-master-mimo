package com.matelink.data.sync

import android.app.Application
import androidx.work.Configuration
import androidx.work.Data
import androidx.work.ForegroundUpdater
import androidx.work.ListenableWorker
import androidx.work.ProgressUpdater
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.impl.WorkManagerImpl
import androidx.work.impl.utils.futures.SettableFuture
import com.matelink.data.local.entity.SchemaVersion
import com.matelink.data.local.entity.SyncState
import com.matelink.data.repository.SettingsRepository
import com.matelink.domain.model.SyncPhase
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real repository/worker with isolated disk Room and intercepted HTTP; no provider or owner data. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class ExternalReviewSyncLifecycleTest {
    @get:Rule val temp = TemporaryFolder()
    private val managers = mutableListOf<WorkManagerImpl>()

    @After fun releaseManagers() {
        managers.forEach { it.cancelAllWork().result.get() }
        WorkManagerImpl.setDelegate(null)
        managers.forEach { it.workDatabase.close() }
    }

    private suspend fun initialize(name: String): HistoryResourceFixture =
        HistoryResourceFixture(File(temp.root, name)).also { f ->
            SettingsRepository(f.context).setMockMode(false)
            f.initialize()
            f.db.syncStateDao().upsert(SyncState(f.localId(1)))
        }

    private fun worker(f: HistoryResourceFixture): DataSyncWorker {
        val manager = Class.forName("androidx.work.impl.WorkManagerImplExtKt")
            .getMethod("createWorkManager", android.content.Context::class.java, Configuration::class.java)
            .invoke(null, f.context, Configuration.Builder().build()) as WorkManagerImpl
        manager.cancelAllWork().result.get()
        WorkManagerImpl.setDelegate(manager)
        managers += manager
        val completed = { SettableFuture.create<Void>().apply { set(null) } }
        val params = WorkerParameters(UUID.randomUUID(), Data.EMPTY, listOf(DataSyncWorker.TAG),
            WorkerParameters.RuntimeExtras(), 0, 0, Executor { it.run() }, manager.workTaskExecutor,
            WorkerFactory.getDefaultWorkerFactory(), ProgressUpdater { _, _, _ -> completed() },
            ForegroundUpdater { _, _, _ -> completed() })
        return DataSyncWorker(f.context, params, f.apiRepo, f.repo, f.syncManager, f.log)
    }

    private suspend fun assertFirstDetailAndRemainingRowsSurvive(f: HistoryResourceFixture) {
        val local = f.localId(1)
        val state = requireNotNull(f.db.syncStateDao().get(local))
        assertEquals("The already committed detail cursor must survive", 1, state.lastDriveDetailId)
        assertEquals(1, state.drivesProcessed)
        assertFalse("An interrupted run must not certify every detail", state.detailsSynced)
        assertNotNull(f.db.aggregateDao().getDriveAggregate(local, 1))
        assertEquals(listOf(1, 2, 3), f.db.driveSummaryDao().getAllChronologicalForAdjacency(local).map { it.driveId }.sorted())
        assertEquals(2, f.db.driveSummaryDao().countUnprocessedDrives(local, SchemaVersion.DRIVE_ENERGY))
    }

    @Test fun summary503EndsTheInMemoryPhaseWithoutResettingDurableProgress() = runBlocking {
        val f = initialize("summary-503.db")
        try {
            f.seed(1, 2, 1)
            val local = f.localId(1)
            val before = SyncState(local, lastDriveDetailId = 41, lastChargeDetailId = 17,
                drivesProcessed = 3, chargesProcessed = 2, totalDrivesToProcess = 5,
                totalChargesToProcess = 4)
            f.db.syncStateDao().upsert(before)
            val rows = f.db.driveSummaryDao().getAllChronologicalForAdjacency(local)
            f.status = { path -> if (path.endsWith("/drives")) 503 else 200 }

            assertTrue(f.repo.syncCar(1, summariesOnly = true) is CarSyncResult.Failed)

            assertEquals(before, f.db.syncStateDao().get(local))
            assertEquals(rows, f.db.driveSummaryDao().getAllChronologicalForAdjacency(local))
            assertEquals(1, f.db.chargeSummaryDao().getAllForCar(local).size)
            assertFalse("A returned summary failure must not block another Stats refresh as SYNCING",
                f.syncManager.syncStatus.value.isAnySyncing)
            assertEquals(SyncPhase.ERROR, f.syncManager.getProgressForCar(local)?.phase)
        } finally { f.close() }
    }

    @Test fun workerDetailFailureEndsThePhaseAndKeepsCommittedCursorAndRoomRows() = runBlocking {
        val f = initialize("detail-failure.db")
        try {
            f.seed(1, 3, 0)
            f.status = { path -> if (path.endsWith("/drives/2")) 503 else 200 }

            assertTrue(worker(f).doWork() is ListenableWorker.Result.Retry)

            assertFirstDetailAndRemainingRowsSurvive(f)
            assertFalse("A failed detail worker must not leave stale SYNCING progress",
                f.syncManager.syncStatus.value.isAnySyncing)
            assertEquals(SyncPhase.ERROR, f.syncManager.getProgressForCar(f.localId(1))?.phase)
        } finally { f.close() }
    }

    @Test fun cancelledWorkerReleasesItsPhaseWithoutResettingCommittedWork() = runBlocking {
        val f = initialize("detail-cancel.db")
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        try {
            f.seed(1, 3, 0)
            f.onRequest = { path -> if (path.endsWith("/drives/2")) {
                entered.complete(Unit)
                release.await()
            } }
            val actual = worker(f)
            val running = async(Dispatchers.Default) { actual.doWork() }
            try {
                withTimeout(10_000) { entered.await() }
                assertEquals(1, f.db.syncStateDao().get(f.localId(1))?.lastDriveDetailId)
                running.cancel(CancellationException("synthetic_cancel"))
                release.complete(Unit)
                withTimeout(10_000) { running.join() }
                assertTrue("Cancellation must propagate rather than report success", running.isCancelled)
                assertFirstDetailAndRemainingRowsSurvive(f)
                assertFalse("A cancelled worker must release only its active in-memory phase",
                    f.syncManager.syncStatus.value.isAnySyncing)
                assertEquals(SyncPhase.IDLE, f.syncManager.getProgressForCar(f.localId(1))?.phase)
            } finally { release.complete(Unit); running.cancelAndJoin() }
        } finally { release.complete(Unit); f.close() }
    }

    @Test fun olderFailedRunCannotFinishTheNewerRunForTheSameVerifiedLocalCar() = runBlocking {
        val f = initialize("overlapping-runs.db")
        val oldEntered = CompletableDeferred<Unit>()
        val oldRelease = CompletableDeferred<Unit>()
        val newEntered = CompletableDeferred<Unit>()
        val newRelease = CompletableDeferred<Unit>()
        val reads = AtomicInteger()
        val requestOrdinal = ConcurrentHashMap<Long, Int>()
        try {
            f.onRequest = { path -> if (path.endsWith("/drives")) {
                val ordinal = reads.incrementAndGet()
                requestOrdinal[Thread.currentThread().id] = ordinal
                when (ordinal) {
                    1 -> { oldEntered.complete(Unit); oldRelease.await() }
                    2 -> { newEntered.complete(Unit); newRelease.await() }
                    else -> error("Unexpected extra summary request")
                }
            } }
            f.status = { path ->
                if (path.endsWith("/drives") && requestOrdinal[Thread.currentThread().id] == 1) 503 else 200
            }
            val older = async(Dispatchers.Default) { f.repo.syncCar(1, summariesOnly = true) }
            try {
                withTimeout(10_000) { oldEntered.await() }
                val newer = async(Dispatchers.Default) { f.repo.syncCar(1, summariesOnly = true) }
                try {
                    withTimeout(10_000) { newEntered.await() }
                    oldRelease.complete(Unit)
                    assertTrue(withTimeout(10_000) { older.await() } is CarSyncResult.Failed)
                    assertEquals("An obsolete run's cleanup must not clear the newer run's phase",
                        SyncPhase.SYNCING_SUMMARIES, f.syncManager.getProgressForCar(f.localId(1))?.phase)
                    assertTrue(f.syncManager.syncStatus.value.isAnySyncing)

                    newRelease.complete(Unit)
                    withTimeout(10_000) { newer.await() }
                    assertFalse(f.syncManager.syncStatus.value.isAnySyncing)
                } finally { newRelease.complete(Unit); newer.cancelAndJoin() }
            } finally { oldRelease.complete(Unit); older.cancelAndJoin() }
        } finally { oldRelease.complete(Unit); newRelease.complete(Unit); f.close() }
    }
}
