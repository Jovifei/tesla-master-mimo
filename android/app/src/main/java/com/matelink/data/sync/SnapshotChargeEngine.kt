package com.matelink.data.sync

import android.content.Context
import android.content.SharedPreferences
import com.matelink.data.api.models.CarStatus
import com.matelink.data.local.dao.AggregateDao
import com.matelink.data.local.dao.ChargeSummaryDao
import com.matelink.data.local.dao.DriveSummaryDao
import com.matelink.data.local.entity.ChargeDetailAggregate
import com.matelink.data.local.entity.ChargeSummary
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

@Singleton
class SnapshotChargeEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val chargeSummaryDao: ChargeSummaryDao,
    private val driveSummaryDao: DriveSummaryDao,
    private val aggregateDao: AggregateDao
) {
    private val prefs: SharedPreferences = context.getSharedPreferences(
        "snapshot_charge_tracker",
        Context.MODE_PRIVATE
    )

    suspend fun recordSnapshot(historyCarId: Int, status: CarStatus) {
        ensureBackfillToday(historyCarId)

        val currentSoc = status.batteryLevel ?: return
        val isChargingNow = status.isCharging || status.chargingState.equals("charging", ignoreCase = true)
        val now = Instant.now()
        val nowIso = DateTimeFormatter.ISO_INSTANT.format(now)

        val activeKey = "charge_active_$historyCarId"
        val startSocKey = "charge_start_soc_$historyCarId"
        val startTimeKey = "charge_start_time_$historyCarId"
        val startOdoKey = "charge_start_odo_$historyCarId"

        val wasActive = prefs.getBoolean(activeKey, false)
        val startSoc = prefs.getInt(startSocKey, -1)
        val startTimeIso = prefs.getString(startTimeKey, null)
        val startOdo = prefs.getFloat(startOdoKey, -1f).toDouble()

        if (isChargingNow) {
            if (!wasActive || startSoc < 0 || startTimeIso == null) {
                // Charge session began
                prefs.edit()
                    .putBoolean(activeKey, true)
                    .putInt(startSocKey, currentSoc)
                    .putString(startTimeKey, nowIso)
                    .putFloat(startOdoKey, (status.odometer ?: 0.0).toFloat())
                    .apply()
            }
        } else {
            if (wasActive && startSoc >= 0 && startTimeIso != null) {
                // Charge session ended
                val deltaSoc = (currentSoc - startSoc).coerceAtLeast(0)
                if (deltaSoc >= 1) {
                    val startTime = try { Instant.parse(startTimeIso) } catch (_: Exception) { now.minus(30, ChronoUnit.MINUTES) }
                    val durationMinutes = max(1, ChronoUnit.MINUTES.between(startTime, now).toInt())
                    val syntheticChargeId = ((now.epochSecond % 1_000_000_000) + (Math.random() * 1000).toInt()).toInt()
                    val addedEnergy = status.chargeEnergyAdded ?: ((deltaSoc / 100.0) * 60.0)

                    val charge = ChargeSummary(
                        chargeId = syntheticChargeId,
                        carId = historyCarId,
                        startDate = DateTimeFormatter.ISO_INSTANT.format(startTime),
                        endDate = nowIso,
                        durationMin = durationMinutes,
                        address = status.geofence ?: "充电站",
                        latitude = status.latitude ?: 0.0,
                        longitude = status.longitude ?: 0.0,
                        energyAdded = ((addedEnergy * 10.0).roundToInt() / 10.0),
                        energyUsed = null,
                        cost = null,
                        startBatteryLevel = startSoc,
                        endBatteryLevel = currentSoc,
                        outsideTempAvg = status.outsideTemp?.toDouble(),
                        odometer = if (startOdo > 0) startOdo else (status.odometer ?: 0.0),
                        apiEvidence = "snapshot_charge"
                    )
                    chargeSummaryDao.upsert(charge)
                }

                prefs.edit()
                    .putBoolean(activeKey, false)
                    .putInt(startSocKey, -1)
                    .putString(startTimeKey, null)
                    .apply()
            }
        }
    }

    suspend fun reconstructChargesFromDrives(historyCarId: Int) {
        // Ensure legacy copies across candidate carIds
        val candidateIds = listOf(-1, 1, 2)
        for (legacyId in candidateIds) {
            if (legacyId != historyCarId) {
                try {
                    driveSummaryDao.copyFromLegacy(legacyId, historyCarId)
                    chargeSummaryDao.copyFromLegacy(legacyId, historyCarId)
                } catch (_: Exception) {
                }
            }
        }

        var drives = driveSummaryDao.getAllChronological(historyCarId)
        if (drives.size < 2 && historyCarId != -1) {
            drives = driveSummaryDao.getAllChronological(-1)
        }
        val validDrives = drives.filter { (it.startBatteryLevel ?: 0) > 0 }
        if (validDrives.size < 2) return

        for (i in 0 until validDrives.size - 1) {
            val prev = validDrives[i]
            val next = validDrives[i + 1]

            val prevEndSoc = prev.endBatteryLevel
            val nextStartSoc = next.startBatteryLevel

            if (prevEndSoc != null && nextStartSoc != null && (nextStartSoc - prevEndSoc) >= 3) {
                val deltaSoc = nextStartSoc - prevEndSoc
                val startInstant = try { Instant.parse(prev.endDate) } catch (_: Exception) { null }
                val endInstant = try { Instant.parse(next.startDate) } catch (_: Exception) { null }

                val durationMinutes = if (startInstant != null && endInstant != null) {
                    max(1, ChronoUnit.MINUTES.between(startInstant, endInstant).toInt())
                } else {
                    30
                }

                // Standard 60.0 kWh nominal usable capacity
                val energyAdded = ((deltaSoc / 100.0) * 60.0 * 10.0).roundToInt() / 10.0
                val durationHours = max(0.016, durationMinutes / 60.0)
                val avgPower = energyAdded / durationHours

                val isFastCharge = avgPower >= 25.0 || (durationMinutes <= 60 && deltaSoc >= 30)
                val defaultRate = if (isFastCharge) 1.50 else 1.10
                val estimatedCost = ((energyAdded * defaultRate) * 100.0).roundToInt() / 100.0
                val defaultAddress = prev.endAddress?.takeIf { it.isNotBlank() }
                    ?: next.startAddress?.takeIf { it.isNotBlank() }
                    ?: (if (isFastCharge) "超级充电站" else "充电站")

                // Deterministic ID based on start time hash
                val rawHash = abs((prev.endDate + next.startDate).hashCode())
                val syntheticChargeId = 200000 + (rawHash % 700000)

                for (targetCarId in setOf(historyCarId, -1, 1)) {
                    val existing = chargeSummaryDao.get(targetCarId, syntheticChargeId)
                    val costToUse = existing?.cost ?: estimatedCost
                    val addressToUse = existing?.address?.takeIf { it.isNotBlank() } ?: defaultAddress

                    val charge = ChargeSummary(
                        chargeId = syntheticChargeId,
                        carId = targetCarId,
                        startDate = prev.endDate,
                        endDate = next.startDate,
                        durationMin = durationMinutes,
                        address = addressToUse,
                        latitude = 0.0,
                        longitude = 0.0,
                        energyAdded = energyAdded,
                        energyUsed = null,
                        cost = costToUse,
                        startBatteryLevel = prevEndSoc,
                        endBatteryLevel = nextStartSoc,
                        outsideTempAvg = prev.outsideTempAvg,
                        odometer = 0.0,
                        apiEvidence = "inferred_soc_jump"
                    )
                    chargeSummaryDao.upsert(charge)

                    try {
                        val aggregate = ChargeDetailAggregate(
                            chargeId = syntheticChargeId,
                            carId = targetCarId,
                            schemaVersion = 1,
                            computedAt = System.currentTimeMillis(),
                            isFastCharger = isFastCharge,
                            fastChargerBrand = if (isFastCharge) "Tesla" else null,
                            connectorType = if (isFastCharge) "CCS" else "Type 2",
                            maxChargerPower = (avgPower * 1.5).roundToInt().coerceAtLeast(7),
                            maxChargerVoltage = if (isFastCharge) 400 else 220,
                            maxChargerCurrent = if (isFastCharge) 150 else 32,
                            chargerPhases = if (isFastCharge) null else 1,
                            maxOutsideTemp = prev.outsideTempAvg,
                            minOutsideTemp = prev.outsideTempAvg,
                            chargePointCount = 20
                        )
                        aggregateDao.upsertChargeAggregate(aggregate)
                    } catch (_: Exception) {
                    }
                }
            }
        }
    }

    suspend fun ensureBackfillToday(historyCarId: Int) {
        // Purge legacy synthetic backfill and invalid zero-SoC delta sessions if present
        try {
            chargeSummaryDao.deleteChargeById(1001)
            chargeSummaryDao.deleteChargeById(102)
            chargeSummaryDao.deleteChargeById(103)
        } catch (_: Exception) {
        }
        reconstructChargesFromDrives(historyCarId)
        val charges = chargeSummaryDao.getAllForCar(historyCarId)
        for (charge in charges) {
            if (charge.startBatteryLevel == 0 && charge.endBatteryLevel == 0) {
                chargeSummaryDao.deleteChargeById(charge.chargeId)
                continue
            }
            if (charge.cost == null && charge.energyAdded > 0.0) {
                val isFast = charge.energyAdded > 20.0
                val rate = if (isFast) 1.50 else 1.10
                val estimated = (charge.energyAdded * rate * 100.0).roundToInt() / 100.0
                chargeSummaryDao.upsert(charge.copy(cost = estimated))
            }
        }
    }
}
