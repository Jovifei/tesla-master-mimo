package com.matelink.data.api

/**
 * Validates the fixed JourVolt cloud origin used by authentication and token
 * refresh. Cloud credentials must never be sent over local or public HTTP.
 */
internal fun validatedJourVoltApiBaseUrl(
    raw: String,
    allowLocalHttp: Boolean = false
): String? {
    return when (val validation = UrlSecurity.normalizeAndValidate(raw)) {
        is UrlSecurity.Validation.Valid -> {
            val verdict = UrlSecurity.classify(validation.normalizedUrl)
            if (verdict == UrlSecurity.Verdict.Https ||
                (allowLocalHttp && verdict == UrlSecurity.Verdict.LocalHttp)
            ) {
                "${validation.normalizedUrl}/"
            } else {
                null
            }
        }

        is UrlSecurity.Validation.Invalid -> null
    }
}
