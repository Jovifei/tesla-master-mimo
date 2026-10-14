package com.matelink.domain

import com.matelink.data.local.entity.DriveSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Synthetic records exercise the real aggregator; no persistence or provider evidence. */
class TripAggregatorEnergyTest {
    @Test
    fun allUnknownEnergyRemainsUnknown() {
        val trip = TripAggregator.buildTrip(listOf(drive(1, 10.0, null), drive(2, 10.0, null)), emptyList())!!
        assertNull(trip.avgEfficiency)
        assertNull(trip.totalEnergyConsumed)
        assertEquals(0, trip.energySampleCount)
        assertEquals(0, trip.efficiencySampleCount)
        assertEquals(0.0, trip.efficiencyRecordCoveragePercent!!, 0.0)
        assertEquals(0.0, trip.efficiencyDistanceCoveragePercent!!, 0.0)
    }

    @Test
    fun unknownLegDistanceDoesNotDiluteKnownEfficiency() {
        val trip = TripAggregator.buildTrip(listOf(drive(1, 10.0, 1.0), drive(2, 10.0, null)), emptyList())!!
        assertEquals(100.0, trip.avgEfficiency!!, 0.000001)
        assertEquals(1.0, trip.totalEnergyConsumed!!, 0.0)
        assertEquals(20.0, trip.totalDistance, 0.0)
        assertEquals(1, trip.energySampleCount)
    }

    @Test
    fun observedZeroRetainsItsDistanceWeight() {
        val trip = TripAggregator.buildTrip(listOf(drive(1, 10.0, 0.0), drive(2, 10.0, 2.0)), emptyList())!!
        assertEquals(100.0, trip.avgEfficiency!!, 0.000001)
        assertEquals(2.0, trip.totalEnergyConsumed!!, 0.0)
    }

    @Test
    fun allObservedZeroIsNotUnknown() {
        val trip = TripAggregator.buildTrip(listOf(drive(1, 10.0, 0.0)), emptyList())!!
        assertEquals(0.0, trip.avgEfficiency!!, 0.0)
        assertEquals(0.0, trip.totalEnergyConsumed!!, 0.0)
    }

    @Test
    fun netRecoveryRemainsNegative() {
        val trip = TripAggregator.buildTrip(listOf(drive(1, 10.0, -1.0)), emptyList())!!
        assertEquals(-100.0, trip.avgEfficiency!!, 0.0)
        assertEquals(-1.0, trip.totalEnergyConsumed!!, 0.0)
    }

    @Test
    fun unequalLegsUseDistanceWeightNotMeanOfRatios() {
        val trip = TripAggregator.buildTrip(listOf(drive(1, 1.0, 0.3), drive(2, 99.0, 14.85)), emptyList())!!
        assertEquals(151.5, trip.avgEfficiency!!, 0.000001)
    }

    @Test
    fun nonFiniteEnergyDoesNotPoisonKnownPairs() {
        val trip = TripAggregator.buildTrip(listOf(
            drive(1, 10.0, 1.0), drive(2, 10.0, Double.NaN),
            drive(3, 10.0, Double.POSITIVE_INFINITY), drive(4, 10.0, Double.NEGATIVE_INFINITY)
        ), emptyList())!!
        assertEquals(100.0, trip.avgEfficiency!!, 0.0)
        assertEquals(1.0, trip.totalEnergyConsumed!!, 0.0)
    }

    @Test
    fun invalidDistanceDoesNotContributeEnergyToEfficiency() {
        val trip = TripAggregator.buildTrip(listOf(
            drive(1, 10.0, 1.0), drive(2, 0.0, 9.0), drive(3, -10.0, 9.0),
            drive(4, Double.NaN, 9.0), drive(5, Double.POSITIVE_INFINITY, 9.0)
        ), emptyList())!!
        assertEquals(100.0, trip.avgEfficiency!!, 0.0)
        assertEquals(37.0, trip.totalEnergyConsumed!!, 0.0)
        assertEquals(5, trip.energySampleCount)
    }

    @Test
    fun noQualifiedDistanceRetainsKnownEnergyButLeavesEfficiencyUnknown() {
        val trip = TripAggregator.buildTrip(listOf(drive(1, 0.0, 1.0), drive(2, -10.0, 1.0)), emptyList())!!
        assertNull(trip.avgEfficiency)
        assertEquals(2.0, trip.totalEnergyConsumed!!, 0.0)
        assertEquals(2, trip.energySampleCount)
    }

    @Test
    fun trueZeroEnergyWithZeroDistanceIsKnownTotalButUnknownEfficiency() {
        val trip = TripAggregator.buildTrip(listOf(drive(1, 0.0, 0.0)), emptyList())!!
        assertEquals(0.0, trip.totalEnergyConsumed!!, 0.0)
        assertEquals(1, trip.energySampleCount)
        assertEquals(0, trip.efficiencySampleCount)
        assertNull(trip.avgEfficiency)
    }

    @Test
    fun trueZeroEnergyWithUnknownDistanceIsKnownTotal() {
        val trip = TripAggregator.buildTrip(listOf(drive(1, Double.NaN, 0.0)), emptyList())!!
        assertEquals(0.0, trip.totalEnergyConsumed!!, 0.0)
        assertEquals(1, trip.energySampleCount)
        assertNull(trip.avgEfficiency)
    }

    @Test
    fun knownEnergyWithUnknownDistanceIsRetainedWithoutInventingEfficiency() {
        val trip = TripAggregator.buildTrip(listOf(drive(1, Double.NaN, 3.0)), emptyList())!!
        assertEquals(3.0, trip.totalEnergyConsumed!!, 0.0)
        assertEquals(1, trip.energySampleCount)
        assertNull(trip.avgEfficiency)
    }

    @Test
    fun partiallyKnownEnergyTotalUsesEnergyCoverageRatherThanEfficiencyCoverage() {
        val trip = TripAggregator.buildTrip(listOf(
            drive(1, 10.0, 1.0), drive(2, Double.NaN, -0.5), drive(3, 10.0, null)
        ), emptyList())!!
        assertEquals(0.5, trip.totalEnergyConsumed!!, 0.0)
        assertEquals(2, trip.energySampleCount)
        assertEquals(1, trip.efficiencySampleCount)
        assertEquals(100.0, trip.avgEfficiency!!, 0.0)
    }

    @Test
    fun allNonFiniteEnergyIsUnknownEvenWithValidDistance() {
        val trip = TripAggregator.buildTrip(listOf(
            drive(1, 10.0, Double.NaN), drive(2, 10.0, Double.POSITIVE_INFINITY)
        ), emptyList())!!
        assertNull(trip.totalEnergyConsumed)
        assertNull(trip.avgEfficiency)
        assertEquals(0, trip.energySampleCount)
    }

    @Test
    fun energyOnlySumOverflowIsUnknownWhileQualifiedEfficiencyCanRemainKnown() {
        val trip = TripAggregator.buildTrip(listOf(
            drive(1, 10.0, 1.0), drive(2, Double.NaN, Double.MAX_VALUE),
            drive(3, 0.0, Double.MAX_VALUE)
        ), emptyList())!!
        assertNull(trip.totalEnergyConsumed)
        assertEquals(100.0, trip.avgEfficiency!!, 0.0)
        assertEquals(3, trip.energySampleCount)
        assertEquals(1, trip.efficiencySampleCount)
    }

    @Test
    fun overflowedEnergySumIsUnknown() {
        val trip = TripAggregator.buildTrip(listOf(
            drive(1, 10.0, Double.MAX_VALUE), drive(2, 10.0, Double.MAX_VALUE)
        ), emptyList())!!
        assertNull(trip.avgEfficiency)
        assertNull(trip.totalEnergyConsumed)
    }

    @Test
    fun overflowedDistanceSumDoesNotCreateZeroEfficiency() {
        val trip = TripAggregator.buildTrip(listOf(
            drive(1, Double.MAX_VALUE, 1.0), drive(2, Double.MAX_VALUE, 1.0)
        ), emptyList())!!
        assertNull(trip.avgEfficiency)
        assertEquals(2.0, trip.totalEnergyConsumed!!, 0.0)
        assertNull(trip.efficiencyDistanceCoveragePercent)
    }

    @Test
    fun nonFiniteCalculatedRatioRemainsUnknown() {
        val trip = TripAggregator.buildTrip(listOf(drive(1, Double.MIN_VALUE, 1.0)), emptyList())!!
        assertNull(trip.avgEfficiency)
        assertEquals(1.0, trip.totalEnergyConsumed!!, 0.0)
    }

    @Test
    fun emptyDriveSetStillHasNoTrip() {
        assertNull(TripAggregator.buildTrip(emptyList(), emptyList()))
    }

    @Test
    fun originalLegsAndIdentityMetadataArePreserved() {
        val drives = listOf(drive(1, 10.0, 1.0), drive(2, 10.0, null))
        val before = drives.toList()
        val trip = TripAggregator.buildTrip(drives, emptyList(), "Weekend")!!
        assertEquals(before, drives)
        assertEquals(before, trip.drives)
        assertEquals("Weekend", trip.name)
        assertEquals(drives.first().startDate, trip.startDate)
        assertEquals(drives.last().endDate, trip.endDate)
    }

    @Test
    fun recordCoverageAndDistanceCoverageDescribeDifferentDenominators() {
        val trip = TripAggregator.buildTrip(listOf(drive(1, 10.0, 1.0), drive(2, 90.0, null)), emptyList())!!
        assertEquals(100.0, trip.avgEfficiency!!, 0.0)
        assertEquals(1, trip.efficiencySampleCount)
        assertEquals(50.0, trip.efficiencyRecordCoveragePercent!!, 0.0)
        assertEquals(10.0, trip.efficiencyDistanceCoveragePercent!!, 0.0)
    }

    @Test
    fun completeCoverageIncludesZeroAndRecoveryRecords() {
        val trip = TripAggregator.buildTrip(listOf(drive(1, 10.0, 0.0), drive(2, 10.0, -1.0)), emptyList())!!
        assertEquals(2, trip.efficiencySampleCount)
        assertEquals(100.0, trip.efficiencyRecordCoveragePercent!!, 0.0)
        assertEquals(100.0, trip.efficiencyDistanceCoveragePercent!!, 0.0)
        assertEquals(-50.0, trip.avgEfficiency!!, 0.0)
    }

    @Test
    fun invalidDistanceMakesRecordCoveragePartialEvenWhenKnownDistanceCoverageIsFull() {
        val trip = TripAggregator.buildTrip(listOf(drive(1, 10.0, 1.0), drive(2, Double.NaN, 4.0)), emptyList())!!
        assertEquals(1, trip.efficiencySampleCount)
        assertEquals(50.0, trip.efficiencyRecordCoveragePercent!!, 0.0)
        assertEquals(100.0, trip.efficiencyDistanceCoveragePercent!!, 0.0)
    }

    @Test
    fun allInvalidDistanceHasUnknownDistanceCoverage() {
        val trip = TripAggregator.buildTrip(listOf(drive(1, Double.NaN, 1.0)), emptyList())!!
        assertNull(trip.efficiencyDistanceCoveragePercent)
        assertEquals(0, trip.efficiencySampleCount)
    }

    private fun drive(id: Int, distance: Double, energy: Double?) = DriveSummary(
        driveId = id, carId = 1,
        startDate = "2026-01-01T00:00:00Z", endDate = "2026-01-01T00:10:00Z", durationMin = 10,
        startAddress = "Start", endAddress = "End", distance = distance,
        speedMax = 50, speedAvg = 30, powerMax = 20, powerMin = -10,
        startBatteryLevel = 80, endBatteryLevel = 79, outsideTempAvg = null, insideTempAvg = null,
        energyConsumed = energy, efficiency = null,
        qualityState = "observed", qualityReason = "synthetic_test"
    )
}
