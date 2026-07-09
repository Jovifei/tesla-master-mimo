package com.matelink.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionTestResultTest {

    @Test
    fun blankUrl_isInvalid() {
        assertEquals(
            ConnectionUrlValidation.Invalid("Server URL is required"),
            validateConnectionUrl(" ")
        )
    }

    @Test
    fun missingScheme_isInvalid() {
        assertEquals(
            ConnectionUrlValidation.Invalid("URL must start with http:// or https://"),
            validateConnectionUrl("teslamate.local")
        )
    }

    @Test
    fun rootUrl_isNormalizedWithoutApiPath() {
        assertEquals(
            ConnectionUrlValidation.Valid("https://teslamate.example.com"),
            validateConnectionUrl("https://teslamate.example.com/api/v1/")
        )
    }

    @Test
    fun readyzWarning_doesNotBlockSuccessfulCarsProbe() {
        val outcome = ConnectionTestOutcome(
            ping = ConnectionStepResult.Success,
            readiness = ConnectionStepResult.Warning(
                message = "Readiness endpoint is unavailable",
                hint = "Continuing with vehicle check"
            ),
            cars = ConnectionStepResult.Success,
            carCount = 1,
            firstCarName = "Model 3"
        )

        assertTrue(outcome.isSuccessful)
        assertEquals("Connected to 1 car: Model 3", outcome.summary)
    }

    @Test
    fun emptyCarsResponse_isNotSuccessful() {
        val outcome = ConnectionTestOutcome(
            ping = ConnectionStepResult.Success,
            readiness = ConnectionStepResult.Success,
            cars = ConnectionStepResult.Failure(
                message = "No cars returned by TeslaMate",
                hint = "Check TeslaMate API permissions and data availability"
            )
        )

        assertFalse(outcome.isSuccessful)
    }
}
