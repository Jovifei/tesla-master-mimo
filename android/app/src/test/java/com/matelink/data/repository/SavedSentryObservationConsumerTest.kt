package com.matelink.data.repository

import android.app.Application
import com.matelink.data.api.models.CarStatus
import com.matelink.data.api.models.CarStatusDetails
import com.matelink.data.local.SentryStateDataStore
import com.matelink.data.sync.HistoryResourceFixture
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class SavedSentryObservationConsumerTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun status(mode: Boolean? = true, display: String? = "7") =
        CarStatus(carStatus = CarStatusDetails(sentryMode = mode, centerDisplayState = display))

    @Test fun persistedDisplayLatchSurvivesReopenAndUnrelatedNewSnapshotTime() = runBlocking {
        val f = HistoryResourceFixture(File(temporary.root, "sentry-observed-reopen.db"))
        f.initialize(cloud = true)
        try {
            val disk = SentryStateDataStore(f.context)
            val local = f.localId(1)
            disk.resetSession(local)
            val at = maxOf(System.currentTimeMillis(), disk.getState(local).lastObservationAt + 1)
            val repo = SentryStateRepository(disk, f.db.sentryAlertLogDao(), f.vehicleContexts)
            repo.consumeObservation(local, status(), at)
            disk.saveState(local, disk.getState(local).copy(lastEventAt = 0))
            val reopened = SentryStateRepository(SentryStateDataStore(f.context), f.db.sentryAlertLogDao(), f.vehicleContexts)
            reopened.consumeObservation(local, status(), at + 1)
            assertEquals(1, disk.getState(local).eventCount)
            assertEquals("7", disk.getState(local).lastObservedDisplay)
            assertEquals(at + 1, disk.getState(local).lastObservationAt)
            assertEquals(1, f.db.sentryAlertLogDao().observeAll(local).first().size)
        } finally { f.close() }
    }

    @Test fun unknownFieldsDoNotRearmButAnObservedNonSevenDoes() = runBlocking {
        val f = HistoryResourceFixture(File(temporary.root, "sentry-observed-unknown.db"))
        f.initialize(cloud = true)
        try {
            val disk = SentryStateDataStore(f.context)
            val local = f.localId(1)
            disk.resetSession(local)
            val at = maxOf(System.currentTimeMillis(), disk.getState(local).lastObservationAt + 1)
            val repo = SentryStateRepository(disk, f.db.sentryAlertLogDao(), f.vehicleContexts)
            repo.consumeObservation(local, status(), at)
            repo.consumeObservation(local, status(mode = null, display = "0"), at + 1)
            repo.consumeObservation(local, status(display = null), at + 2)
            disk.saveState(local, disk.getState(local).copy(lastEventAt = 0))
            repo.consumeObservation(local, status(), at + 3)
            assertEquals(1, disk.getState(local).eventCount)
            repo.consumeObservation(local, status(display = "0"), at + 4)
            repo.consumeObservation(local, status(), at + 5)
            assertEquals(2, disk.getState(local).eventCount)
        } finally { f.close() }
    }

    @Test fun twoSavedObservationConsumersDispatchExactlyOneClaim() = runBlocking {
        val f = HistoryResourceFixture(File(temporary.root, "sentry-observed-claim.db"))
        f.initialize(cloud = true)
        try {
            val disk = SentryStateDataStore(f.context)
            val local = f.localId(1)
            disk.resetSession(local)
            val at = maxOf(System.currentTimeMillis(), disk.getState(local).lastObservationAt + 1)
            val repo = SentryStateRepository(disk, f.db.sentryAlertLogDao(), f.vehicleContexts)
            val captured = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var dispatched = 0
            val first = async(Dispatchers.Default) {
                var checks = 0
                repo.consumeObservation(local, status(), at, ensureCurrent = {
                    if (++checks == 2) { captured.complete(Unit); release.await() }
                }) { dispatched++ }
            }
            captured.await()
            val second = async(Dispatchers.Default) { repo.consumeObservation(local, status(), at) { dispatched++ } }
            withTimeoutOrNull(150) { second.await() }
            release.complete(Unit)
            assertEquals(listOf(true, false), listOf(first.await(), second.await()))
            assertEquals(1, dispatched)
            assertEquals(1, disk.getState(local).eventCount)
        } finally { f.close() }
    }
}
