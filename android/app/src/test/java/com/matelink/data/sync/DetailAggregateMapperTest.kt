package com.matelink.data.sync

import com.matelink.data.api.models.ChargeDetail
import com.matelink.data.api.models.ChargePoint
import com.matelink.data.api.models.ChargerDetails
import com.matelink.data.api.models.DriveClimateInfo
import com.matelink.data.api.models.DriveDetail
import com.matelink.data.api.models.DrivePosition
import com.matelink.data.local.entity.SchemaVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DetailAggregateMapperTest {

    @Test
    fun driveDetail_persistsComputedExtremesAndCoordinates() {
        val aggregate = DriveDetail(
            driveId = 17,
            positions = listOf(
                DrivePosition(
                    latitude = 30.1,
                    longitude = 120.1,
                    elevation = 10,
                    power = 4,
                    climateInfo = DriveClimateInfo(insideTemp = 22.0, outsideTemp = 18.0, isClimateOn = true)
                ),
                DrivePosition(
                    latitude = 30.2,
                    longitude = 120.2,
                    elevation = 18,
                    power = -8,
                    climateInfo = DriveClimateInfo(insideTemp = 28.0, outsideTemp = 12.0, isClimateOn = false)
                ),
                DrivePosition(
                    latitude = 30.3,
                    longitude = 120.3,
                    elevation = 15,
                    power = 12,
                    climateInfo = DriveClimateInfo(insideTemp = 25.0, outsideTemp = 20.0, isClimateOn = true)
                )
            )
        ).toAggregate(carId = 4, computedAt = 123L)

        assertEquals(17, aggregate.driveId)
        assertEquals(4, aggregate.carId)
        assertEquals(SchemaVersion.CURRENT, aggregate.schemaVersion)
        assertEquals(18, aggregate.maxElevation)
        assertEquals(10, aggregate.minElevation)
        assertEquals(8, aggregate.elevationGain)
        assertEquals(3, aggregate.elevationLoss)
        assertEquals(12, aggregate.maxPower)
        assertEquals(-8, aggregate.minPower)
        assertEquals(2, aggregate.climateOnPositions)
        assertEquals(30.1, aggregate.startLatitude!!, 0.0001)
        assertEquals(120.3, aggregate.endLongitude!!, 0.0001)
    }

    @Test
    fun chargeDetail_marksKnownDcSessionAndCapturesChargerExtremes() {
        val aggregate = ChargeDetail(
            chargeId = 23,
            chargePoints = listOf(
                ChargePoint(
                    outsideTemp = 16.0,
                    chargerDetails = ChargerDetails(
                        chargerPower = 90.0,
                        chargerVoltage = 400.0,
                        chargerActualCurrent = 220.0,
                        chargerPhases = 0,
                        fastChargerPresent = true,
                        fastChargerBrand = "Tesla",
                        fastChargerType = "CCS"
                    )
                ),
                ChargePoint(
                    outsideTemp = 23.0,
                    chargerDetails = ChargerDetails(chargerPower = 120.0, chargerVoltage = 410.0, chargerActualCurrent = 240.0)
                )
            )
        ).toAggregate(carId = 4, computedAt = 123L)

        assertTrue(aggregate.isFastCharger)
        assertEquals("Tesla", aggregate.fastChargerBrand)
        assertEquals("CCS", aggregate.connectorType)
        assertEquals(120, aggregate.maxChargerPower)
        assertEquals(410, aggregate.maxChargerVoltage)
        assertEquals(240, aggregate.maxChargerCurrent)
        assertEquals(23.0, aggregate.maxOutsideTemp!!, 0.0001)
        assertEquals(16.0, aggregate.minOutsideTemp!!, 0.0001)
    }

    @Test
    fun detailWithoutSamples_keepsUnknownMeasurementsInsteadOfFakeZeros() {
        val drive = DriveDetail(driveId = 2).toAggregate(carId = 4, computedAt = 123L)
        val charge = ChargeDetail(chargeId = 3).toAggregate(carId = 4, computedAt = 123L)

        assertFalse(drive.hasElevationData)
        assertEquals(null, drive.maxPower)
        assertEquals(null, charge.maxChargerPower)
        assertFalse(charge.isFastCharger)
    }
}
