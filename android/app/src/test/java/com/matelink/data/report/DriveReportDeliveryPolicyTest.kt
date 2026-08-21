package com.matelink.data.report

import org.junit.Assert.assertEquals
import org.junit.Test

class DriveReportDeliveryPolicyTest {
    @Test
    fun noPendingReportsDoesNothing() {
        assertEquals(
            DriveReportDeliverySurface.NOTHING,
            DriveReportDeliveryPolicy.decide(
                isForeground = false,
                notificationsEnabled = true,
                pendingCount = 0
            )
        )
    }

    @Test
    fun foregroundUsesInAppPrompt() {
        assertEquals(
            DriveReportDeliverySurface.FOREGROUND_PROMPT,
            DriveReportDeliveryPolicy.decide(
                isForeground = true,
                notificationsEnabled = true,
                pendingCount = 1
            )
        )
    }

    @Test
    fun deniedNotificationsKeepsReportPending() {
        assertEquals(
            DriveReportDeliverySurface.PENDING_ONLY,
            DriveReportDeliveryPolicy.decide(
                isForeground = false,
                notificationsEnabled = false,
                pendingCount = 1
            )
        )
    }

    @Test
    fun oneBackgroundReportUsesSingleNotification() {
        assertEquals(
            DriveReportDeliverySurface.SINGLE_NOTIFICATION,
            DriveReportDeliveryPolicy.decide(
                isForeground = false,
                notificationsEnabled = true,
                pendingCount = 1
            )
        )
    }

    @Test
    fun multipleBackgroundReportsUseSummaryNotification() {
        assertEquals(
            DriveReportDeliverySurface.SUMMARY_NOTIFICATION,
            DriveReportDeliveryPolicy.decide(
                isForeground = false,
                notificationsEnabled = true,
                pendingCount = 3
            )
        )
    }
}
