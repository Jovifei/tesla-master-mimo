package com.matelink.data.repository

import com.squareup.moshi.JsonDataException
import com.squareup.moshi.JsonEncodingException
import kotlinx.coroutines.CancellationException
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertPathBuilderException
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import java.security.cert.CertificateExpiredException
import java.security.cert.CertificateNotYetValidException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLProtocolException

/** Fixed diagnostic values only. Never retain throwable messages, paths, bodies or identities. */
enum class SafeApiFailure(val label: String) {
    DNS("dns"), CONNECT("connect"), TIMEOUT("timeout"), TLS("tls"),
    JSON_DATA("json_data"), JSON_ENCODING("json_encoding"), EOF("eof"),
    IO("io"), CLIENT_CONFIG("client_config"), UNKNOWN("unknown")
}

/**
 * Bounded, type-only TLS diagnostic. No exception text, URL, peer certificate,
 * host, proxy, stack trace, path or response body is retained.
 *
 * A desktop-valid public chain is not proof of this phone's failure cause.
 * In particular SSLHandshakeException alone does NOT establish a certificate
 * path or trust-anchor error. Codes are observations of Java exception types.
 */
enum class SafeTlsCause(val label: String) {
    CERT_EXPIRED("certificate_expired"),
    CERT_NOT_YET_VALID("certificate_not_yet_valid"),
    CERT_PATH_VALIDATION("certificate_path_validation"),
    CERT_VALIDATION_OTHER("certificate_validation_other"),
    PEER_UNVERIFIED("peer_unverified"),
    PROTOCOL("protocol"),
    HANDSHAKE_UNSPECIFIED("handshake_unspecified"),
    TLS_UNSPECIFIED("tls_unspecified")
}

private fun safeTlsCause(chain: List<Throwable>): SafeTlsCause? {
    if (chain.none { it is SSLException }) return null
    return when {
        chain.any { it is CertificateExpiredException } -> SafeTlsCause.CERT_EXPIRED
        chain.any { it is CertificateNotYetValidException } -> SafeTlsCause.CERT_NOT_YET_VALID
        chain.any { it is CertPathValidatorException || it is CertPathBuilderException } ->
            SafeTlsCause.CERT_PATH_VALIDATION
        chain.any { it is CertificateException } -> SafeTlsCause.CERT_VALIDATION_OTHER
        chain.any { it is SSLPeerUnverifiedException } -> SafeTlsCause.PEER_UNVERIFIED
        chain.any { it is SSLProtocolException } -> SafeTlsCause.PROTOCOL
        chain.any { it is SSLHandshakeException } -> SafeTlsCause.HANDSHAKE_UNSPECIFIED
        else -> SafeTlsCause.TLS_UNSPECIFIED
    }
}

internal fun safeApiException(error: Exception): ApiResult.Error {
    val chain = generateSequence<Throwable>(error) { it.cause }.take(8).toList()
    chain.filterIsInstance<CancellationException>().firstOrNull()?.let { throw it }
    // Prefer a specific nested parser/network failure to Retrofit's generic wrapper.
    val failure = chain.firstNotNullOfOrNull { cause -> when (cause) {
        is JsonDataException -> SafeApiFailure.JSON_DATA
        is JsonEncodingException -> SafeApiFailure.JSON_ENCODING
        is UnknownHostException -> SafeApiFailure.DNS
        is SocketTimeoutException -> SafeApiFailure.TIMEOUT
        is ConnectException -> SafeApiFailure.CONNECT
        is SSLException -> SafeApiFailure.TLS
        is EOFException -> SafeApiFailure.EOF
        else -> null
    } } ?: when {
        chain.any { it is IOException } -> SafeApiFailure.IO
        chain.any { it is IllegalArgumentException || it is IllegalStateException } -> SafeApiFailure.CLIENT_CONFIG
        else -> SafeApiFailure.UNKNOWN
    }
    val kind = when (failure) {
        SafeApiFailure.JSON_DATA, SafeApiFailure.JSON_ENCODING -> ApiErrorKind.INVALID_RESPONSE
        SafeApiFailure.CLIENT_CONFIG -> ApiErrorKind.CONFIGURATION
        SafeApiFailure.UNKNOWN -> ApiErrorKind.UNKNOWN
        else -> ApiErrorKind.NETWORK
    }
    val message = when (failure) {
        SafeApiFailure.TIMEOUT -> "Connection timed out"
        SafeApiFailure.TLS -> "Secure connection could not be established"
        SafeApiFailure.JSON_DATA, SafeApiFailure.JSON_ENCODING -> "Server returned unrecognised data"
        SafeApiFailure.CLIENT_CONFIG -> "API client configuration unavailable"
        else -> "Server is temporarily unreachable"
    }
    return ApiResult.Error(message, kind = kind, safeFailure = failure,
        safeTlsCause = if (failure == SafeApiFailure.TLS) safeTlsCause(chain) else null)
}
