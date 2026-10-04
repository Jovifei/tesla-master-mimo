package com.matelink.data.repository

import com.squareup.moshi.JsonDataException
import com.squareup.moshi.JsonEncodingException
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.Instant
import javax.net.ssl.SSLHandshakeException

class SafeApiFailureTest {
    private val secret = "https://secret.invalid/VIN?access_token=private body=private id=123"
    @Test fun actualExceptionTypesHaveFixedDistinctCategoriesAndNeverExposeMessages() {
        val cases = listOf(
            UnknownHostException(secret) to SafeApiFailure.DNS,
            ConnectException(secret) to SafeApiFailure.CONNECT,
            SocketTimeoutException(secret) to SafeApiFailure.TIMEOUT,
            SSLHandshakeException(secret) to SafeApiFailure.TLS,
            JsonDataException(secret) to SafeApiFailure.JSON_DATA,
            JsonEncodingException(secret) to SafeApiFailure.JSON_ENCODING,
            EOFException(secret) to SafeApiFailure.EOF,
            IOException(secret) to SafeApiFailure.IO,
            IllegalArgumentException(secret) to SafeApiFailure.CLIENT_CONFIG,
            RuntimeException(secret) to SafeApiFailure.UNKNOWN
        )
        for ((exception, expected) in cases) {
            val error = safeApiException(exception)
            assertEquals(expected, error.safeFailure)
            assertEquals(expected.label, historyFailureCategory(error))
            val log = historyFailureDiagnostic(secret, Instant.EPOCH, error)
            assertTrue(log.startsWith("stage=unknown "))
            for (text in listOf(log, error.toString())) {
                assertFalse(text.contains(secret)); assertFalse(text.contains("secret.invalid"))
                assertFalse(text.contains("access_token")); assertFalse(text.contains("VIN"))
            }
        }
    }
    @Test fun specificWrappedCauseWinsAndMessageCannotSpoofClassification() {
        assertEquals(SafeApiFailure.JSON_DATA, safeApiException(IllegalArgumentException(secret, JsonDataException(secret))).safeFailure)
        assertEquals(SafeApiFailure.IO, safeApiException(IOException("JsonReader timed out SSLHandshakeException")).safeFailure)
    }
    @Test fun cancellationIsAlwaysRethrownIncludingWrapper() {
        val cancel = CancellationException(secret)
        for (error in listOf(cancel, RuntimeException(secret, cancel))) {
            try { safeApiException(error); fail("cancellation swallowed") }
            catch (actual: CancellationException) { assertSame(cancel, actual) }
        }
    }
    @Test fun typedErrorsKeepHttpAndInvalidPayloadDistinctionsWithoutBody() {
        assertEquals("authentication", historyFailureCategory(ApiResult.Error(secret, 401)))
        assertEquals("authorization", historyFailureCategory(ApiResult.Error(secret, 403)))
        assertEquals("invalid_response", historyFailureCategory(ApiResult.Error(secret, 200, kind = ApiErrorKind.INVALID_RESPONSE)))
        assertEquals("network_unknown", historyFailureCategory(ApiResult.Error(secret, kind = ApiErrorKind.NETWORK)))
    }
}
