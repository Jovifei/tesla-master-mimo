package com.matelink.data.sync

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.matelink.data.repository.GeocodingRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Background worker for processing geocoding queue items.
 *
 * Fetches address data from Nominatim for locations that need geocoding,
 * caches results, and updates progress tracking.
 */
@HiltWorker
class GeocodeWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val geocodingRepository: GeocodingRepository
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        const val TAG = "GeocodeWorker"
        const val WORK_NAME = "geocode_work"
    }

    override suspend fun doWork(): Result {
        Log.d(TAG, "Starting geocoding worker")

        try {
            var processedCount = 0

            while (true) {
                // Get next batch of items to geocode
                val batch = geocodingRepository.getNextBatch(1)
                if (batch.isEmpty()) {
                    Log.d(TAG, "No more items to geocode")
                    break
                }

                val item = batch.first()
                val result = geocodingRepository.geocodeAndCache(item)

                if (result != null) {
                    processedCount++
                    geocodingRepository.markGeocoded(item.carId)
                    Log.d(TAG, "Geocoded (${item.gridLat}, ${item.gridLon}) -> ${result.countryName}")
                }

                // Small delay to be nice to Nominatim API
                kotlinx.coroutines.delay(1100)
            }

            Log.d(TAG, "Geocoding complete. Processed $processedCount items")
            return Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Error in geocoding worker", e)
            return if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }
}
