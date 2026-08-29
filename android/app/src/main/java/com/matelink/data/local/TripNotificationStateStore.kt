package com.matelink.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.matelink.data.local.entity.DriveSummary
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

private val Context.tripNotificationDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "trip_notification_state"
)

internal data class CompletedTripNotificationPlan(
    val toNotify: List<DriveSummary>,
    val watermarkToSave: Int?
)

internal interface TripNotificationWatermarks {
    suspend fun getWatermark(carId: Int): Int?
    suspend fun advanceWatermark(carId: Int, driveId: Int)
}

internal interface CompletedTripNotifier {
    fun showCompletedDrive(carId: Int, summary: DriveSummary): Boolean
}

/** Plans first-sync suppression and later one-time completed-drive notifications. */
internal fun completedTripNotificationPlan(
    watermark: Int?,
    summaries: List<DriveSummary>
): CompletedTripNotificationPlan {
    val completed = summaries
        .distinctBy { it.driveId }
        .sortedBy { it.driveId }
    val latestId = completed.lastOrNull()?.driveId
    if (watermark == null) {
        return CompletedTripNotificationPlan(toNotify = emptyList(), watermarkToSave = latestId)
    }
    return CompletedTripNotificationPlan(
        toNotify = completed.filter { it.driveId > watermark },
        watermarkToSave = latestId?.takeIf { it > watermark }
    )
}

/** Serializes notification acknowledgement so concurrent syncs cannot duplicate a trip. */
internal class CompletedTripNotificationProcessor(
    private val watermarks: TripNotificationWatermarks,
    private val notifier: CompletedTripNotifier
) {
    private val mutex = Mutex()

    suspend fun process(carId: Int, summaries: List<DriveSummary>) = mutex.withLock {
        val watermark = watermarks.getWatermark(carId)
        val plan = completedTripNotificationPlan(watermark, summaries)
        if (watermark == null) {
            plan.watermarkToSave?.let { watermarks.advanceWatermark(carId, it) }
            return@withLock
        }
        for (summary in plan.toNotify) {
            if (!notifier.showCompletedDrive(carId, summary)) return@withLock
            watermarks.advanceWatermark(carId, summary.driveId)
        }
    }
}

/** Stores the last completed drive processed for notification per vehicle. */
@Singleton
class TripNotificationStateStore @Inject constructor(
    @ApplicationContext private val context: Context
) : TripNotificationWatermarks {
    private fun watermarkKey(carId: Int) = longPreferencesKey("trip_notification_watermark_$carId")

    override suspend fun getWatermark(carId: Int): Int? = context.tripNotificationDataStore.data
        .map { it[watermarkKey(carId)]?.toInt() }
        .first()

    override suspend fun advanceWatermark(carId: Int, driveId: Int) {
        context.tripNotificationDataStore.edit { preferences ->
            val current = preferences[watermarkKey(carId)]?.toInt()
            if (current == null || driveId > current) {
                preferences[watermarkKey(carId)] = driveId.toLong()
            }
        }
    }
}
