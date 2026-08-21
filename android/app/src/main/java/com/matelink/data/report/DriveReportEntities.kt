package com.matelink.data.report

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

object DriveReportDeliveryState {
    const val PENDING = "pending"
    const val NOTIFIED = "notified"
    const val OPENED = "opened"
    const val DISMISSED = "dismissed"
}

@Entity(
    tableName = "drive_report_delivery",
    primaryKeys = ["carId", "driveId"],
    indices = [
        Index(value = ["deliveryState"]),
        Index(value = ["carId", "detectedAt"])
    ]
)
data class DriveReportDeliveryEntity(
    val carId: Int,
    val driveId: Int,
    val detectedAt: Long,
    val notificationPostedAt: Long? = null,
    val openedAt: Long? = null,
    val dismissedAt: Long? = null,
    val deliveryState: String = DriveReportDeliveryState.PENDING,
    val reportVersion: Int = 1
)

@Entity(tableName = "drive_report_cursor")
data class DriveReportCursorEntity(
    @PrimaryKey
    val carId: Int,
    val lastSeenDriveId: Int,
    val initializedAt: Long,
    val lastCheckedAt: Long
)
