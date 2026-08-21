package com.matelink.data.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.matelink.data.local.SettingsDataStore
import com.matelink.data.report.DriveReportDeliveryCoordinator
import com.matelink.data.report.DriveReportDeliveryRepository
import com.matelink.data.repository.ApiResult
import com.matelink.data.repository.SettingsRepository
import com.matelink.data.repository.TeslamateRepository
import com.matelink.domain.report.CompletedDriveCandidate
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull

@HiltWorker
class DriveReportMonitorWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParameters: WorkerParameters,
    private val repository: TeslamateRepository,
    private val settingsDataStore: SettingsDataStore,
    private val settingsRepository: SettingsRepository,
    private val deliveryRepository: DriveReportDeliveryRepository,
    private val coordinator: DriveReportDeliveryCoordinator
) : CoroutineWorker(appContext, workerParameters) {
    override suspend fun doWork(): Result {
        val settings = settingsDataStore.settings.first()
        if (!settings.isConfigured || settingsRepository.mockMode.firstOrNull() == true) {
            return Result.success()
        }

        val cars = when (val result = repository.getCars()) {
            is ApiResult.Success -> result.data
            is ApiResult.Error -> return classifyError(result)
        }

        var shouldRetry = false
        for (car in cars) {
            when (val candidates = loadCandidates(car.carId)) {
                is CandidateLoad.Success -> {
                    deliveryRepository.detectCompletedCandidates(car.carId, candidates.values)
                }
                CandidateLoad.AuthFailure -> return Result.failure()
                CandidateLoad.RetryableFailure -> shouldRetry = true
            }
        }

        coordinator.deliverPending()
        return if (shouldRetry) Result.retry() else Result.success()
    }

    private suspend fun loadCandidates(carId: Int): CandidateLoad {
        val cursor = deliveryRepository.currentCursor(carId)
        val values = mutableListOf<CompletedDriveCandidate>()
        var page = 1

        while (page <= MAX_PAGES_PER_RUN) {
            when (val result = repository.getDrives(carId, page = page, show = PAGE_SIZE)) {
                is ApiResult.Error -> return if (result.code == 401 || result.code == 403) {
                    CandidateLoad.AuthFailure
                } else {
                    CandidateLoad.RetryableFailure
                }
                is ApiResult.Success -> {
                    if (result.data.isEmpty()) break
                    val pageCandidates = result.data.map {
                        CompletedDriveCandidate(
                            carId = carId,
                            driveId = it.id,
                            endDate = it.endDate.orEmpty(),
                            endedAtEpochMillis = parseEndEpochMillis(it.endDate),
                            durationMinutes = it.durationMin ?: 0,
                            distanceKm = it.distance ?: Double.NaN
                        )
                    }
                    values += pageCandidates

                    // The API lists newest drives first. One page is sufficient
                    // to establish an initial high-water mark. Later runs page
                    // until they reach the existing cursor.
                    if (cursor == null || cursor.lastSeenDriveId == 0) break
                    if (pageCandidates.any { it.driveId <= cursor.lastSeenDriveId }) break
                    if (result.data.size < PAGE_SIZE) break
                    page += 1
                }
            }
        }
        return CandidateLoad.Success(values)
    }

    private fun classifyError(error: ApiResult.Error): Result =
        if (error.code == 401 || error.code == 403) Result.failure() else Result.retry()

    private fun parseEndEpochMillis(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        return runCatching { OffsetDateTime.parse(value).toInstant().toEpochMilli() }
            .recoverCatching {
                LocalDateTime.parse(value.removeSuffix("Z"))
                    .atZone(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli()
            }
            .getOrNull()
    }

    private sealed interface CandidateLoad {
        data class Success(val values: List<CompletedDriveCandidate>) : CandidateLoad
        data object AuthFailure : CandidateLoad
        data object RetryableFailure : CandidateLoad
    }

    companion object {
        const val TAG = "DriveReportMonitorWorker"
        private const val PERIODIC_WORK_NAME = "drive_report_monitor_periodic"
        private const val IMMEDIATE_WORK_NAME = "drive_report_monitor_immediate"
        private const val PAGE_SIZE = 50
        private const val MAX_PAGES_PER_RUN = 20

        fun schedulePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<DriveReportMonitorWorker>(
                15,
                TimeUnit.MINUTES
            )
                .setConstraints(networkConstraints())
                .setBackoffCriteria(BackoffPolicy.LINEAR, 1, TimeUnit.MINUTES)
                .addTag(TAG)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        fun runNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<DriveReportMonitorWorker>()
                .setConstraints(networkConstraints())
                .setBackoffCriteria(BackoffPolicy.LINEAR, 1, TimeUnit.MINUTES)
                .addTag(TAG)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                IMMEDIATE_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                request
            )
        }

        private fun networkConstraints(): Constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
    }
}
