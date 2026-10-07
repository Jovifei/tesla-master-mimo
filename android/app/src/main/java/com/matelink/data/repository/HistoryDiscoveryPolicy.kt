package com.matelink.data.repository

/** Safe diagnostic categories: never include response bodies, URLs, tokens or vehicle identities. */
internal fun historyFailureCategory(code: Int?): String = when (code) {
    401 -> "authentication"
    403 -> "authorization"
    404 -> "not_found"
    429 -> "rate_limited"
    in 500..599 -> "server"
    null -> "transport_or_decode"
    else -> "http_other"
}

internal fun historyFailureCategory(error: ApiResult.Error): String =
    error.safeFailure?.label ?: when {
        error.code != null && error.code !in 200..299 -> historyFailureCategory(error.code)
        error.kind == ApiErrorKind.INVALID_RESPONSE -> "invalid_response"
        error.kind == ApiErrorKind.CONFIGURATION -> "client_config"
        error.kind == ApiErrorKind.NETWORK -> "network_unknown"
        else -> "unknown"
    }

internal fun historyFailureDiagnostic(stage: String, requestedAt: java.time.Instant, error: ApiResult.Error): String {
    val safeStage = stage.takeIf { it in setOf("cars", "history_context", "drives", "charges", "drive_detail", "charge_detail") } ?: "unknown"
    return "stage=$safeStage requested_at=$requestedAt http=${error.code ?: "none"} category=${historyFailureCategory(error)}"
}
