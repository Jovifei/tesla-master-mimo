package com.matelink.domain.analytics

import com.matelink.data.api.models.ChargeData
import com.matelink.data.local.AppSettings
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class AppReleaseTariffConsumerTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val settings = AppSettings(defaultChargePrice = 1.14, tariffEnabled = true,
        tariffPeakPrice = 2.0, tariffFlatPrice = 1.0, tariffValleyPrice = 0.5)
    private fun charge(start: String = "2026-10-27T01:30:00Z", end: String = "2026-10-27T02:30:00Z",
        energy: Double? = 10.0) = ChargeData(1, startDate = start, endDate = end,
            chargeEnergyAdded = energy, source = "teslamate_archive", currencyCode = "CNY")

    @Test fun actualCostProjectionUsesTheSavedHourlyScheduleForUnpricedSessions() {
        val c = charge()
        val estimate = estimateChargeTariff(c, settings, zone)
        val result = projectEffectiveChargeCost(c, -1, null, "CNY", tariffEstimate = estimate)
        assertEquals(15.0, result.cost ?: Double.NaN, 1e-9)
        assertEquals(ChargeCostSource.ESTIMATE, result.source)
        assertEquals("tariff_time_weighted_app_zone", result.qualificationReason)
    }
    @Test fun overnightRangeAndObservedZeroRemainCalculable() {
        assertEquals(5.0, estimateChargeTariff(charge("2026-10-27T15:30:00Z", "2026-10-27T16:30:00Z"), settings, zone).amount!!, 1e-9)
        assertEquals(0.0, estimateChargeTariff(charge(energy = 0.0), settings, zone).amount!!, 0.0)
    }
    @Test fun invalidOrOverlappingScheduleDoesNotInventAPrice() {
        for (s in listOf(settings.copy(tariffPeakPrice = Double.NaN), settings.copy(tariffPeakRanges = "[[0,23]]")))
            assertNull(estimateChargeTariff(charge(), s, zone).amount)
    }
    @Test fun missingWindowCanOnlyUseTheExplicitDefaultRateEstimate() {
        val estimate = estimateChargeTariff(charge(energy = 3.0).copy(endDate = null), settings, zone)
        assertEquals(3.42, estimate.amount ?: Double.NaN, 1e-9)
        assertEquals("tariff_window_unavailable_default_rate", estimate.reason)
    }
    @Test fun disabledScheduleRetainsDefaultEstimateAndKnownBillStillWins() {
        val c = charge()
        assertEquals(11.4, estimateChargeTariff(c, settings.copy(tariffEnabled = false), zone).amount!!, 1e-9)
        val billed = c.copy(cost = 23.0)
        assertEquals(23.0, projectEffectiveChargeCost(billed, -1, null, "CNY",
            tariffEstimate = estimateChargeTariff(c, settings, zone)).cost!!, 0.0)
    }
}
