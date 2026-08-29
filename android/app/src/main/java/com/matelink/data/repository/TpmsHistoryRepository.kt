package com.matelink.data.repository

import com.matelink.data.local.dao.TpmsPressureSampleDao
import com.matelink.data.local.entity.TpmsPressureSample
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TpmsHistoryRepository @Inject constructor(
    private val dao: TpmsPressureSampleDao
) {
    suspend fun saveObservation(sample: TpmsPressureSample) {
        val normalized = sample.normalized()
        dao.upsert(normalized)
    }

    suspend fun load7DaySamples(carId: Int, now: Long = System.currentTimeMillis()): List<TpmsPressureSample> =
        dao.getInRange(carId, now - SEVEN_DAYS_MS, now + 1)

    suspend fun load30DaySamples(carId: Int, now: Long = System.currentTimeMillis()): List<TpmsPressureSample> =
        dao.getInRange(carId, now - THIRTY_DAYS_MS, now + 1)

    suspend fun pruneOlderThan90Days(carId: Int, now: Long = System.currentTimeMillis()): Int =
        dao.deleteOlderThan(carId, now - NINETY_DAYS_MS)

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
