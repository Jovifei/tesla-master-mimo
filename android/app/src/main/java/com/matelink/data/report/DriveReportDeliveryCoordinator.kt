package com.matelink.data.report

import com.matelink.app.AppVisibilityTracker
import com.matelink.notification.DriveReportNotificationManager
import javax.inject.Inject
import javax.inject.Singleton

enum class DriveReportDeliverySurface {
    FOREGROUND_PROMPT,
    SINGLE_NOTIFICATION,
    SUMMARY_NOTIFICATION,
    PENDING_ONLY,
    NOTHING
}

object DriveReportDeliveryPolicy {
    fun decide(
        isForeground: Boolean,
        notificationsEnabled: Boolean,
        pendingCount: Int
    ): DriveReportDeliverySurface = when {
        pendingCount <= 0 -> DriveReportDeliverySurface.NOTHING
        isForeground -> DriveReportDeliverySurface.FOREGROUND_PROMPT
        !notificationsEnabled -> DriveReportDeliverySurface.PENDING_ONLY
        pendingCount == 1 -> DriveReportDeliverySurface.SINGLE_NOTIFICATION
        else -> DriveReportDeliverySurface.SUMMARY_NOTIFICATION
    }
}

@Singleton
class DriveReportDeliveryCoordinator @Inject constructor(
    private val repository: DriveReportDeliveryRepository,
    private val appVisibilityTracker: AppVisibilityTracker,
    private val notificationManager: DriveReportNotificationManager
) {
    suspend fun deliverPending() {
        val pending = repository.pendingNotificationReports()
        when (
            DriveReportDeliveryPolicy.decide(
                isForeground = appVisibilityTracker.isForeground,
                notificationsEnabled = notificationManager.canNotify(),
                pendingCount = pending.size
            )
        ) {
            DriveReportDeliverySurface.SINGLE_NOTIFICATION -> {
                val report = pending.single()
                notificationManager.showSingle(report)
                repository.markNotificationPosted(report.carId, report.driveId)
            }
            DriveReportDeliverySurface.SUMMARY_NOTIFICATION -> {
                notificationManager.showSummary(pending.size)
                pending.forEach {
                    repository.markNotificationPosted(it.carId, it.driveId)
                }
            }
            DriveReportDeliverySurface.FOREGROUND_PROMPT,
            DriveReportDeliverySurface.PENDING_ONLY,
            DriveReportDeliverySurface.NOTHING -> Unit
        }
    }
}
