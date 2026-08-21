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
    val durationMinutes: Int?,
    val distanceKm: Double?
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
    suspend fun currentCursor(carId: Int): DriveReportCursorEntity? =
        driveReportDao.getCursor(carId)

    suspend fun detectCompletedDrives(
        carId: Int,
        now: Long = System.currentTimeMillis()
    ): DriveReportDetectionResult {
        val candidates = driveSummaryDao.getAllChronological(carId).map {
            CompletedDriveCandidate(
                carId = it.carId,
                driveId = it.driveId,
                endDate = it.endDate,
                endedAtEpochMillis = null,
                durationMinutes = it.durationMin,
                distanceKm = it.distance
            )
        }
        return detectCompletedCandidates(carId, candidates, now)
    }

    suspend fun detectCompletedCandidates(
        carId: Int,
        candidates: List<CompletedDriveCandidate>,
        now: Long = System.currentTimeMillis()
    ): DriveReportDetectionResult {
        val cursor = driveReportDao.getCursor(carId)
        val activationCutoff = cursor
            ?.takeIf { it.lastSeenDriveId == 0 }
            ?.initializedAt
        val plan = CompletedDriveDetector.evaluate(
            carId = carId,
            currentCursor = cursor?.lastSeenDriveId,
            candidates = candidates,
            minimumEndEpochMillis = activationCutoff
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
                        detectedAt = now,
                        durationMinutes = candidate.durationMinutes,
                        distanceKm = candidate.distanceKm
                    )
                )
                if (rowId != -1L) add(candidate.toDetectedReport())
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

    suspend fun pendingNotificationReports(): List<DetectedDriveReport> =
        driveReportDao.getPendingNotifications().map { delivery ->
            DetectedDriveReport(
                carId = delivery.carId,
                driveId = delivery.driveId,
                durationMinutes = delivery.durationMinutes,
                distanceKm = delivery.distanceKm
            )
        }

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

    private fun CompletedDriveCandidate.toDetectedReport() = DetectedDriveReport(
        carId = carId,
        driveId = driveId,
        durationMinutes = durationMinutes,
        distanceKm = distanceKm
    )
}
