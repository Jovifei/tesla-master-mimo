package com.matelink.data.report

import com.matelink.data.local.dao.DriveSummaryDao
import com.matelink.domain.report.CompletedDriveCandidate
import com.matelink.domain.report.CompletedDriveDetector
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

data class DetectedDriveReport(
    val carId: Int,
    val driveId: Int,
    val durationMinutes: Int,
    val distanceKm: Double
)

data class DriveReportDetectionResult(
    val initialized: Boolean,
    val newReports: List<DetectedDriveReport>
)

@Singleton
class DriveReportDeliveryRepository @Inject constructor(
    private val driveSummaryDao: DriveSummaryDao,
    private val driveReportDao: DriveReportDao
) {
    suspend fun detectCompletedDrives(
        carId: Int,
        now: Long = System.currentTimeMillis()
    ): DriveReportDetectionResult {
        val candidates = driveSummaryDao.getAllChronological(carId).map {
            CompletedDriveCandidate(
                carId = it.carId,
                driveId = it.driveId,
                endDate = it.endDate,
                durationMinutes = it.durationMin,
                distanceKm = it.distance
            )
        }
        val cursor = driveReportDao.getCursor(carId)
        val plan = CompletedDriveDetector.evaluate(
            carId = carId,
            currentCursor = cursor?.lastSeenDriveId,
            candidates = candidates
        )

        if (cursor == null) {
            driveReportDao.upsertCursor(
                DriveReportCursorEntity(
                    carId = carId,
                    lastSeenDriveId = plan.nextCursor,
                    initializedAt = now,
                    lastCheckedAt = now
                )
            )
            return DriveReportDetectionResult(initialized = true, newReports = emptyList())
        }

        val inserted = buildList {
            for (candidate in plan.newDrives) {
                val rowId = driveReportDao.insertDelivery(
                    DriveReportDeliveryEntity(
                        carId = candidate.carId,
                        driveId = candidate.driveId,
                        detectedAt = now
                    )
                )
                if (rowId != -1L) {
                    add(
                        DetectedDriveReport(
                            carId = candidate.carId,
                            driveId = candidate.driveId,
                            durationMinutes = candidate.durationMinutes,
                            distanceKm = candidate.distanceKm
                        )
                    )
                }
            }
        }

        driveReportDao.upsertCursor(
            cursor.copy(
                lastSeenDriveId = plan.nextCursor,
                lastCheckedAt = now
            )
        )
        return DriveReportDetectionResult(initialized = false, newReports = inserted)
    }

    fun observeUnseenReports(): Flow<List<DriveReportDeliveryEntity>> =
        driveReportDao.observeUnseen()

    suspend fun latestUnseenReport(): DriveReportDeliveryEntity? =
        driveReportDao.getLatestUnseen()

    suspend fun unseenCount(): Int = driveReportDao.countUnseen()

    suspend fun markNotificationPosted(
        carId: Int,
        driveId: Int,
        at: Long = System.currentTimeMillis()
    ) {
        driveReportDao.markNotificationPosted(carId, driveId, at, DriveReportDeliveryState.NOTIFIED)
    }

    suspend fun markOpened(
        carId: Int,
        driveId: Int,
        at: Long = System.currentTimeMillis()
    ) {
        driveReportDao.markOpened(carId, driveId, at, DriveReportDeliveryState.OPENED)
    }

    suspend fun markDismissed(
        carId: Int,
        driveId: Int,
        at: Long = System.currentTimeMillis()
    ) {
        driveReportDao.markDismissed(carId, driveId, at, DriveReportDeliveryState.DISMISSED)
    }
}
