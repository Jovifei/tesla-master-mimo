package com.matelink.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionTestResultTest {

    @Test fun blankUrl_isInvalid() = assertInvalid(" ", "地址格式不正确")

    @Test fun missingScheme_isInvalid() = assertInvalid(
        "teslamate.local", "地址格式不正确"
    )

    @Test fun rootUrls_areNormalizedWithoutChangingProtocolOrPort() {
        assertEquals(
            ConnectionUrlValidation.Valid("https://api.example.com:8443"),
            validateConnectionUrl("  https://api.example.com:8443/  ")
        )
        assertEquals(
            ConnectionUrlValidation.Valid("http://192.168.0.104:8080"),
            validateConnectionUrl("http://192.168.0.104:8080/")
        )
    }

    @Test fun localhostAndIpv6Roots_areValid() {
        assertEquals(
            ConnectionUrlValidation.Valid("http://localhost:8080"),
            validateConnectionUrl("http://localhost:8080")
        )
        assertEquals(
            ConnectionUrlValidation.Valid("http://[fd00::1]:8080"),
            validateConnectionUrl("http://[fd00::1]:8080")
        )
    }

    @Test fun protocolAndUriShapeProblems_areRejected() {
        assertInvalid("https://", "地址格式不正确")
        assertInvalid("https://https://example.com", "地址格式不正确")
        assertInvalid("ftp://example.com", "地址格式不正确")
        assertInvalid("https://user:pass@example.com", "地址格式不正确")
        assertInvalid("https://example.com?key=value", "地址格式不正确")
        assertInvalid("https://example.com#section", "地址格式不正确")
        assertInvalid("https://example.com:99999", "地址格式不正确")
    }

    @Test fun apiPathAndOtherPaths_areRejectedWithoutRewriting() {
        val expected = "只填写服务器根地址，不要追加 /api/v1 或其他接口路径。"
        assertInvalid("https://example.com/api/v1", expected)
        assertInvalid("https://example.com/anything", expected)
    }

    @Test fun readyzWarning_doesNotBlockSuccessfulCarsProbe() {
        val outcome = ConnectionTestOutcome(
            ping = ConnectionStepResult.Success,
            readiness = ConnectionStepResult.Warning("Readiness endpoint is unavailable"),
            cars = ConnectionStepResult.Success,
            carCount = 1,
            firstCarName = "Model 3"
        )

        assertTrue(outcome.isSuccessful)
        assertEquals("Connected to 1 car: Model 3", outcome.summary)
    }

    @Test fun emptyCarsResponse_isNotSuccessful() {
        val outcome = ConnectionTestOutcome(
            ping = ConnectionStepResult.Success,
            readiness = ConnectionStepResult.Success,
            cars = ConnectionStepResult.Failure("No cars returned by TeslaMate")
        )

        assertFalse(outcome.isSuccessful)
    }

    private fun assertInvalid(input: String, message: String) {
        val result = validateConnectionUrl(input)
        assertTrue("$input should be rejected", result is ConnectionUrlValidation.Invalid)
        assertTrue("$input must have a controlled error", (result as ConnectionUrlValidation.Invalid).message.isNotBlank())
    }
}
