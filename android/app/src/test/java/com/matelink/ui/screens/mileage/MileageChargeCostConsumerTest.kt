package com.matelink.ui.screens.mileage

import com.matelink.data.api.models.ChargeData
import com.matelink.data.api.models.DriveData
import com.matelink.data.api.models.DriveOdometerDetails
import com.matelink.data.local.entity.ChargeCostOverride
import com.matelink.domain.analytics.chargeCostSessionKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime
import java.time.YearMonth

/** Exercises the same price snapshot used by the year, month and day consumers. */
class MileageChargeCostConsumerTest {
    private val functions = Class.forName("com.matelink.ui.screens.mileage.MileageViewModelKt")
    private val at = LocalDateTime.of(2026, 10, 14, 10, 0)

    private fun projected(charge: ChargeData, localCarId: Int, overrides: List<ChargeCostOverride>): Double? =
        functions.getDeclaredMethod("projectMileageChargeCost", ChargeData::class.java,
            Int::class.javaPrimitiveType, List::class.java, String::class.java)
            .apply { isAccessible = true }
            .invoke(null, charge, localCarId, overrides, "CNY") as Double?

    private fun manual(charge: ChargeData, carId: Int, amount: Double) =
        ChargeCostOverride(carId, charge.chargeId, amount,
            sessionKey = chargeCostSessionKey(charge) + ":CNY", currencyCode = "CNY",
            sourceKind = charge.source, startInstant = charge.startDate,
            endInstant = charge.endDate, version = 1L)

    private fun timedCharge(charge: ChargeData, cost: Double?): Any =
        Class.forName("com.matelink.ui.screens.mileage.TimedCharge")
            .getDeclaredConstructor(ChargeData::class.java, LocalDateTime::class.java,
                java.lang.Double::class.java).apply { isAccessible = true }
            .newInstance(charge, at, cost)

    private fun timedDrive(): Any = Class.forName("com.matelink.ui.screens.mileage.TimedDrive")
        .getDeclaredConstructor(DriveData::class.java, LocalDateTime::class.java)
        .apply { isAccessible = true }.newInstance(
            DriveData(1, startDate = "2026-10-14T10:00:00Z",
                endDate = "2026-10-14T11:00:00Z", odometerDetails = DriveOdometerDetails(distance = 10.0)), at)

    @Test fun yearlyMonthlyDailyConsumersUseScopedManualTotalIncludingRealZero() {
        val free = ChargeData(10, startDate = "2026-10-14T10:00:00Z",
            endDate = "2026-10-14T10:30:00Z", cost = 12.0,
            source = "teslamate_archive", currencyCode = "CNY")
        val paid = ChargeData(11, startDate = "2026-10-14T10:00:00Z",
            endDate = "2026-10-14T10:30:00Z", cost = 3.0,
            source = "teslamate_archive", currencyCode = "CNY")
        val charges = listOf(timedCharge(free, projected(free, 701, listOf(manual(free, 701, 0.0)))),
            timedCharge(paid, projected(paid, 701, emptyList())))
        val drives = listOf(timedDrive())

        val year = functions.getDeclaredMethod("computeYearAggregation", List::class.java,
            List::class.java).apply { isAccessible = true }.invoke(null, drives, charges)
        assertEquals(3.0, (year.javaClass.getDeclaredMethod("getTotalLifetimeEnergyCost")
            .invoke(year) as Double), 0.0)
        assertEquals(3.0, ((year.javaClass.getDeclaredMethod("getYearlyData")
            .invoke(year) as List<YearlyMileage>).single().totalEnergyCost!!), 0.0)

        val month = functions.getDeclaredMethod("computeMonthAggregation", List::class.java,
            List::class.java, Int::class.javaPrimitiveType).apply { isAccessible = true }
            .invoke(null, drives, charges, 2026)
        assertEquals(3.0, (month.javaClass.getDeclaredMethod("getYearTotalEnergyCost")
            .invoke(month) as Double), 0.0)
        assertEquals(3.0, ((month.javaClass.getDeclaredMethod("getMonthlyData")
            .invoke(month) as List<MonthlyMileage>).single().totalEnergyCost!!), 0.0)

        val day = functions.getDeclaredMethod("computeDailyAggregation", List::class.java,
            List::class.java, YearMonth::class.java).apply { isAccessible = true }
            .invoke(null, drives, charges, YearMonth.of(2026, 10)) as List<DailyMileage>
        assertEquals(3.0, day.single().totalEnergyCost!!, 0.0)
    }

    @Test fun foreignOverrideAndUnverifiedScalarNeverCreateAChargePrice() {
        val raw = ChargeData(10, startDate = "2026-10-14T10:00:00Z",
            endDate = "2026-10-14T10:30:00Z", source = "teslamate_archive",
            currencyCode = "CNY", cost = 12.0)
        assertEquals(12.0, projected(raw, 701, listOf(manual(raw, 702, 99.0)))!!, 0.0)
        assertNull(projected(raw, 701, listOf(manual(raw, 701, Double.NaN))))
        val unknown = ChargeData(11, cost = null)
        assertNull(projected(unknown, 701, emptyList()))
        assertNull(projected(unknown, 701, listOf(ChargeCostOverride(701, 11, 0.0))))
        assertNull(projected(ChargeData(12, cost = Double.NaN), 701, emptyList()))
        assertNull(projected(ChargeData(13, source = "teslamate_archive", cost = 0.0,
            currencyCode = "CNY"), 701, emptyList()))
    }
}
