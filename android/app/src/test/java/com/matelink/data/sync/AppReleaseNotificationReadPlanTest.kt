package com.matelink.data.sync

import com.matelink.data.local.ConnectionMode
import org.junit.Assert.*
import org.junit.Test

class AppReleaseNotificationReadPlanTest {
    @Test fun cloudWithBlankSelfHostedUrlConsumesAlreadySavedObservations() {
        assertEquals(NotificationReadPlan.SAVED_CLOUD, notificationReadPlan(ConnectionMode.TESLA_CLOUD, false))
        assertEquals(NotificationReadPlan.SAVED_CLOUD, notificationReadPlan(ConnectionMode.TESLA_CLOUD, true))
    }
    @Test fun selfHostedStillNeedsItsExplicitServerAndUsesExistingReadBudget() {
        assertEquals(NotificationReadPlan.UNCONFIGURED, notificationReadPlan(ConnectionMode.SELF_HOSTED, false))
        assertEquals(NotificationReadPlan.REMOTE_SELF_HOSTED, notificationReadPlan(ConnectionMode.SELF_HOSTED, true))
    }
}
