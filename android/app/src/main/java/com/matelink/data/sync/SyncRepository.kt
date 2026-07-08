package com.matelink.data.sync

import android.util.Log
import com.matelink.data.api.models.DriveData
import com.matelink.data.api.models.ChargeData
import com.matelink.data.local.dao.ChargeSummaryDao
import com.matelink.data.local.dao.DriveSummaryDao
import com.matelink.data.local.entity.DriveSummary
import com.matelink.data.local.entity.ChargeSummary
import com.matelink.data.repository.ApiResult
import com.matelink.data.repository.GeocodingRepository
import com.matelink.data.repository.TeslamateRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Coordinates syncing of drives and charges data from TeslaMate API to local database.
 */
@Singleton
class SyncRepository @Inject constructor(
    private val teslamateRepository: TeslamateRepository,
    private val driveSummaryDao: DriveSummaryDao,
    private val chargeSummaryDao: ChargeSummaryDao,
    private val syncManager: SyncManager,
    private val geocodingRepository: GeocodingRepository
) {
    companion object {
        private const val TAG = "SyncRepository"
    }

    /**
     * Sync all data for a car. Returns true if successful, false on network error.
     */
    suspend fun syncCar(carId: Int): Boolean {
        Log.d(TAG, "Starting sync for car $carId")

        // Phase 1: Sync summaries
        syncManager.updateSummaryProgress(carId)

        val drivesSynced = syncDriveSummaries(carId)
        if (!drivesSynced) return false

        val chargesSynced = syncChargeSummaries(carId)
        if (!chargesSynced) return false

        syncManager.markSummariesComplete(carId)

        // Phase 2: Sync details
        syncDriveDetails(carId)
        syncChargeDetails(carId)

        // Phase 3: Enqueue geocoding for new locations
        enqueueGeocoding(carId)

        syncManager.markSyncComplete(carId)
        Log.d(TAG, "Sync complete for car $carId")
        return true
    }

    private suspend fun syncDriveSummaries(carId: Int): Boolean {
        return try {
            var page = 1
            var hasMore = true

            while (hasMore) {
                when (val result = teslamateRepository.getDrives(carId, page = page, show = 50)) {
                    is ApiResult.Success -> {
                        val drives = result.data
                        if (drives.isEmpty()) {
                            hasMore = false
                        } else {
                            val summaries = drives.mapNotNull { it.toSummary(carId) }
                            driveSummaryDao.upsertAll(summaries)
                            page++
                        }
                    }
                    is ApiResult.Error -> {
                        Log.e(TAG, "Failed to sync drive summaries: ${result.message}")
                        return false
                    }
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error syncing drive summaries", e)
            false
        }
    }

    private suspend fun syncChargeSummaries(carId: Int): Boolean {
        return try {
            var page = 1
            var hasMore = true

            while (hasMore) {
                when (val result = teslamateRepository.getCharges(carId, page = page, show = 50)) {
                    is ApiResult.Success -> {
                        val charges = result.data
                        if (charges.isEmpty()) {
                            hasMore = false
                        } else {
                            val summaries = charges.mapNotNull { it.toSummary(carId) }
                            chargeSummaryDao.upsertAll(summaries)
                            page++
                        }
                    }
                    is ApiResult.Error -> {
                        Log.e(TAG, "Failed to sync charge summaries: ${result.message}")
                        return false
                    }
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error syncing charge summaries", e)
            false
        }
    }

    private suspend fun syncDriveDetails(carId: Int) {
        try {
            val unprocessedIds = driveSummaryDao.getUnprocessedDriveIds(carId, com.matelink.data.local.entity.SchemaVersion.CURRENT)
            for (driveId in unprocessedIds) {
                val summary = driveSummaryDao.get(driveId) ?: continue
                try {
                    when (val result = teslamateRepository.getDriveDetail(carId, summary.driveId)) {
                        is ApiResult.Success -> {
                            val detail = result.data
                            driveSummaryDao.upsert(summary.copy(
                                startAddress = detail.startAddress ?: summary.startAddress,
                                endAddress = detail.endAddress ?: summary.endAddress,
                                outsideTempAvg = detail.outsideTempAvg ?: summary.outsideTempAvg,
                                speedMax = detail.speedMax ?: summary.speedMax,
                                powerMax = detail.powerMax ?: summary.powerMax,
                                powerMin = detail.powerMin ?: summary.powerMin,
                                startBatteryLevel = detail.startBatteryLevel ?: summary.startBatteryLevel,
                                endBatteryLevel = detail.endBatteryLevel ?: summary.endBatteryLevel
                            ))
                            syncManager.updateDriveDetailProgress(carId, summary.driveId)
                        }
                        is ApiResult.Error -> {
                            Log.w(TAG, "Failed to sync drive detail ${summary.driveId}: ${result.message}")
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error syncing drive detail ${summary.driveId}", e)
                }
            }
            syncManager.markDriveDetailsComplete(carId)
        } catch (e: Exception) {
            Log.e(TAG, "Error in drive detail sync", e)
        }
    }

    private suspend fun syncChargeDetails(carId: Int) {
        try {
            val unprocessedIds = chargeSummaryDao.getUnprocessedChargeIds(carId, com.matelink.data.local.entity.SchemaVersion.CURRENT)
            for (chargeId in unprocessedIds) {
                val summary = chargeSummaryDao.get(chargeId) ?: continue
                try {
                    when (val result = teslamateRepository.getChargeDetail(carId, summary.chargeId)) {
                        is ApiResult.Success -> {
                            val detail = result.data
                            chargeSummaryDao.upsert(summary.copy(
                                address = detail.address ?: summary.address,
                                outsideTempAvg = detail.outsideTempAvg ?: summary.outsideTempAvg,
                                startBatteryLevel = detail.startBatteryLevel ?: summary.startBatteryLevel,
                                endBatteryLevel = detail.endBatteryLevel ?: summary.endBatteryLevel
                            ))
                            syncManager.updateChargeDetailProgress(carId, summary.chargeId)
                        }
                        is ApiResult.Error -> {
                            Log.w(TAG, "Failed to sync charge detail ${summary.chargeId}: ${result.message}")
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error syncing charge detail ${summary.chargeId}", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in charge detail sync", e)
        }
    }

    private suspend fun enqueueGeocoding(carId: Int) {
        try {
            val drives = driveSummaryDao.getAllChronological(carId)
            val locations = mutableListOf<Pair<Double, Double>>()
            // TODO: extract positions from drive data for geocoding
            if (locations.isNotEmpty()) {
                geocodingRepository.enqueueLocationsForCar(carId, locations)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error enqueuing geocoding", e)
        }
    }

    private fun DriveData.toSummary(carId: Int): DriveSummary? {
        val start = startDate ?: return null
        val end = endDate ?: return null
        return DriveSummary(
            driveId = id,
            carId = carId,
            startDate = start,
            endDate = end,
            distance = distance ?: 0.0,
            durationMin = durationMin ?: 0,
            startAddress = startAddress ?: "",
            endAddress = endAddress ?: "",
            speedMax = speedMax ?: 0,
            speedAvg = speedAvg?.toInt() ?: 0,
            powerMax = powerMax ?: 0,
            powerMin = powerMin ?: 0,
            startBatteryLevel = startBatteryLevel ?: 0,
            endBatteryLevel = endBatteryLevel ?: 0,
            outsideTempAvg = outsideTempAvg,
            insideTempAvg = insideTempAvg,
            energyConsumed = energyConsumedNet,
            efficiency = efficiencyWhKm
        )
    }

    private fun ChargeData.toSummary(carId: Int): ChargeSummary? {
        val start = startDate ?: return null
        val end = endDate ?: return null
        return ChargeSummary(
            chargeId = chargeId,
            carId = carId,
            startDate = start,
            endDate = end,
            durationMin = durationMin ?: 0,
            address = address ?: "",
            latitude = latitude ?: 0.0,
            longitude = longitude ?: 0.0,
            energyAdded = chargeEnergyAdded ?: 0.0,
            energyUsed = chargeEnergyUsed,
            cost = cost,
            startBatteryLevel = startBatteryLevel ?: 0,
            endBatteryLevel = endBatteryLevel ?: 0,
            outsideTempAvg = outsideTempAvg,
            odometer = odometer ?: 0.0
        )
    }
}
