package com.matelink.data.repository

import android.app.Application
import com.matelink.data.local.SentryStateDataStore
import com.matelink.data.api.models.CarStatus
import com.matelink.data.api.models.CarStatusDetails
import com.matelink.data.sync.isNewSentryObservation
import com.matelink.data.sync.HistoryResourceFixture
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class AppReleaseSentryObservationTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun unrelatedNewSnapshotCannotReplayTheSameSentryScreenAfterDebounce() = runBlocking {
        val f = HistoryResourceFixture(File(temp.root, "sentry-display-latch.db"))
        f.initialize(cloud = true)
        try {
            val disk = SentryStateDataStore(f.context)
            val repo = SentryStateRepository(disk, f.db.sentryAlertLogDao(), f.vehicleContexts)
            val status = CarStatus(carStatus = CarStatusDetails(sentryMode = true, centerDisplayState = "7"))
            repo.processLocalStatus(f.localId(1), true, isNewSentryObservation(null, status))
            assertEquals(1, disk.getState(f.localId(1)).eventCount)
            // Leave the old 7 in the snapshot and put the debounce window behind us.
            val prior = disk.getState(f.localId(1))
            disk.saveState(f.localId(1), prior.copy(lastEventAt = 0L))
            repo.processLocalStatus(f.localId(1), true, isNewSentryObservation("7", status))
            assertEquals("Only SOC or another field changed; no new display event", 1, disk.getState(f.localId(1)).eventCount)
            assertTrue(isNewSentryObservation("0", status))
            assertFalse(isNewSentryObservation("0", status.copy(carStatus = status.carStatus!!.copy(sentryMode = null))))
        } finally { f.close() }
    }
    @Test fun absentModeCannotEndAnObservedActiveSession() = runBlocking {
        val f = HistoryResourceFixture(File(temp.root, "sentry-observation.db"))
        f.initialize(cloud = true)
        try {
            val disk = SentryStateDataStore(f.context)
            val repo = SentryStateRepository(disk, f.db.sentryAlertLogDao(), f.vehicleContexts)
            repo.processStatus(1, true, false)
            assertTrue(disk.getState(f.localId(1)).sentryActive)
            assertNull(repo.processStatus(1, null, false))
            assertTrue("Unknown is not an off observation", disk.getState(f.localId(1)).sentryActive)
            assertEquals(SentryEvent.SessionEnded, repo.processStatus(1, false, false))
            assertFalse(disk.getState(f.localId(1)).sentryActive)
        } finally { f.close() }
    }
}
