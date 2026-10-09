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
import java.security.cert.CertPathBuilderException
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import java.security.cert.CertificateExpiredException
import java.security.cert.CertificateNotYetValidException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLProtocolException

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
    @Test fun tlsDiagnosticDiscriminatesCausesByTypeWithoutGuessingTrustAnchorOrHost() {
        val cases = listOf(
            SSLHandshakeException(secret) to SafeTlsCause.HANDSHAKE_UNSPECIFIED,
            SSLException(secret) to SafeTlsCause.TLS_UNSPECIFIED,
            SSLProtocolException(secret) to SafeTlsCause.PROTOCOL,
            SSLPeerUnverifiedException(secret) to SafeTlsCause.PEER_UNVERIFIED,
            SSLHandshakeException(secret).apply {
                initCause(CertificateExpiredException(secret))
            } to SafeTlsCause.CERT_EXPIRED,
            SSLHandshakeException(secret).apply {
                initCause(CertificateNotYetValidException(secret))
            } to SafeTlsCause.CERT_NOT_YET_VALID,
            SSLHandshakeException(secret).apply {
                initCause(CertPathValidatorException(secret))
            } to SafeTlsCause.CERT_PATH_VALIDATION,
            SSLHandshakeException(secret).apply {
                initCause(CertPathBuilderException(secret))
            } to SafeTlsCause.CERT_PATH_VALIDATION,
            SSLHandshakeException(secret).apply {
                initCause(CertificateException(secret))
            } to SafeTlsCause.CERT_VALIDATION_OTHER
        )
        for ((exception, expected) in cases) {
            val wrapped = IOException(secret, exception)
            val error = safeApiException(wrapped)
            assertEquals(SafeApiFailure.TLS, error.safeFailure)
            assertEquals(ApiErrorKind.NETWORK, error.kind)
            assertEquals(expected, error.safeTlsCause)
            assertEquals("tls", historyFailureCategory(error))
            val log = historyFailureDiagnostic("history_context", Instant.EPOCH, error)
            assertTrue(log.contains("category=tls tls_cause=${expected.label}"))
            assertEquals("Secure connection could not be established", error.message)
            for (output in listOf(error.toString(), log)) {
                listOf(secret, "VIN", "access_token", "secret.invalid", "private", "id=123").forEach {
                    assertFalse(output.contains(it))
                }
            }
        }
    }

    @Test fun tlsTextCannotSpoofSpecificCertificateCause() {
        val error = safeApiException(SSLHandshakeException(
            "PKIX validation expired trust anchor hostname " + secret))
        assertEquals(SafeTlsCause.HANDSHAKE_UNSPECIFIED, error.safeTlsCause)
        assertFalse(historyFailureDiagnostic("history_context", Instant.EPOCH, error).contains("cert"))
    }

    @Test fun unrelatedNetworkFailuresNeverAcquireTlsDetails() {
        for (exception in listOf(IOException(secret), UnknownHostException(secret),
                SocketTimeoutException(secret), IllegalArgumentException(secret))) {
            val error = safeApiException(exception)
            assertNull(error.safeTlsCause)
            assertFalse(historyFailureDiagnostic("history_context", Instant.EPOCH, error).contains("tls_cause="))
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
