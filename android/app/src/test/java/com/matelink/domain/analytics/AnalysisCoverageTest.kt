package com.matelink.domain.analytics

import com.matelink.data.api.models.DrivesResponse
import com.matelink.data.api.models.receivedDrives
import com.matelink.data.sync.toSyncSummary
import com.squareup.moshi.Moshi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AnalysisCoverageTest {
    @Test
    fun coverageCountsOnlyFiniteUsableInputsAndKeepsZeroCostObserved() {
        val coverage = buildAnalysisCoverage(
            drives = listOf(
                AnalysisDriveCoverageSample(100.0, 20.0, "2026-01-01T10:00:00Z"),
                AnalysisDriveCoverageSample(0.0, null, "2026-01-02T10:00:00Z"),
                AnalysisDriveCoverageSample(Double.NaN, Double.POSITIVE_INFINITY, "2026-01-03T10:00:00Z")
            ),
            charges = listOf(
                AnalysisChargeCoverageSample(20.0, 0.0, "2026-01-04"),
                AnalysisChargeCoverageSample(null, -1.0, "2026-01-05")
            )
        )

        assertEquals(3, coverage.driveRecordCount)
        assertEquals(2, coverage.driveDistanceSampleCount)
        assertEquals(1, coverage.driveEnergySampleCount)
        assertEquals(2, coverage.chargeRecordCount)
        assertEquals(1, coverage.chargeEnergySampleCount)
        assertEquals(1, coverage.chargeCostSampleCount)
        assertEquals(66.6667, coverage.distanceCoveragePercent!!, 0.0001)
        assertEquals(50.0, coverage.costCoveragePercent!!, 0.0001)
        assertEquals(4L, coverage.observationDays)
    }

    @Test
    fun validZeroAndNetRecoveryCountButOnlyPairedAcModesSupportLoss() {
        val c = buildAnalysisCoverage(
            drives = listOf(
                AnalysisDriveCoverageSample(0.0, 0.0, "2026-02-01T00:00:00Z"),
                AnalysisDriveCoverageSample(5.0, -0.5, "2026-02-02T00:00:00Z"),
                AnalysisDriveCoverageSample(5.0, null, "2026-02-03T00:00:00Z")
            ),
            charges = listOf(
                AnalysisChargeCoverageSample(0.0, 0.0, "2026-02-01T00:00:00Z",
                    energyUsedKwh = 1.0),
                AnalysisChargeCoverageSample(9.0, null, "2026-02-02T00:00:00Z",
                    energyUsedKwh = 10.0, acBalanceQualified = true)
            )
        )
        assertEquals(2, c.driveEnergySampleCount)
        assertEquals(2, c.chargeEnergySampleCount)
        assertEquals(1, c.chargeLossSampleCount)
        assertEquals(10.0, c.chargeEnergyUsedForLossKwh!!, 0.0001)
        assertEquals(9.0, c.chargeEnergyAddedForLossKwh!!, 0.0001)
        assertEquals(1, c.pairedEfficiency!!.sampleCount)
        assertEquals(-0.5, c.pairedEfficiency.validEnergyKwh, 0.0001)
        assertEquals(5.0, c.pairedEfficiency.validDistanceKm, 0.0001)
        assertEquals(33.3333, c.efficiencyCoveragePercent!!, 0.0001)
        assertEquals(1, c.pairedEstimatedEnergySampleCount)
    }

    @Test
    fun noRecordsProduceUnknownCoverageInsteadOfZero() {
        val coverage = buildAnalysisCoverage(emptyList(), emptyList())

        assertNull(coverage.distanceCoveragePercent)
        assertNull(coverage.driveEnergyCoveragePercent)
        assertNull(coverage.chargeEnergyCoveragePercent)
        assertNull(coverage.costCoveragePercent)
        assertNull(coverage.observationDays)
    }

    @Test
    fun localDateTimeAndInvalidDatesAreHandledWithoutChangingCounts() {
        val coverage = buildAnalysisCoverage(
            drives = listOf(
                AnalysisDriveCoverageSample(10.0, 2.0, "2026-02-01T12:00:00"),
                AnalysisDriveCoverageSample(10.0, 2.0, "not-a-date")
            ),
            charges = emptyList()
        )

        assertEquals("2026-02-01", coverage.firstObservedDate.toString())
        assertEquals("2026-02-01", coverage.lastObservedDate.toString())
        assertEquals(2, coverage.driveDistanceSampleCount)
    }

    @Test
    fun actualReceiptThroughRoomProjectionKeepsEnergyAndDistancePairsTogether() {
        val drives = Moshi.Builder().build().adapter(DrivesResponse::class.java).fromJson("""{
            "data":{"units":{"unit_of_length":"km"},"drives":[
                {"drive_id":1,"source":"teslamate_archive","quality_state":"observed",
                 "start_date":"2026-10-20T10:00:00Z","end_date":"2026-10-20T10:30:00Z",
                 "odometer_details":{"odometer_distance":10.0},"energy_consumed_net":2.0},
                {"drive_id":2,"source":"teslamate_archive","quality_state":"observed",
                 "start_date":"2026-10-20T11:00:00Z","end_date":"2026-10-20T11:30:00Z",
                 "odometer_details":{"odometer_distance":5.0}},
                {"drive_id":3,"source":"teslamate_archive","quality_state":"observed",
                 "start_date":"2026-10-20T12:00:00Z","end_date":"2026-10-20T12:30:00Z",
                 "odometer_details":{"odometer_distance":0.0},"energy_consumed_net":-0.5}
            ]}}
        """)!!.data!!.receivedDrives()
        val stored = drives.map { it.toSyncSummary(9)!! }
        val projected = stored.map { it.toAnalysisDriveData() }
        val coverage = buildAnalysisCoverage(projected.map {
            AnalysisDriveCoverageSample(it.distanceKm, it.netEnergyKwh, it.startDate,
                it.energyContract?.netEnergy?.quality)
        }, emptyList())

        assertEquals(3, coverage.driveRecordCount)
        assertEquals(15.0, coverage.qualifiedDistanceTotalKm!!, 0.0001)
        assertEquals(1.5, coverage.qualifiedDriveEnergyTotalKwh!!, 0.0001)
        assertEquals(1, coverage.pairedEfficiency!!.sampleCount)
        assertEquals(200.0, coverage.pairedEfficiency.efficiencyWhKm!!, 0.0001)
        assertEquals(10.0, coverage.pairedEfficiency.validDistanceKm, 0.0001)
    }
}
