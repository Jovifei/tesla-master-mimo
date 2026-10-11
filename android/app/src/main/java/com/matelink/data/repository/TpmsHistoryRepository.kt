package com.matelink.data.repository

import com.matelink.data.local.dao.TpmsPressureSampleDao
import com.matelink.data.local.HistoryCarIdResolver
import com.matelink.data.local.LegacyHistoryCarIdResolver
import com.matelink.data.local.entity.TpmsPressureSample
import com.matelink.data.local.entity.TPMS_VERIFIED_COLLECTOR
import com.matelink.domain.analytics.TpmsPressureChange
import com.matelink.data.local.TirePosition
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TpmsHistoryRepository @Inject constructor(
    private val dao: TpmsPressureSampleDao,
    private val vehicleContextRepository: HistoryCarIdResolver
) {
    constructor(dao: TpmsPressureSampleDao) : this(dao, LegacyHistoryCarIdResolver)

    suspend fun saveObservation(sample: TpmsPressureSample) {
        dao.upsertMergingObservation(sample.normalized())
    }
    suspend fun saveObservation(remoteApiCarId: Int, sample: TpmsPressureSample) {
        val normalized = sample.normalized()
        saveObservationForHistoryCarId(
            vehicleContextRepository.requireLocalHistoryCarId(remoteApiCarId),
            normalized
        )
    }

    suspend fun saveObservationForHistoryCarId(historyCarId: Int, sample: TpmsPressureSample) {
        dao.upsertMergingObservation(sample.normalized().copy(carId = historyCarId))
    }

    /** Does not fabricate a new event on repeated polling with identical source time. */
    suspend fun insertNovelVerifiedForHistoryCarId(historyCarId: Int, sample: TpmsPressureSample): TpmsPressureSample? =
        dao.insertNovelCollectorObservation(sample.normalized().copy(carId = historyCarId))

    /** Previously saved source evidence may retry permission-denied alerts,
     * but only when every non-null candidate wheel exactly matches the receipt.
     */
    suspend fun hasMatchingVerifiedCollectorReceipt(historyCarId: Int, sample: TpmsPressureSample): Boolean {
        val previous = dao.getAt(historyCarId, sample.observedAt) ?: return false
        if (previous.provenance != TPMS_VERIFIED_COLLECTOR || sample.provenance != TPMS_VERIFIED_COLLECTOR) return false
        val incoming = listOf(sample.pressureFl,sample.pressureFr,sample.pressureRl,sample.pressureRr)
        val existing = listOf(previous.pressureFl,previous.pressureFr,previous.pressureRl,previous.pressureRr)
        return incoming.any { it != null } && incoming.indices.all { i ->
            incoming[i] == null || incoming[i] == existing[i]
        }
    }

    suspend fun latestVerifiedPerWheel(historyCarId: Int, before: Long, windowHours: Int): List<TpmsPressureSample> =
        dao.latestPerWheel(historyCarId, before - windowHours.toLong() * 3_600_000L, before)

    /** Refuse to deliver old queued pressure changes without exact V2 receipt. */
    suspend fun verifiesChange(historyCarId: Int, change: TpmsPressureChange): Boolean {
        val row = dao.getAt(historyCarId, change.observedAt) ?: return false
        if (row.provenance != TPMS_VERIFIED_COLLECTOR) return false
        val received = when (change.wheel) {
            TirePosition.FL -> row.pressureFl
            TirePosition.FR -> row.pressureFr
            TirePosition.RL -> row.pressureRl
            TirePosition.RR -> row.pressureRr
        }
        return received?.takeIf { it.isFinite() } == change.after
    }

    suspend fun latestVerified(historyCarId: Int, now: Long): TpmsPressureSample? =
        dao.getInRange(historyCarId, now - THIRTY_DAYS_MS, now).filter { it.provenance == TPMS_VERIFIED_COLLECTOR }.lastOrNull()

    suspend fun load7DaySamples(carId: Int, now: Long = System.currentTimeMillis()): List<TpmsPressureSample> =
        dao.getInRange(vehicleContextRepository.requireLocalHistoryCarId(carId), now - SEVEN_DAYS_MS, now + 1).filter { it.provenance == TPMS_VERIFIED_COLLECTOR }

    suspend fun load30DaySamples(carId: Int, now: Long = System.currentTimeMillis()): List<TpmsPressureSample> =
        dao.getInRange(vehicleContextRepository.requireLocalHistoryCarId(carId), now - THIRTY_DAYS_MS, now + 1).filter { it.provenance == TPMS_VERIFIED_COLLECTOR }

    suspend fun pruneOlderThan90Days(carId: Int, now: Long = System.currentTimeMillis()): Int =
        dao.deleteOlderCollectorThan(vehicleContextRepository.requireLocalHistoryCarId(carId), now - NINETY_DAYS_MS)

    suspend fun pruneOlderThan90DaysForHistoryCarId(historyCarId: Int, now: Long): Int =
        dao.deleteOlderCollectorThan(historyCarId, now - NINETY_DAYS_MS)

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

/** Never rewrite a legacy-unverified receipt or clear another wheel at one timestamp. */
internal fun mergeTpmsSampleAtSameInstant(previous: TpmsPressureSample?, incoming: TpmsPressureSample): TpmsPressureSample {
    if (previous == null) return incoming
    if (previous.carId != incoming.carId || previous.observedAt != incoming.observedAt ||
        previous.provenance != incoming.provenance) return previous
    return previous.copy(
        pressureFl = previous.pressureFl ?: incoming.pressureFl,
        pressureFr = previous.pressureFr ?: incoming.pressureFr,
        pressureRl = previous.pressureRl ?: incoming.pressureRl,
        pressureRr = previous.pressureRr ?: incoming.pressureRr,
        outsideTempC = previous.outsideTempC ?: incoming.outsideTempC
    )
}

/** Only the first actual wheel reading at this exact collector instant is new.
 * A retry or same-instant conflicting value does NOT create another event.
 * Legacy unverified/poll-time rows remain untouched.
 */
internal fun novelTpmsSampleAtSameInstant(
    previous: TpmsPressureSample?, incoming: TpmsPressureSample
): TpmsPressureSample? {
    if (incoming.provenance != TPMS_VERIFIED_COLLECTOR) return null
    if (previous != null && (previous.carId != incoming.carId ||
        previous.observedAt != incoming.observedAt || previous.provenance != incoming.provenance)) return null
    val newRows = incoming.copy(
        pressureFl = incoming.pressureFl.takeIf { previous?.pressureFl == null },
        pressureFr = incoming.pressureFr.takeIf { previous?.pressureFr == null },
        pressureRl = incoming.pressureRl.takeIf { previous?.pressureRl == null },
        pressureRr = incoming.pressureRr.takeIf { previous?.pressureRr == null },
        outsideTempC = null
    )
    return newRows.takeIf {
        it.pressureFl != null || it.pressureFr != null ||
            it.pressureRl != null || it.pressureRr != null
    }
}
