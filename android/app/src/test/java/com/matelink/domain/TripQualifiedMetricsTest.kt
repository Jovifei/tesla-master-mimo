package com.matelink.domain

import com.matelink.data.api.models.ChargeData
import com.matelink.data.api.models.DriveBatteryDetails
import com.matelink.data.api.models.DriveData
import com.matelink.data.api.models.DriveOdometerDetails
import com.matelink.data.sync.toSyncSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TripQualifiedMetricsTest {
    private fun drive(id: Int, distance: Double? = null, soc: Int? = null) = requireNotNull(
        DriveData(id, startDate = "2026-10-14T10:00:00Z", endDate = "2026-10-14T11:00:00Z",
            source = "teslamate_archive", qualityState = "observed",
            odometerDetails = DriveOdometerDetails(distance = distance),
            batteryDetails = DriveBatteryDetails(startBatteryLevel = soc, endBatteryLevel = soc))
            .toSyncSummary(-701))

    @Test fun missingReceiptFieldsNeverAcquirePrimitiveZeroMeasurements() {
        val charge = requireNotNull(ChargeData(9, startDate = "2026-10-14T11:00:00Z",
            endDate = "2026-10-14T11:30:00Z", source = "teslamate_archive", qualityState = "observed")
            .toSyncSummary(-701))
        val trip = requireNotNull(TripAggregator.buildQualifiedTrip(listOf(drive(1)), listOf(charge)))
        assertNull(trip.knownDistanceKm)
        assertEquals(0, trip.distanceSampleCount)
        assertNull(trip.knownChargeEnergyKwh)
        assertEquals(0, trip.chargeEnergySampleCount)
        assertNull(trip.observedStartBatteryLevel)
        assertNull(trip.observedEndBatteryLevel)
        assertNull(trip.observedMaxSpeedKmh)
    }

    @Test fun qualifiedZeroIsPreservedIndependentlyFromMissingFields() {
        val charge = requireNotNull(ChargeData(9, startDate = "2026-10-14T11:00:00Z",
            endDate = "2026-10-14T11:30:00Z", chargeEnergyAdded = 0.0,
            source = "teslamate_archive", qualityState = "observed").toSyncSummary(-701))
        val trip = requireNotNull(TripAggregator.buildQualifiedTrip(listOf(drive(1, 0.0, 0)), listOf(charge)))
        assertEquals(0.0, trip.knownDistanceKm!!, 0.0)
        assertEquals(1, trip.distanceSampleCount)
        assertEquals(0.0, trip.knownChargeEnergyKwh!!, 0.0)
        assertEquals(1, trip.chargeEnergySampleCount)
        assertEquals(0, trip.observedStartBatteryLevel)
    }

    @Test fun knownDistanceHasExplicitCoverageAndUnprovenLegacyEnergyIsExcluded() {
        val missing = drive(2).copy(energyConsumed = 99.0, energySource = "legacy_unproven")
        val trip = requireNotNull(TripAggregator.buildQualifiedTrip(listOf(drive(1, 10.0), missing), emptyList()))
        assertEquals(10.0, trip.knownDistanceKm!!, 0.0)
        assertEquals(1, trip.distanceSampleCount)
        assertNull(trip.totalEnergyConsumed)
        assertEquals(0, trip.energySampleCount)
    }
}
