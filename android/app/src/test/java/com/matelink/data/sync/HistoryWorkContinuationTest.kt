package com.matelink.data.sync

import android.app.Application
import androidx.work.*
import androidx.work.impl.WorkDatabase
import androidx.work.impl.WorkManagerImpl
import androidx.work.impl.utils.futures.SettableFuture
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID
import java.util.concurrent.Executor

/** Actual DataSyncWorker.doWork, initialized repository, fake HTTP, disk WorkManager and Room.
 * Database/manager reconstruction simulates process recreation; no physical device claim. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class HistoryWorkContinuationTest {
    @get:Rule val temp = TemporaryFolder()
    private val ownedManagers = mutableListOf<WorkManagerImpl>()
    private fun initialize(fixture: HistoryResourceFixture): WorkManagerImpl {
        // Android Startup may already initialize its default manager in the rendered test manifest.
        // Build the actual manager directly, then bind only this Robolectric sandbox's delegate.
        val manager = Class.forName("androidx.work.impl.WorkManagerImplExtKt")
            .getMethod("createWorkManager", android.content.Context::class.java, Configuration::class.java)
            .invoke(null, fixture.context, Configuration.Builder().build()) as WorkManagerImpl
        manager.cancelAllWork().result.get()
        WorkManagerImpl.setDelegate(manager)
        ownedManagers += manager
        return manager
    }
    @org.junit.After fun releaseDelegate() {
        ownedManagers.lastOrNull()?.cancelAllWork()?.result?.get()
        WorkManagerImpl.setDelegate(null)
        ownedManagers.forEach { it.workDatabase.close() }
    }

    private fun worker(fixture: HistoryResourceFixture, manager: WorkManagerImpl, input: Data): DataSyncWorker {
        val completed = { SettableFuture.create<Void>().apply { set(null) } }
        val params = WorkerParameters(UUID.randomUUID(), input, listOf(DataSyncWorker.TAG),
            WorkerParameters.RuntimeExtras(), 0, 0, Executor { it.run() }, manager.workTaskExecutor,
            WorkerFactory.getDefaultWorkerFactory(), ProgressUpdater { _, _, _ -> completed() },
            ForegroundUpdater { _, _, _ -> completed() })
        return DataSyncWorker(fixture.context, params, fixture.apiRepo, fixture.repo, fixture.syncManager, fixture.log)
    }
    private suspend fun input(fixture: HistoryResourceFixture, ids: List<Int> = listOf(1, 2)): Data = workDataOf(
        "history_sync_intent" to "DETAIL_CONTINUATION", "history_scope_epoch" to fixture.repo.captureContinuationFence(),
        "history_car_ids" to ids.toIntArray(), "history_car_proof" to fixture.repo.continuationCarProof(ids))

    @Test fun actualWorkerPersistsContinuationReopensAndExplicitlyRebuildsAfterCancelWithoutCloudWrites() = runBlocking(kotlinx.coroutines.Dispatchers.IO) {
        val fixture = HistoryResourceFixture(File(temp.root, "worker.db"))
        try {
            fixture.initialize(cloud = true); fixture.seed(1, 351, 500); fixture.seed(2, 1, 1)
            var manager = initialize(fixture)
            val actual = worker(fixture, manager, input(fixture))
            assertTrue(actual.doWork() is ListenableWorker.Result.Success)
            assertEquals(20, fixture.calls.size)
            val first = manager.getWorkInfosForUniqueWork(DataSyncWorker.WORK_NAME).get().single()
            val saved = requireNotNull(manager.workDatabase.workSpecDao().getWorkSpec(first.id.toString()))
            assertEquals("DETAIL_CONTINUATION", saved.input.getString("history_sync_intent"))
            assertNotNull(saved.input.getString("history_scope_epoch"))
            assertTrue(saved.input.keyValueMap.keys.none { it.contains("token") })
            // Reconstruct WorkManager over its actual existing disk DB and preserve exact queued task identity.
            val coldDatabase = WorkDatabase.create(fixture.context, manager.configuration.executor, manager.configuration.clock, false)
            val cold = Class.forName("androidx.work.impl.WorkManagerImplExtKt")
                .getMethod("createWorkManager", android.content.Context::class.java, Configuration::class.java,
                    androidx.work.impl.utils.taskexecutor.TaskExecutor::class.java, WorkDatabase::class.java)
                .invoke(null, fixture.context, manager.configuration, manager.workTaskExecutor, coldDatabase) as WorkManagerImpl
            ownedManagers += cold
            WorkManagerImpl.setDelegate(cold); manager = cold
            val restored = requireNotNull(manager.workDatabase.workSpecDao().getWorkSpec(first.id.toString()))
            assertEquals(saved.input, restored.input)
            assertEquals(WorkInfo.State.ENQUEUED, restored.state)
            fixture.reopen()
            fixture.calls.clear()
            assertTrue(worker(fixture, manager, restored.input).doWork() is ListenableWorker.Result.Success)
            assertEquals(20, fixture.calls.size)
            assertTrue(fixture.calls.all { it.matches(Regex("/api/v1/cars/[12]/(drives|charges)/[0-9]+")) })
            manager.cancelUniqueWork(DataSyncWorker.WORK_NAME).result.get()
            assertTrue(manager.getWorkInfosForUniqueWork(DataSyncWorker.WORK_NAME).get().all { it.state == WorkInfo.State.CANCELLED })
            val rebuilt = worker(fixture, manager, input(fixture))
            rebuilt.scheduleDetailContinuation(fixture.repo.captureContinuationFence(), listOf(1, 2), fixture.repo.continuationCarProof(listOf(1, 2)), 0)
            val after = manager.getWorkInfosForUniqueWork(DataSyncWorker.WORK_NAME).get()
            assertTrue(after.any { it.state == WorkInfo.State.ENQUEUED && it.id != first.id })
        } finally { fixture.close() }
    }

    @Test fun oldPersistedEpochCannotRunAfterAccountABAAndRestart() = runBlocking(kotlinx.coroutines.Dispatchers.IO) {
        val fixture = HistoryResourceFixture(File(temp.root, "aba-worker.db"))
        try {
            fixture.initialize(cloud = true); fixture.seed(1, 3, 3)
            val manager = initialize(fixture)
            val old = input(fixture)
            fixture.sessions.save("synthetic-B", "synthetic-refresh", 3600, "synthetic-other-account")
            fixture.sessions.save("synthetic-A2", "synthetic-refresh", 3600, "synthetic-account")
            fixture.reopen()
            assertTrue(worker(fixture, manager, old).doWork() is ListenableWorker.Result.Failure)
            assertTrue("stale continuation must perform no HTTP", fixture.calls.isEmpty())
        } finally { fixture.close() }
    }

    @Test fun actualWorkerPropagatesCancellationDuringDetailDeliveryWithoutContinuationOrCloudWrites() = runBlocking {
        val fixture = HistoryResourceFixture(File(temp.root, "cancel-worker.db"))
        try {
            fixture.initialize(cloud = true); fixture.seed(1, 3, 3)
            val manager = initialize(fixture)
            val actual = worker(fixture, manager, input(fixture))
            val before = manager.getWorkInfosForUniqueWork(DataSyncWorker.WORK_NAME).get().map { it.id }
            fixture.onRequest = {
                // The HTTP response arrives after the source's ABA invalidation. The production
                // requireIdentity guard throws CancellationException before persisting the detail.
                fixture.sessions.save("synthetic-B", "synthetic-refresh", 3600, "synthetic-other-account")
                fixture.sessions.save("synthetic-A2", "synthetic-refresh", 3600, "synthetic-account")
            }
            try {
                actual.doWork()
                fail("Actual DataSyncWorker must propagate cancellation, never turn it into a Result")
            } catch (_: kotlinx.coroutines.CancellationException) { }
            assertEquals(1, fixture.calls.size)
            assertTrue(fixture.calls.single().matches(Regex("/api/v1/cars/1/drives/[0-9]+")))
            assertEquals(before, manager.getWorkInfosForUniqueWork(DataSyncWorker.WORK_NAME).get().map { it.id })
            assertEquals(3, fixture.db.driveSummaryDao().countUnprocessedDrives(fixture.localId(1),
                com.matelink.data.local.entity.SchemaVersion.DRIVE_ENERGY))
            assertEquals(3, fixture.db.chargeSummaryDao().countUnprocessedCharges(fixture.localId(1),
                com.matelink.data.local.entity.SchemaVersion.CURRENT))
        } finally { fixture.close() }
    }

    @Test fun newFullSyncStillDiscoversAndFetchesIncrementalSummaries() = runBlocking(kotlinx.coroutines.Dispatchers.IO) {
        val fixture = HistoryResourceFixture(File(temp.root, "full-worker.db"))
        try {
            fixture.initialize()
            val manager = initialize(fixture)
            assertTrue(worker(fixture, manager, Data.EMPTY).doWork() is ListenableWorker.Result.Success)
            assertTrue(fixture.calls.any { it.endsWith("/cars") })
            assertTrue(fixture.calls.any { it.endsWith("/drives") })
            assertTrue(fixture.calls.any { it.endsWith("/charges") })
        } finally { fixture.close() }
    }
}
