package com.matelink.ui.screens.charges

import com.matelink.data.api.models.*
import com.matelink.data.repository.*
import com.matelink.data.sync.toSyncSummary
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Response

class AppReleaseChargeReceiptTest {
    private fun summary(latitude: Double = 30.0) = ChargeData(1,
        startDate = "2026-10-20T00:00:00Z", endDate = "2026-10-20T00:10:00Z",
        source = "teslamate_archive", latitude = latitude, longitude = 120.0).toSyncSummary(-1)!!
    @Test fun anotherSessionUnderSameNumericIdCannotSupplyThePosition() {
        val current = ChargeDetail(1, startDate = "2026-10-27T00:00:00Z", endDate = "2026-10-27T00:10:00Z",
            source = "teslamate_archive")
        assertNull(chooseChargeCoordinates(current, summary()))
    }
    @Test fun invalidPositionMustNotEnterTheMap() {
        assertNull(chooseChargeCoordinates(ChargeDetail(1, latitude = 91.0, longitude = 120.0), null))
    }
    @Test fun verifiedPositionWithOneZeroAxisIsARealCoordinatePair() {
        val row = summary(0.0)
        val current = ChargeDetail(1, startDate = row.startDate, endDate = row.endDate, source = "teslamate_archive")
        assertEquals(0.0 to 120.0, chooseChargeCoordinates(current, row))
    }
    @Test fun currentResponseMustMatchRequestedVehicleWhenServerSuppliesItsIdentity() {
        val response = Response.success(ChargeDetailResponse(ChargeDetailData(ChargeDetailCar(8), ChargeDetail(1))))
        val result = currentChargeResultForResponse(response, 7)
        assertTrue(result is ApiResult.Error)
        assertEquals(ApiErrorKind.INVALID_RESPONSE, (result as ApiResult.Error).kind)
    }
    @Test fun currentNoActiveAndSameCarResponseRemainSupported() {
        assertTrue(currentChargeResultForResponse(Response.success(ChargeDetailResponse(error = "no active")), 7) is ApiResult.Success)
        assertTrue(currentChargeResultForResponse(Response.success(ChargeDetailResponse(ChargeDetailData(ChargeDetailCar(7), ChargeDetail(1)))), 7) is ApiResult.Success)
    }
    @Test fun offlineDetailRestoresNullableDurationFromReceiptInsteadOfPrimitiveZero() {
        val summary = ChargeData(1, startDate = "2026-10-27T00:00:00Z", endDate = "2026-10-27T00:10:00Z",
            source = "teslamate_archive", durationMin = null, durationStr = "recorded duration").toSyncSummary(-1)!!
        val detail = cachedChargeDetail(summary)
        assertNull(detail.durationMin)
        assertEquals("recorded duration", detail.durationStr)
    }
    @Test fun currentCounterIsDistinctFromWholeWindowAndCannotBeInventedFromRawScalar() {
        val receipt = CurrentChargeEnergyReceipt(1, 12.0, "kWh", "fleet_api", "charge_state.charge_energy_added",
            "provider_current_plug_counter", "reported", "2026-10-27T00:10:00Z")
        val detail = ChargeDetail(1, source = "fleet_api", currentEnergyReceipt = receipt)
        assertEquals(12.0, currentChargeEnergyKwh(detail)!!, 0.0)
        assertNull("A live counter does not prove a complete historic window", detail.batteryInputKwh)
        assertNull(currentChargeEnergyKwh(detail.copy(currentEnergyReceipt = receipt.copy(quality = "unknown"))))
        assertNull(currentChargeEnergyKwh(detail.copy(currentEnergyReceipt = receipt.copy(sourceField = "other"))))
    }
}
