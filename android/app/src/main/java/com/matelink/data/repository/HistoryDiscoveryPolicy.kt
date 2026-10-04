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
