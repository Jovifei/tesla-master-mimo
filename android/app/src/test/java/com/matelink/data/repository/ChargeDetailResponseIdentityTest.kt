package com.matelink.data.repository

import com.matelink.data.api.models.ChargeDetail
import com.matelink.data.api.models.ChargeDetailCar
import com.matelink.data.api.models.ChargeDetailData
import com.matelink.data.api.models.ChargeDetailResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

class ChargeDetailResponseIdentityTest {
    private fun response(carId: Int?, chargeId: Int) = Response.success(
        ChargeDetailResponse(data = ChargeDetailData(
            car = carId?.let { ChargeDetailCar(carId = it) },
            charge = ChargeDetail(chargeId = chargeId)
        ))
    )

    @Test fun matchingCarAndChargeAreAccepted() {
        val result = chargeDetailResultForResponse(response(7, 64), 7, 64)
        assertTrue(result is ApiResult.Success)
        assertEquals(64, (result as ApiResult.Success<ChargeDetail>).data.chargeId)
    }

    @Test fun differentResponseCarOrChargeIsRejected() {
        for (payload in listOf(response(8, 64), response(7, 65))) {
            val result = chargeDetailResultForResponse(payload, 7, 64)
            assertTrue(result is ApiResult.Error)
            assertEquals(ApiErrorKind.INVALID_RESPONSE, (result as ApiResult.Error).kind)
        }
    }

    @Test fun legacyResponseWithoutCarIsReadableWhenChargeMatches() {
        assertTrue(chargeDetailResultForResponse(response(null, 64), 7, 64) is ApiResult.Success)
    }
}
