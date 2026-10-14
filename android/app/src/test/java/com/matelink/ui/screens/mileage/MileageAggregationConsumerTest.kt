package com.matelink.ui.screens.mileage

import com.matelink.data.api.models.DriveData
import com.matelink.data.api.models.DriveOdometerDetails
import com.matelink.domain.analytics.buildMileageEvidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime
import java.time.YearMonth

/** Invoke the actual VM's pure snapshot aggregators without Android lifecycle
 * services, owner data or widening their private production API for tests.
 */
class MileageAggregationConsumerTest {
    private val packageName = "com.matelink.ui.screens.mileage"
    private val functions = Class.forName("$packageName.MileageViewModelKt")
    private val timed = Class.forName("$packageName.TimedDrive").getDeclaredConstructor(
        DriveData::class.java, LocalDateTime::class.java).apply { isAccessible = true }

    private fun drive(id: Int, distance: Double, energy: Double?, unit: String? = null) = DriveData(
        driveId = id, startDate = "2026-10-14T01:00:00Z", endDate = "2026-10-14T02:00:00Z",
        odometerDetails = DriveOdometerDetails(distance = distance), energyConsumedNet = energy,
        lengthUnit = unit, qualityState = "observed")

    private fun timed(drives: List<DriveData>): List<Any> = drives.map {
        timed.newInstance(it, LocalDateTime.of(2026, 10, 14, 1, 0))
    }

    private fun number(result: Any, getter: String): Double = result.javaClass.getDeclaredMethod(getter)
        .apply { isAccessible = true }.invoke(result) as Double

    @Test fun actualYearAggregationDoesNotDiluteKnownEnergyWithUnknownEnergyDistance() {
        val drives = listOf(drive(1, 10.0, 2.0), drive(2, 90.0, null),
            drive(3, 100.0, 999.0).copy(qualityState = "quarantined"))
        val result = functions.getDeclaredMethod("computeYearAggregation", List::class.java, List::class.java)
            .apply { isAccessible = true }.invoke(null, timed(drives), emptyList<Any>())
        assertEquals(100.0, number(result, "getTotalLifetimeDistance"), 0.0)
        assertEquals(2.0, number(result, "getTotalLifetimeEnergy"), 0.0)
        assertEquals(200.0, number(result, "getAvgLifetimeEnergyDistance"), 0.0)
    }

    @Test fun actualMonthlyAggregationKeepsMixedUnitZeroNetEnergyCanonical() {
        val drives = listOf(drive(1, 10.0, 1.0), drive(2, 10.0, -1.0, "mi"))
        val result = functions.getDeclaredMethod("computeMonthAggregation", List::class.java,
            List::class.java, Int::class.javaPrimitiveType).apply { isAccessible = true }
            .invoke(null, timed(drives), emptyList<Any>(), 2026)
        assertEquals(26.09344, number(result, "getYearTotalDistance"), 1e-10)
        assertEquals(0.0, number(result, "getYearTotalEnergy"), 0.0)
        assertEquals(0.0, number(result, "getAvgYearEnergyDistance"), 0.0)
        assertEquals(2, buildMileageEvidence(drives).energySampleCount)
    }

    @Test fun actualDailyAggregationRetainsNegativeEnergyAndLeavesAllUnknownUnavailable() {
        fun aggregate(drives: List<DriveData>): DailyMileage {
            val result = functions.getDeclaredMethod("computeDailyAggregation", List::class.java,
                List::class.java, YearMonth::class.java).apply { isAccessible = true }
                .invoke(null, timed(drives), emptyList<Any>(), YearMonth.of(2026, 10))
            return (result as List<*>).single() as DailyMileage
        }
        val negative = aggregate(listOf(drive(1, 10.0, -1.0), drive(2, 10.0, 0.0)))
        assertEquals(-1.0, negative.totalEnergy, 0.0)
        assertEquals(-50.0, buildMileageEvidence(negative.drives).efficiencyWhKm!!, 0.0)
        val unknown = aggregate(listOf(drive(1, 10.0, null)))
        assertEquals(0, buildMileageEvidence(unknown.drives).energySampleCount)
        assertNull(buildMileageEvidence(unknown.drives).efficiencyWhKm)
    }
}
