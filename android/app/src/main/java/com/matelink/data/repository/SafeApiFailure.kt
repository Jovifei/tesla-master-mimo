package com.matelink.data.repository

import com.squareup.moshi.JsonDataException
import com.squareup.moshi.JsonEncodingException
import kotlinx.coroutines.CancellationException
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** Fixed diagnostic values only. Never retain throwable messages, paths, bodies or identities. */
enum class SafeApiFailure(val label: String) {
    DNS("dns"), CONNECT("connect"), TIMEOUT("timeout"), TLS("tls"),
    JSON_DATA("json_data"), JSON_ENCODING("json_encoding"), EOF("eof"),
    IO("io"), CLIENT_CONFIG("client_config"), UNKNOWN("unknown")
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
        SafeApiFailure.TLS -> "Server certificate cannot be verified"
        SafeApiFailure.JSON_DATA, SafeApiFailure.JSON_ENCODING -> "Server returned unrecognised data"
        SafeApiFailure.CLIENT_CONFIG -> "API client configuration unavailable"
        else -> "Server is temporarily unreachable"
    }
    return ApiResult.Error(message, kind = kind, safeFailure = failure)
}
