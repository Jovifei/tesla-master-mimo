package com.matelink.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ChargingNotificationIdentityTest {
    @Test
    fun displayAndCancelShareTheResolvedLocalHistoryNamespaceId() {
        val remoteApiCarId = 42
        val localHistoryCarId = -1

        val displayId = chargingNotificationId(localHistoryCarId)
        val cancelId = chargingNotificationId(localHistoryCarId)

        assertEquals(displayId, cancelId)
        assertNotEquals(
            ChargingNotificationManager.NOTIFICATION_ID_BASE + remoteApiCarId,
            displayId
        )
    }
}
