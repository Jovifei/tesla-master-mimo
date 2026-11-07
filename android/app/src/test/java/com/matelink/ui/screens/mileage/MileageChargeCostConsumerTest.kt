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

    private fun timedCharge(charge: ChargeData, cost: Double?, dateTime: LocalDateTime = at): Any =
        Class.forName("com.matelink.ui.screens.mileage.TimedCharge")
            .getDeclaredConstructor(ChargeData::class.java, LocalDateTime::class.java,
                java.lang.Double::class.java).apply { isAccessible = true }
            .newInstance(charge, dateTime, cost)

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

    private fun charge(id: Int, dateTime: LocalDateTime, cost: Double?) = ChargeData(
        id, startDate = dateTime.toInstant(java.time.ZoneOffset.UTC).toString(),
        endDate = dateTime.plusMinutes(30).toInstant(java.time.ZoneOffset.UTC).toString(),
        chargeEnergyAdded = 5.0, cost = cost, source = "teslamate_archive",
        qualityState = "observed", currencyCode = "CNY"
    )

    private fun yearAggregation(drives: List<Any>, charges: List<Any>): Any =
        functions.getDeclaredMethod("computeYearAggregation", List::class.java, List::class.java)
            .apply { isAccessible = true }.invoke(null, drives, charges)

    private fun monthAggregation(drives: List<Any>, charges: List<Any>, year: Int): Any =
        functions.getDeclaredMethod("computeMonthAggregation", List::class.java,
            List::class.java, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(null, drives, charges, year)

    private fun yearDetailCost(aggregation: Any): Double? =
        aggregation.javaClass.getDeclaredMethod("getYearTotalEnergyCost").invoke(aggregation) as Double?

    @Test fun yearDetailIncludesKnownBillsFromMonthsWithoutDrives() {
        val septemberAt = at.minusMonths(1)
        val october = charge(20, at, 3.0)
        val september = charge(21, septemberAt, 7.0)
        val charges = listOf(
            timedCharge(october, projected(october, 701, emptyList())),
            timedCharge(september, projected(september, 701, emptyList()), septemberAt)
        )
        val drives = listOf(timedDrive())
        val year = yearAggregation(drives, charges)
        assertEquals(10.0, year.javaClass.getDeclaredMethod("getTotalLifetimeEnergyCost")
            .invoke(year) as Double, 0.0)
        assertEquals(10.0, (year.javaClass.getDeclaredMethod("getYearlyData")
            .invoke(year) as List<YearlyMileage>).single().totalEnergyCost!!, 0.0)

        val month = monthAggregation(drives, charges, 2026)
        // This fix does not add charge-only rows to the mileage list.
        assertEquals(listOf(YearMonth.of(2026, 10)),
            (month.javaClass.getDeclaredMethod("getMonthlyData").invoke(month)
                as List<MonthlyMileage>).map { it.yearMonth })
        assertEquals(10.0, yearDetailCost(month) ?: Double.NaN, 0.0)
    }

    @Test fun yearDetailExcludesBillsOutsideTheSelectedYear() {
        val septemberAt = at.minusMonths(1)
        val otherYearAt = at.minusYears(1)
        val october = charge(30, at, 3.0)
        val september = charge(31, septemberAt, 7.0)
        val otherYear = charge(32, otherYearAt, 11.0)
        val charges = listOf(
            timedCharge(october, projected(october, 701, emptyList())),
            timedCharge(september, projected(september, 701, emptyList()), septemberAt),
            timedCharge(otherYear, projected(otherYear, 701, emptyList()), otherYearAt)
        )
        val drives = listOf(timedDrive())
        val year = yearAggregation(drives, charges)
        assertEquals(21.0, year.javaClass.getDeclaredMethod("getTotalLifetimeEnergyCost")
            .invoke(year) as Double, 0.0)
        assertEquals(10.0, (year.javaClass.getDeclaredMethod("getYearlyData")
            .invoke(year) as List<YearlyMileage>).single().totalEnergyCost!!, 0.0)
        assertEquals(10.0, yearDetailCost(monthAggregation(drives, charges, 2026)) ?: Double.NaN, 0.0)
    }

    @Test fun yearDetailRetainsObservedManualZeroInAMonthWithoutDrives() {
        val septemberAt = at.minusMonths(1)
        val free = charge(40, septemberAt, 12.0)
        val cost = projected(free, 701, listOf(manual(free, 701, 0.0)))
        assertEquals(0.0, cost ?: Double.NaN, 0.0)
        val charges = listOf(timedCharge(free, cost, septemberAt))
        assertEquals(0.0, yearDetailCost(monthAggregation(listOf(timedDrive()), charges, 2026))
            ?: Double.NaN, 0.0)
    }

    @Test fun yearDetailKeepsAllUnknownBillsUnknownInAMonthWithoutDrives() {
        val septemberAt = at.minusMonths(1)
        val unknown = charge(50, septemberAt, null)
        val cost = projected(unknown, 701, emptyList())
        assertNull(cost)
        val charges = listOf(timedCharge(unknown, cost, septemberAt))
        assertNull(yearDetailCost(monthAggregation(listOf(timedDrive()), charges, 2026)))
    }
}
