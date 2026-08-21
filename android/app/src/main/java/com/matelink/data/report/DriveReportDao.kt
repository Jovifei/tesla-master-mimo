package com.matelink.data.report

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface DriveReportDao {
    @Query("SELECT * FROM drive_report_cursor WHERE carId = :carId")
    suspend fun getCursor(carId: Int): DriveReportCursorEntity?

    @Upsert
    suspend fun upsertCursor(cursor: DriveReportCursorEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertDelivery(delivery: DriveReportDeliveryEntity): Long

    @Query("""
        SELECT * FROM drive_report_delivery
        WHERE carId = :carId AND driveId = :driveId
    """)
    suspend fun getDelivery(carId: Int, driveId: Int): DriveReportDeliveryEntity?

    @Query("""
        SELECT * FROM drive_report_delivery
        WHERE openedAt IS NULL AND dismissedAt IS NULL
        ORDER BY detectedAt DESC
    """)
    fun observeUnseen(): Flow<List<DriveReportDeliveryEntity>>

    @Query("""
        SELECT * FROM drive_report_delivery
        WHERE openedAt IS NULL AND dismissedAt IS NULL
        ORDER BY detectedAt DESC
        LIMIT 1
    """)
    suspend fun getLatestUnseen(): DriveReportDeliveryEntity?

    @Query("""
        SELECT * FROM drive_report_delivery
        WHERE notificationPostedAt IS NULL
          AND openedAt IS NULL
          AND dismissedAt IS NULL
        ORDER BY detectedAt ASC
    """)
    suspend fun getPendingNotifications(): List<DriveReportDeliveryEntity>

    @Query("""
        SELECT COUNT(*) FROM drive_report_delivery
        WHERE openedAt IS NULL AND dismissedAt IS NULL
    """)
    suspend fun countUnseen(): Int

    @Query("""
        UPDATE drive_report_delivery
        SET notificationPostedAt = :postedAt,
            deliveryState = :state
        WHERE carId = :carId AND driveId = :driveId
          AND openedAt IS NULL AND dismissedAt IS NULL
    """)
    suspend fun markNotificationPosted(
        carId: Int,
        driveId: Int,
        postedAt: Long,
        state: String
    )

    @Query("""
        UPDATE drive_report_delivery
        SET openedAt = :openedAt,
            deliveryState = :state
        WHERE carId = :carId AND driveId = :driveId
    """)
    suspend fun markOpened(
        carId: Int,
        driveId: Int,
        openedAt: Long,
        state: String
    )

    @Query("""
        UPDATE drive_report_delivery
        SET dismissedAt = :dismissedAt,
            deliveryState = :state
        WHERE carId = :carId AND driveId = :driveId
          AND openedAt IS NULL
    """)
    suspend fun markDismissed(
        carId: Int,
        driveId: Int,
        dismissedAt: Long,
        state: String
    )
}
