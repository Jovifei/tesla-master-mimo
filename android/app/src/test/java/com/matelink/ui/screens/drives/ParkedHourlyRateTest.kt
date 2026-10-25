package com.matelink.ui.screens.drives

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Duration

class ParkedHourlyRateTest {
    private val chargedWindow = Duration.ofMinutes(138)

    @Test fun socOnlyChargingUsesPercentagePointsPerHourWithoutInventingKwh() {
        assertEquals(30.0, requireNotNull(parkedSocPointsPerHour(69, chargedWindow)), 0.01)
        assertEquals(-1.3043, requireNotNull(parkedSocPointsPerHour(-3, chargedWindow)), 0.001)
        assertEquals(0.0, requireNotNull(parkedSocPointsPerHour(0, chargedWindow)), 0.0)
        assertNull(parkedEnergyKwhPerHour(null, null, chargedWindow))
    }

    @Test fun qualifiedEnergyUsesSignedKwhPerHourAndPreservesRealZero() {
        assertEquals(0.6, requireNotNull(parkedEnergyKwhPerHour(1.38, null, chargedWindow)), 0.0001)
        assertEquals(-0.2, requireNotNull(parkedEnergyKwhPerHour(-0.46, null, chargedWindow)), 0.0001)
        assertEquals(0.0, requireNotNull(parkedEnergyKwhPerHour(0.0, null, chargedWindow)), 0.0)
        assertEquals(0.5, requireNotNull(parkedEnergyKwhPerHour(null, 500.0, chargedWindow)), 0.0001)
    }

    @Test fun missingOrInvalidDurationCannotBecomeHourlyConsumption() {
        for (duration in listOf(null, Duration.ZERO, Duration.ofSeconds(-1))) {
            assertNull(parkedSocPointsPerHour(10, duration))
            assertNull(parkedEnergyKwhPerHour(1.0, 500.0, duration))
        }
        assertNull(parkedSocPointsPerHour(null, chargedWindow))
        assertNull(parkedSocPointsPerHour(101, chargedWindow))
        assertNull(parkedEnergyKwhPerHour(Double.NaN, Double.POSITIVE_INFINITY, chargedWindow))
    }
}
