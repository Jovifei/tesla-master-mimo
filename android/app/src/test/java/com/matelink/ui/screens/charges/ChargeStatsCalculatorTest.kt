package com.matelink.ui.screens.charges

import com.matelink.data.api.models.ChargeBatteryDetails
import com.matelink.data.api.models.ChargeDetail
import com.matelink.data.api.models.ChargePoint
import com.matelink.data.api.models.ChargerDetails
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChargeStatsCalculatorTest {
    private val start = "2026-10-20T10:00:00Z"
    private val end = "2026-10-20T10:30:00Z"

    @Test
    fun missingDetailSignalsRemainUnavailable() {
        val stats = ChargeStatsCalculator.calculateStats(ChargeDetail(chargeId = 1))

        assertNull(stats.powerMax)
        assertNull(stats.voltageMax)
        assertNull(stats.currentMax)
        assertNull(stats.tempMax)
        assertNull(stats.batteryStart)
        assertNull(stats.batteryEnd)
        assertNull(stats.energyAdded)
        assertNull(stats.energyUsed)
        assertNull(stats.efficiency)
        assertNull(stats.durationMin)
    }

    @Test
    fun observedZeroSignalsRemainAvailable() {
        val stats = ChargeStatsCalculator.calculateStats(
            ChargeDetail(
                chargeId = 1,
                chargeEnergyAdded = 0.0,
                chargeEnergyUsed = 0.0,
                durationMin = 0,
                startDate = start,
                endDate = end,
                batteryDetails = ChargeBatteryDetails(startBatteryLevel = 0, endBatteryLevel = 0),
                chargePoints = listOf(
                    ChargePoint(
                        date = start,
                        batteryLevel = 0,
                        outsideTemp = 0.0,
                        chargerDetails = ChargerDetails(
                            chargerPower = 0.0,
                            chargerVoltage = 0.0,
                            chargerActualCurrent = 0.0
                        )
                    ),
                    ChargePoint(date = end, batteryLevel = 0)
                )
            )
        )

        assertEquals(0, stats.powerMax)
        assertEquals(0, stats.voltageMax)
        assertEquals(0, stats.currentMax)
        assertEquals(0.0, stats.tempMax!!, 0.0001)
        assertEquals(0, stats.batteryStart)
        assertEquals(0, stats.batteryEnd)
        assertEquals(0.0, stats.energyAdded!!, 0.0001)
        assertEquals(0.0, stats.energyUsed!!, 0.0001)
        assertNull(stats.efficiency)
        assertEquals(0, stats.durationMin)
    }

    @Test fun summaryAverageDoesNotInventTemperatureExtrema() {
        val stats = ChargeStatsCalculator.calculateStats(ChargeDetail(
            chargeId = 1, startDate = start, endDate = end, outsideTempAvg = 20.0
        ))
        assertNull(stats.tempMax)
        assertNull(stats.tempMin)
        assertEquals(20.0, stats.tempAvg!!, 0.0)
    }

    @Test fun outOfOrderPointsAreSortedBeforeChoosingObservedSoc() {
        val stats = ChargeStatsCalculator.calculateStats(ChargeDetail(
            chargeId = 1, startDate = start, endDate = end,
            batteryDetails = ChargeBatteryDetails(50, 90),
            chargePoints = listOf(
                ChargePoint(date = end, batteryLevel = 80),
                ChargePoint(date = "2026-10-20T10:15:00Z", batteryLevel = 75),
                ChargePoint(date = start, batteryLevel = 60)
            )
        ))
        assertEquals(60, stats.batteryStart)
        assertEquals(80, stats.batteryEnd)
        assertEquals(20, stats.batteryAdded)
    }

    @Test fun sameInstantConflictingSocCannotProveBoundary() {
        val stats = ChargeStatsCalculator.calculateStats(ChargeDetail(
            chargeId = 1, startDate = start, endDate = end,
            chargePoints = listOf(
                ChargePoint(date = start, batteryLevel = 60),
                ChargePoint(date = "2026-10-20T18:00:00+08:00", batteryLevel = 70),
                ChargePoint(date = end, batteryLevel = 80)
            )
        ))
        assertNull(stats.batteryStart)
        assertEquals(80, stats.batteryEnd)
        assertNull(stats.batteryAdded)
    }

    @Test fun interiorSocSamplesRemainFirstAndLastObservedValues() {
        val stats = ChargeStatsCalculator.calculateStats(ChargeDetail(
            chargeId = 1, startDate = start, endDate = end,
            batteryDetails = ChargeBatteryDetails(60, 80),
            chargePoints = listOf(
                ChargePoint(date = "2026-10-20T10:01:00Z", batteryLevel = 61),
                ChargePoint(date = "2026-10-20T10:29:00Z", batteryLevel = 79)
            )
        ))
        assertEquals(61, stats.batteryStart)
        assertEquals(79, stats.batteryEnd)
        assertEquals(18, stats.batteryAdded)
    }

    @Test fun identicalSameTimeValuesCountOnceAndKeepRealZero() {
        val stats = ChargeStatsCalculator.calculateStats(ChargeDetail(
            chargeId = 1, startDate = start, endDate = end,
            chargePoints = listOf(
                ChargePoint(date = start, outsideTemp = 0.0),
                ChargePoint(date = "2026-10-20T18:00:00+08:00", outsideTemp = 0.0),
                ChargePoint(date = end, outsideTemp = 10.0)
            )
        ))
        assertEquals(0.0, stats.tempMin!!, 0.0)
        assertEquals(10.0, stats.tempMax!!, 0.0)
        assertEquals(5.0, stats.tempAvg!!, 0.0)
    }

    @Test fun singleExactZeroDurationObservationCanBeBothBoundaries() {
        val stats = ChargeStatsCalculator.calculateStats(ChargeDetail(
            chargeId = 1, startDate = start, endDate = start, durationMin = 0,
            chargePoints = listOf(ChargePoint(date = start, batteryLevel = 0))
        ))
        assertEquals(0, stats.batteryStart)
        assertEquals(0, stats.batteryEnd)
        assertEquals(0, stats.batteryAdded)
    }

    @Test fun conflictingSameTimeAndOutsideWindowSamplesDoNotCreateExtrema() {
        val stats = ChargeStatsCalculator.calculateStats(ChargeDetail(
            chargeId = 1, startDate = start, endDate = end, outsideTempAvg = 25.0,
            chargePoints = listOf(
                ChargePoint(date = "2026-10-20T09:59:00Z", outsideTemp = 99.0,
                    chargerDetails = ChargerDetails(chargerPower = 999.0)),
                ChargePoint(date = "2026-10-20T10:10:00Z", outsideTemp = 20.0,
                    chargerDetails = ChargerDetails(chargerPower = 2.0)),
                ChargePoint(date = "2026-10-20T10:10:00Z", outsideTemp = 40.0,
                    chargerDetails = ChargerDetails(chargerPower = 3.0))
            )
        ))
        assertNull(stats.tempMax)
        assertNull(stats.tempMin)
        assertNull(stats.tempAvg)
        assertNull(stats.powerMax)
        assertNull(stats.powerMin)
        assertNull(stats.powerAvg)
    }

    @Test fun inWindowNegativeAndZeroTemperatureSamplesSurviveOutlierFiltering() {
        val stats = ChargeStatsCalculator.calculateStats(ChargeDetail(
            chargeId = 1, startDate = start, endDate = end,
            chargePoints = listOf(
                ChargePoint(date = "2026-10-20T09:59:00Z", outsideTemp = 99.0,
                    chargerDetails = ChargerDetails(chargerPower = 999.0)),
                ChargePoint(date = start, outsideTemp = -5.0,
                    chargerDetails = ChargerDetails(chargerPower = 2.0)),
                ChargePoint(date = end, outsideTemp = 0.0,
                    chargerDetails = ChargerDetails(chargerPower = 4.0))
            )
        ))
        assertEquals(-5.0, stats.tempMin!!, 0.0)
        assertEquals(0.0, stats.tempMax!!, 0.0)
        assertEquals(-2.5, stats.tempAvg!!, 0.0)
        assertEquals(2, stats.powerMin)
        assertEquals(4, stats.powerMax)
        assertEquals(3.0, stats.powerAvg!!, 0.0)
    }

    @Test fun chartsAndStatsUseTheSameSortedPerSignalEvidence() {
        val middle = "2026-10-20T10:15:00Z"
        val detail = ChargeDetail(chargeId = 1, startDate = start, endDate = end,
            chargePoints = listOf(
                ChargePoint(date = end, batteryLevel = 20, outsideTemp = 0.0,
                    chargerDetails = ChargerDetails(chargerPower = 3.0,
                        chargerVoltage = 200.0, chargerActualCurrent = 0.0)),
                ChargePoint(date = "2026-10-20T09:59:00Z", batteryLevel = 99, outsideTemp = 99.0,
                    chargerDetails = ChargerDetails(chargerPower = 999.0)),
                ChargePoint(date = middle, batteryLevel = 10, outsideTemp = 1.0,
                    chargerDetails = ChargerDetails(chargerPower = 5.0,
                        chargerVoltage = 100.0, chargerActualCurrent = 1.0)),
                ChargePoint(date = start, batteryLevel = 0, outsideTemp = -5.0,
                    chargerDetails = ChargerDetails(chargerPower = 0.0,
                        chargerVoltage = 0.0, chargerActualCurrent = 0.0)),
                ChargePoint(date = "2026-10-20T18:15:00+08:00", batteryLevel = 10, outsideTemp = 1.0,
                    chargerDetails = ChargerDetails(chargerPower = 7.0,
                        chargerVoltage = 100.0, chargerActualCurrent = 2.0)),
                ChargePoint(date = start, batteryLevel = 0, outsideTemp = -5.0,
                    chargerDetails = ChargerDetails(chargerPower = 0.0,
                        chargerVoltage = 0.0, chargerActualCurrent = 0.0))
            ))
        val series = ChargeStatsCalculator.project(detail)
        val stats = ChargeStatsCalculator.calculateStats(detail)
        assertEquals(listOf(0.0, 3.0), series.power.map { it.second })
        assertEquals(listOf(0.0, 100.0, 200.0), series.voltage.map { it.second })
        assertEquals(listOf(0.0, 0.0), series.current.map { it.second })
        assertEquals(listOf(-5.0, 1.0, 0.0), series.temperature.map { it.second })
        assertEquals(listOf(0, 10, 20), series.soc.map { it.second })
        assertEquals(0, stats.powerMin)
        assertEquals(3, stats.powerMax)
        assertEquals(1.5, stats.powerAvg!!, 0.0)
        assertEquals(0, stats.batteryStart)
        assertEquals(20, stats.batteryEnd)
        assertEquals(-5.0, stats.tempMin!!, 0.0)
        assertEquals(1.0, stats.tempMax!!, 0.0)
    }

    @Test fun ambiguousOrOutOfWindowSignalsProvideNoCurveOrStatPoints() {
        val detail = ChargeDetail(chargeId = 1, startDate = start, endDate = end,
            chargePoints = listOf(
                ChargePoint(date = "2026-10-20T09:59:00Z", batteryLevel = 80,
                    chargerDetails = ChargerDetails(chargerPower = 90.0)),
                ChargePoint(date = start, batteryLevel = 40,
                    chargerDetails = ChargerDetails(chargerPower = 2.0)),
                ChargePoint(date = start, batteryLevel = 50,
                    chargerDetails = ChargerDetails(chargerPower = 3.0))
            ))
        val series = ChargeStatsCalculator.project(detail)
        assertEquals(emptyList<Double>(), series.power.map { it.second })
        assertEquals(emptyList<Int>(), series.soc.map { it.second })
        val stats = ChargeStatsCalculator.calculateStats(detail)
        assertNull(stats.powerMax)
        assertNull(stats.batteryStart)
    }

    @Test fun oneQualifiedZeroSampleRetainsItsRealTimeForCompactObservation() {
        val detail = ChargeDetail(chargeId = 1, startDate = start, endDate = end,
            chargePoints = listOf(ChargePoint(date = start, batteryLevel = 0,
                outsideTemp = 0.0,
                chargerDetails = ChargerDetails(chargerPower = 0.0))))
        val series = ChargeStatsCalculator.project(detail)
        assertEquals(1, series.power.size)
        assertEquals(java.time.Instant.parse(start), series.power.single().first)
        assertEquals(0.0, series.power.single().second, 0.0)
        assertEquals(1, series.temperature.size)
        assertEquals(0.0, series.temperature.single().second, 0.0)
        assertEquals(1, series.soc.size)
        assertEquals(0, series.soc.single().second)
        assertEquals(0, ChargeStatsCalculator.calculateStats(detail).powerMax)
    }

    @Test
    fun missingPhasesAreUnknownRatherThanDcOrAc() {
        val detail = ChargeDetail(
            chargeId = 2,
            chargePoints = listOf(ChargePoint(chargerDetails = ChargerDetails(chargerPower = 7.0)))
        )
        assertEquals(ChargeType.UNKNOWN, ChargeStatsCalculator.detectChargeType(detail))
        assertEquals(false, ChargeStatsCalculator.detectDcCharge(detail))
    }

    @Test
    fun fleetModeRequiresContractProofAndCannotConflictWithDetails() {
        val proof = com.matelink.data.api.models.EnergyContract(
            chargeMode = "ac", chargeModeEvidence = "observed_boundary_modes_no_conflict"
        )
        assertEquals(ChargeType.AC, ChargeStatsCalculator.detectChargeType(
            ChargeDetail(chargeId = 7, chargeType = "ac", energyContract = proof)
        ))
        assertEquals(ChargeType.UNKNOWN, ChargeStatsCalculator.detectChargeType(
            ChargeDetail(chargeId = 7, chargeType = "ac", energyContract = proof.copy(chargeMode = null))
        ))
        assertEquals(ChargeType.UNKNOWN, ChargeStatsCalculator.detectChargeType(
            ChargeDetail(chargeId = 7, chargeType = "ac", energyContract = proof,
                chargePoints = listOf(ChargePoint(
                    chargerDetails = ChargerDetails(fastChargerPresent = true)))
            )
        ))
    }

    @Test
    fun conflictingExplicitChargeSignalsRemainUnknown() {
        val detail = ChargeDetail(
            chargeId = 3,
            chargePoints = listOf(
                ChargePoint(chargerDetails = ChargerDetails(fastChargerPresent = true)),
                ChargePoint(chargerDetails = ChargerDetails(chargerPhases = 2))
            )
        )
        assertEquals(ChargeType.UNKNOWN, ChargeStatsCalculator.detectChargeType(detail))
    }
}
