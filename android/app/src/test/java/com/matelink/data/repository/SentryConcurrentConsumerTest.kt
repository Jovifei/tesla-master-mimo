package com.matelink.data.repository

import android.app.Application
import com.matelink.data.local.SentryStateDataStore
import com.matelink.data.sync.HistoryResourceFixture
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class SentryConcurrentConsumerTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun twoCloudConsumersCannotBothCountTheSameFirstDisplayedSeven() = runBlocking {
        val f = HistoryResourceFixture(File(temporary.root, "sentry-concurrent.db"))
        f.initialize(cloud = true)
        try {
            val disk = SentryStateDataStore(f.context)
            val local = f.localId(1)
            disk.resetSession(local)
            val repo = SentryStateRepository(disk, f.db.sentryAlertLogDao(), f.vehicleContexts)
            val readOldState = CompletableDeferred<Unit>()
            val releaseFirst = CompletableDeferred<Unit>()
            val first = async(Dispatchers.Default) {
                var checks = 0
                repo.processLocalStatus(local, true, true, ensureCurrent = {
                    if (++checks == 2) { readOldState.complete(Unit); releaseFirst.await() }
                })
            }
            readOldState.await()
            val second = async(Dispatchers.Default) { repo.processLocalStatus(local, true, true) }
            // The old consumer can finish with the first suspended after reading
            // its state. A serialized consumer must wait for the first claim.
            withTimeoutOrNull(150) { second.await() }
            releaseFirst.complete(Unit)
            first.await(); second.await()
            assertEquals(1, disk.getState(local).eventCount)
            assertEquals(1, f.db.sentryAlertLogDao().observeAll(local).first().size)
        } finally { f.close() }
    }
}
