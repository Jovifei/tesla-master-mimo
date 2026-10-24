package com.matelink.ui.screens.dashboard

import com.matelink.data.api.models.CarStatus
import com.matelink.data.local.HistoryConnectionSource
import com.matelink.data.local.HistoryReadScope
import com.matelink.data.local.VehicleContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardStatusIdentityTest {
    private val alice = HistoryReadScope(HistoryConnectionSource.CLOUD, "cloud", "alice", "https://one.example")
    private val bob = HistoryReadScope(HistoryConnectionSource.CLOUD, "cloud", "bob", "https://one.example")
    private val car = VehicleContext(1, "alice-uid", -1, HistoryConnectionSource.CLOUD, "cloud")

    @Test fun delayedAliceSnapshotCannotRenderAfterBobUsesSameNumericCarId() = runTest {
        val pending = CompletableDeferred<CarStatus>()
        var activeScope = alice
        var rendered: CarStatus? = null
        val request = launch {
            val response = pending.await()
            if (dashboardStatusMayPublish(alice, car, activeScope, 1, 1, 7, 7, true))
                rendered = response
        }
        runCurrent()
        activeScope = bob
        pending.complete(CarStatus(displayName = "Alice's car"))
        request.join()

        assertNull(rendered)
    }

    @Test fun currentVerifiedVehicleMayRenderButReboundUidMayNot() {
        assertTrue(dashboardStatusMayPublish(alice, car, alice, 1, 1, 7, 7, true))
        assertFalse(dashboardStatusMayPublish(alice, car, alice, 1, 1, 7, 7, false))
        assertFalse(dashboardStatusMayPublish(alice, car, alice, 1, 2, 7, 7, true))
    }
}
