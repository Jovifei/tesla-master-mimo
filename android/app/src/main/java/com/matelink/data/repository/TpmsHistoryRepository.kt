package com.matelink.data.repository

import com.matelink.data.local.dao.TpmsPressureSampleDao
import com.matelink.data.local.HistoryCarIdResolver
import com.matelink.data.local.LegacyHistoryCarIdResolver
import com.matelink.data.local.entity.TpmsPressureSample
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TpmsHistoryRepository @Inject constructor(
    private val dao: TpmsPressureSampleDao,
    private val vehicleContextRepository: HistoryCarIdResolver
) {
    constructor(dao: TpmsPressureSampleDao) : this(dao, LegacyHistoryCarIdResolver)

    suspend fun saveObservation(sample: TpmsPressureSample) {
        dao.upsert(sample.normalized())
    }
    suspend fun saveObservation(remoteApiCarId: Int, sample: TpmsPressureSample) {
        val normalized = sample.normalized()
        saveObservationForHistoryCarId(
            vehicleContextRepository.requireLocalHistoryCarId(remoteApiCarId),
            normalized
        )
    }

    suspend fun saveObservationForHistoryCarId(historyCarId: Int, sample: TpmsPressureSample) {
        dao.upsert(sample.normalized().copy(carId = historyCarId))
    }

    suspend fun load7DaySamples(carId: Int, now: Long = System.currentTimeMillis()): List<TpmsPressureSample> =
        dao.getInRange(vehicleContextRepository.requireLocalHistoryCarId(carId), now - SEVEN_DAYS_MS, now + 1)

    suspend fun load30DaySamples(carId: Int, now: Long = System.currentTimeMillis()): List<TpmsPressureSample> =
        dao.getInRange(vehicleContextRepository.requireLocalHistoryCarId(carId), now - THIRTY_DAYS_MS, now + 1)

    suspend fun pruneOlderThan90Days(carId: Int, now: Long = System.currentTimeMillis()): Int =
        dao.deleteOlderThan(vehicleContextRepository.requireLocalHistoryCarId(carId), now - NINETY_DAYS_MS)

    suspend fun pruneOlderThan90DaysForHistoryCarId(historyCarId: Int, now: Long): Int =
        dao.deleteOlderThan(historyCarId, now - NINETY_DAYS_MS)

    private fun TpmsPressureSample.normalized() = copy(
        pressureFl = pressureFl?.takeIf { it.isFinite() },
        pressureFr = pressureFr?.takeIf { it.isFinite() },
        pressureRl = pressureRl?.takeIf { it.isFinite() },
        pressureRr = pressureRr?.takeIf { it.isFinite() },
        outsideTempC = outsideTempC?.takeIf { it.isFinite() }
    )

    private companion object {
        const val SEVEN_DAYS_MS = 7 * 24 * 60 * 60 * 1_000L
        const val THIRTY_DAYS_MS = 30 * 24 * 60 * 60 * 1_000L
        const val NINETY_DAYS_MS = 90 * 24 * 60 * 60 * 1_000L
    }
}
