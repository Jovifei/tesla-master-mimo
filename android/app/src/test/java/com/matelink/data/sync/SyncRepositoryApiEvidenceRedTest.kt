package com.matelink.data.sync

import com.matelink.data.api.models.*
import com.matelink.data.local.dao.persistDriveRowsWithoutEvidenceLoss
import com.matelink.data.local.entity.DriveSummary
import com.matelink.domain.analytics.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Tests the actual sync serializer and same-ID Room transaction algorithm,
 * not a brittle source-string check. All values are synthetic, no real owner data.
 */
class SyncRepositoryApiEvidenceRedTest {
    private val start = "2026-10-08T01:00:00Z"
    private val end = "2026-10-08T01:10:00Z"

    private fun fleet(id: Int = 7) = DriveData(
        driveId = id, startDate = start, endDate = end,
        energyConsumedNet = 8.0, consumptionNet = 800.0,
        source = "telemetry_mqtt", qualityState = "observed",
        odometerDetails = DriveOdometerDetails(distance = 10.0))

    @Test fun normalSummaryWritesPersistExactApiEvidenceBeforeRoomUpsert() = runBlocking {
        val raw = fleet()
        val first = raw.toSyncSummary(21)!!
        val initialJson = requireNotNull(first.apiEvidence)
        assertEquals(8.0, HistorySummaryEvidenceCodec.decodeDrive(initialJson)!!.energyConsumedNet!!, 0.0)
        assertNull(first.energyConsumed)
        assertNull(first.toAnalysisDriveData().netEnergyKwh)
        val rows = mutableMapOf((21 to 7) to first)
        val weak = raw.copy(energyConsumedNet = null, consumptionNet = null,
            source = "local_import", qualityState = "incomplete").toSyncSummary(21)!!
        suspend fun save(input: List<DriveSummary>) = persistDriveRowsWithoutEvidenceLoss(
            input, { car, id -> rows[car to id] },
            { record -> rows[record.carId to record.driveId] = record })
        save(listOf(weak))
        save(listOf(weak))
        val saved = requireNotNull(rows[21 to 7])
        assertEquals(1, rows.size)
        assertEquals(initialJson, saved.apiEvidence)
        assertEquals(8.0, saved.toRawAnalysisDriveData().energyConsumedNet!!, 0.0)
        assertNull(saved.energyConsumed)
        assertNull(saved.toAnalysisDriveData().netEnergyKwh)
        assertEquals("telemetry_mqtt", saved.toRawAnalysisDriveData().source)
        assertEquals("observed", saved.qualityState)
    }

    @Test fun independentlyCertifiedSignedAndZeroRemainValuesWithoutCarIdCollision() = runBlocking {
        fun measured(value: Double) = fleet().copy(
            energyConsumedNet = value,
            energyContract = EnergyContract(netEnergy = EnergyMetric(
                source = "telemetry_mqtt", valueKwh = value,
                method = "drive_power_integral", measurementPoint = "drive_power",
                quality = "estimated", startDate = start, endDate = end,
                coverageKind = "time", coverageRatio = 1.0,
                coverageSeconds = 600.0, timeBasis = "collector_received_at")))
        val rows = mutableMapOf<Pair<Int, Int>, DriveSummary>()
        persistDriveRowsWithoutEvidenceLoss(
            listOf(measured(0.0).toSyncSummary(21)!!,
                   measured(-0.25).toSyncSummary(22)!!),
            { car, id -> rows[car to id] },
            { row -> rows[row.carId to row.driveId] = row })
        assertEquals(2, rows.size)
        assertEquals(0.0, rows[21 to 7]!!.toAnalysisDriveData().netEnergyKwh!!, 0.0)
        assertEquals(-0.25, rows[22 to 7]!!.toAnalysisDriveData().netEnergyKwh!!, 0.0)
        assertEquals(-0.25, rows[22 to 7]!!.toRawAnalysisDriveData().energyConsumedNet!!, 0.0)
    }
}
