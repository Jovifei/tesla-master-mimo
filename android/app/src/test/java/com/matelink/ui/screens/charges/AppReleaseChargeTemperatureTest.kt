package com.matelink.ui.screens.charges

import com.matelink.data.api.models.*
import com.matelink.data.repository.chargeDetailResultForResponse
import com.matelink.data.repository.ApiResult
import com.matelink.data.sync.toSyncSummary
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Response

class AppReleaseChargeTemperatureTest {
    @Test fun savedFahrenheitPointsMustNotReceiveTheCelsiusSummaryLabel() {
        val row = ChargeData(1, startDate = "2026-10-27T00:00:00Z", endDate = "2026-10-27T00:10:00Z",
            source = "teslamate_archive", temperatureUnit = "C", outsideTempAvg = 20.0).toSyncSummary(-1)!!
        val header = ChargeDetail(1, source = "teslamate_archive", temperatureUnit = "F", chargePoints = listOf(
            ChargePoint(date = "2026-10-27T00:05:00Z", outsideTemp = 68.0)))
        val display = presentChargeTemperature(cachedChargeDetail(row, header), Units(unitOfTemperature = "C"))
        assertEquals("C", display.second?.unitOfTemperature)
        assertEquals(20.0, display.first.chargePoints!!.single().outsideTemp!!, 1e-9)
        assertEquals(68.0, header.chargePoints!!.single().outsideTemp!!, 0.0)
    }
    @Test fun freshInstantPowerIsNotTheLastHistoricalCurvePoint() {
        val now = java.time.Instant.parse("2026-10-27T00:10:00Z")
        val detail = ChargeDetail(1, chargePoints = listOf(ChargePoint(
            date = "2026-10-27T00:00:00Z", chargerDetails = ChargerDetails(chargerPower = 11.0))))
        val status = CarStatus(chargingDetails = ChargingDetails(chargerPower = 2.0))
        val snapshot = VehicleStatusEvidence(status, Units(), com.matelink.domain.telemetry.snapshotEvidence(
            "fleet_api", now.toString(), emptyMap(), now))
        assertEquals(2.0, currentChargeInstantPower(detail, snapshot, now)!!, 0.0)
        assertNull(currentChargeInstantPower(detail, snapshot.copy(status = null), now))
        assertEquals(0.0, currentChargeInstantPower(detail, snapshot.copy(status =
            status.copy(chargingDetails = ChargingDetails(chargerPower = 0.0))), now)!!, 0.0)
    }
    @Test fun responseAndOfflineReceiptKeepTheirOwnTemperatureUnit() {
        val raw = ChargeDetail(1, startDate = "2026-10-27T00:00:00Z", endDate = "2026-10-27T00:10:00Z",
            source = "teslamate_archive", outsideTempAvg = 68.0)
        val received = (chargeDetailResultForResponse(Response.success(ChargeDetailResponse(
            ChargeDetailData(ChargeDetailCar(7), raw, Units(unitOfTemperature = "F")))), 7, 1) as ApiResult.Success).data
        val row = ChargeData(1, startDate = raw.startDate, endDate = raw.endDate, source = raw.source,
            outsideTempAvg = received.outsideTempAvg, temperatureUnit = received.temperatureUnit).toSyncSummary(-7)!!
        val cached = cachedChargeDetail(row)
        val (display, units) = presentChargeTemperature(cached, Units(unitOfTemperature = "C"))
        assertEquals("F", units?.unitOfTemperature)
        assertEquals(68.0, display.outsideTempAvg!!, 0.0)
    }
    @Test fun anExplicitUnknownTemperatureUnitCannotBorrowTodaysPreference() {
        val detail = ChargeDetail(1, source = "teslamate_archive", temperatureUnit = "", outsideTempAvg = 20.0,
            chargePoints = listOf(ChargePoint(outsideTemp = 20.0)))
        val display = presentChargeTemperature(detail, Units(unitOfTemperature = "C")).first
        assertNull(display.outsideTempAvg)
        assertNull(display.chargePoints!!.single().outsideTemp)
        assertEquals(20.0, detail.outsideTempAvg!!, 0.0)
    }
    @Test fun activeObservedSamplesCanDescribeElectricalSampleStatsWithoutInventingPlugInTime() {
        val detail = ChargeDetail(1, isCharging = true, chargePoints = listOf(
            ChargePoint(date = "2026-10-27T00:00:00Z", chargerDetails = ChargerDetails(chargerPower = 0.0)),
            ChargePoint(date = "2026-10-27T00:00:10Z", chargerDetails = ChargerDetails(chargerPower = 10.0))))
        val stats = currentChargeStats(detail)
        assertEquals(10, stats.powerMax)
        assertEquals(5.0, stats.powerAvg!!, 0.0)
        assertNull(detail.startDate)
        assertNull(stats.durationMin)
        assertNull(stats.energyAdded)
    }
}
