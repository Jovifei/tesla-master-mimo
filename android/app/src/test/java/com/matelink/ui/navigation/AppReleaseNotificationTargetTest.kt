package com.matelink.ui.navigation

import android.app.Application
import com.matelink.data.api.models.CarData
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
class AppReleaseNotificationTargetTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun notificationRequiresBothRemoteRouteAndItsOwnVerifiedStorageIdentity() = runBlocking<Unit> {
        val f = HistoryResourceFixture(File(temp.root, "notification-owner.db")); f.initialize(cloud = true)
        try {
            val target = NotificationTargetViewModel(f.vehicleContexts)
            val own = f.localId(1); f.calls.clear()
            assertTrue(target.isCurrentTarget(1, own))
            assertFalse(target.isCurrentTarget(1, own - 100))
            assertFalse(target.isCurrentTarget(1, null))
            assertFalse(target.isCurrentTarget(-1, own))
            assertTrue("Ownership admission must not call Fleet", f.calls.isEmpty())
        } finally { f.close() }
    }
    @Test fun accountSwitchAndVehicleUidRebindingRejectOldPendingIntentsWithoutHttp() = runBlocking<Unit> {
        val f = HistoryResourceFixture(File(temp.root, "notification-owner-aba.db")); f.initialize(cloud = true)
        try {
            val target = NotificationTargetViewModel(f.vehicleContexts)
            val own = f.localId(1); f.calls.clear()
            f.sessions.save("synthetic-b", "synthetic-rb", 3600, "another-user")
            assertFalse(target.isCurrentTarget(1, own))
            val scope = f.vehicleContexts.captureReadScope()
            val rebound = f.vehicleContexts.resolveVerifiedHistoryCar(CarData(1, vehicleUid = "different-vehicle"), scope)
            assertFalse(target.isCurrentTarget(1, own))
            assertTrue(target.isCurrentTarget(1, rebound.localHistoryCarId))
            assertTrue(f.calls.isEmpty())
        } finally { f.close() }
    }
}
