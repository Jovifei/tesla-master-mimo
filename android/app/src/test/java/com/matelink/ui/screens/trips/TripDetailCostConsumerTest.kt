package com.matelink.ui.screens.trips

import com.matelink.data.local.entity.ChargeSummary
import com.matelink.data.local.entity.ChargeCostOverride
import com.matelink.data.local.entity.DriveSummary
import com.matelink.domain.analytics.chargeCostSessionKey
import com.matelink.domain.analytics.toAnalysisChargeData
import com.matelink.domain.TripAggregator
import com.matelink.domain.model.Trip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Uses the detail card's actual price projection, including its cache admission check. */
class TripDetailCostConsumerTest {
    private val start = "2026-10-14T10:00:00Z"
    private val end = "2026-10-14T10:30:00Z"

    private fun drive(carId: Int = 701) = DriveSummary(
        driveId = 1, carId = carId, startDate = start, endDate = end, durationMin = 30,
        startAddress = "A", endAddress = "B", distance = 10.0, speedMax = 90, speedAvg = 30,
        powerMax = 20, powerMin = -5, startBatteryLevel = 80, endBatteryLevel = 75,
        outsideTempAvg = null, insideTempAvg = null, energyConsumed = null, efficiency = null)

    private fun charge(id: Int, cost: Double?, carId: Int = 701, qualifiedKwh: Double? = null) =
        ChargeSummary(chargeId = id, carId = carId, startDate = start, endDate = end,
            durationMin = 30, address = "B", latitude = 1.0, longitude = 2.0,
            energyAdded = qualifiedKwh ?: 5.0, energyUsed = null, cost = cost,
            startBatteryLevel = 75, endBatteryLevel = 80, outsideTempAvg = null,
            odometer = 100.0, apiEvidence =
                """{"charge_id":$id,"start_date":"$start","end_date":"$end","source":"teslamate_archive","currency_code":"CNY","cost":${cost ?: "null"}${qualifiedKwh?.let { ",\"charge_energy_added\":$it" } ?: ""}}""")

    private fun trip(drives: List<DriveSummary>, charges: List<ChargeSummary>): Trip =
        requireNotNull(TripAggregator.buildTrip(drives, charges))

    private fun project(trip: Trip, localCarId: Int, manual: List<ChargeCostOverride>): Any? =
        projectTripChargeCosts(trip, localCarId, manual, "CNY")

    private fun manual(charge: ChargeSummary, amount: Double, carId: Int = 701): ChargeCostOverride {
        val analysis = charge.toAnalysisChargeData()
        return ChargeCostOverride(carId, charge.chargeId, amount,
            sessionKey = chargeCostSessionKey(analysis) + ":CNY", currencyCode = "CNY",
            sourceKind = analysis.source, startInstant = analysis.startDate,
            endInstant = analysis.endDate, version = 1L)
    }

    private fun value(result: Any, getter: String): Any? =
        result.javaClass.getDeclaredMethod(getter).invoke(result)

    @Test fun cardProjectionUsesManualZeroAndOnlySameSessionQualifiedEnergyForUnitPrice() {
        val trip = trip(listOf(drive()), listOf(charge(10, 8.0, qualifiedKwh = 2.0),
            charge(11, 10.0)))
        assertEquals(18.0, trip.totalChargeCost!!, 0.0) // Raw receipt stays unchanged.

        val result = requireNotNull(project(trip, 701, listOf(manual(trip.charges.first(), 0.0))))
        assertEquals(10.0, value(result, "getTotalCost") as Double, 0.0)
        assertEquals(0.0, value(result, "getPairedUnitCost") as Double, 0.0)
        assertEquals(true, value(result, "getAllPriced"))
        val priced = value(result, "getPricedCharges") as List<*>
        assertEquals(2, priced.size)
        assertEquals(0.0, value(priced.first()!!, "getCost") as Double, 0.0)
        assertEquals(2.0, value(priced.first()!!, "getEnergyKwh") as Double, 0.0)
        assertEquals(10.0, value(priced.last()!!, "getCost") as Double, 0.0)
        assertNull(value(priced.last()!!, "getEnergyKwh"))
    }

    @Test fun partialPriceIsNotCompleteAndUnknownRemainsUnknown() {
        val trip = trip(listOf(drive()), listOf(charge(10, null), charge(11, 0.0)))
        val result = requireNotNull(project(trip, 701, emptyList()))
        assertNull(value(result, "getTotalCost"))
        assertEquals(false, value(result, "getAllPriced"))
        assertNull(value(result, "getPairedUnitCost"))
        val allUnknown = project(trip(listOf(drive()), listOf(charge(10, null))), 701, emptyList())
        assertNotNull(allUnknown)
        assertNull(value(allUnknown!!, "getTotalCost"))
        assertTrue((value(allUnknown, "getPricedCharges") as List<*>).isEmpty())
    }

    @Test fun foreignCachedTripCannotBorrowTheCurrentCarsPriceOrBePublished() {
        val foreignDrive = trip(listOf(drive(702)), listOf(charge(10, 8.0, 702)))
        val mixedLeg = trip(listOf(drive()), listOf(charge(10, 8.0, 702)))
        assertNull(project(foreignDrive, 701, emptyList()))
        assertNull(project(mixedLeg, 701, emptyList()))
        val own = project(trip(listOf(drive()), listOf(charge(10, 8.0))), 701,
            emptyList())
        assertEquals(8.0, value(requireNotNull(own), "getTotalCost") as Double, 0.0)
    }

    @Test fun movedArchiveReceiptCannotProvideThePriceDenominator() {
        val moved = charge(10, 8.0, qualifiedKwh = 2.0)
            .copy(endDate = "2026-10-14T10:35:00Z")
        val result = requireNotNull(project(trip(listOf(drive()), listOf(moved)), 701, emptyList()))
        assertEquals(8.0, value(result, "getTotalCost") as Double, 0.0)
        assertNull(value(result, "getPairedUnitCost"))
        assertNull(value((value(result, "getPricedCharges") as List<*>).single()!!,
            "getEnergyKwh"))
    }
}
