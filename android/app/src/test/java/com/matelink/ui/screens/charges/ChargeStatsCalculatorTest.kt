package com.matelink.ui.screens.charges

import com.matelink.data.api.models.ChargeBatteryDetails
import com.matelink.data.api.models.ChargeDetail
import com.matelink.data.api.models.ChargePoint
import com.matelink.data.api.models.ChargerDetails
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChargeStatsCalculatorTest {
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
                batteryDetails = ChargeBatteryDetails(startBatteryLevel = 0, endBatteryLevel = 0),
                chargePoints = listOf(
                    ChargePoint(
                        batteryLevel = 0,
                        outsideTemp = 0.0,
                        chargerDetails = ChargerDetails(
                            chargerPower = 0,
                            chargerVoltage = 0,
                            chargerActualCurrent = 0
                        )
                    )
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
}
