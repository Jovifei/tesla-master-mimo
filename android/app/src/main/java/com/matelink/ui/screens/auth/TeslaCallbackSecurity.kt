package com.matelink.ui.screens.auth

import android.net.Uri
import java.net.URLDecoder
import java.util.Locale

/**
 * Re-checks the verified App Link at the application boundary before a ticket
 * is exchanged. Android intent filters are not the only way another app can
 * deliver an explicit intent to an exported activity.
 */
internal fun isTeslaOAuthCallbackPath(path: String?): Boolean = path == "/oauth/callback"

internal fun isTrustedTeslaCallback(uri: Uri?, expectedHost: String): Boolean {
    return isTrustedTeslaCallback(uri?.scheme, uri?.host, uri?.path, expectedHost)
}

internal fun isTrustedTeslaCallback(
    scheme: String?,
    host: String?,
    path: String?,
    expectedHost: String
): Boolean {
    if (scheme.isNullOrBlank() || host.isNullOrBlank() || expectedHost.isBlank()) return false
    val trustedScheme = scheme.equals("https", ignoreCase = true) ||
        scheme.equals("intent", ignoreCase = true)
    return trustedScheme &&
        host.equals(expectedHost, ignoreCase = true) &&
        isTeslaOAuthCallbackPath(path)
}

/**
 * The API is trusted to construct the authorization request, but the app must
 * still fail closed before handing a returned URL to a browser.
 *
 * Tesla hands the authorization code back to the JourVolt API callback
 * (`https://<api host>/v1/auth/tesla/callback`); the server validates state,
 * issues a one-time ticket, and hops to the App Link (`/oauth/callback`)
 * which opens this app. [expectedRedirectHost] is therefore the JourVolt
 * API host (from [com.matelink.BuildConfig.JOURVOLT_API_BASE_URL]).
 */
internal fun isTrustedTeslaAuthorizationUrl(
    raw: String,
    expectedRedirectHost: String
): Boolean {
    val uri = runCatching { java.net.URI(raw) }.getOrNull() ?: return false
    val normalizedRedirectHost = expectedRedirectHost
        .trim()
        .removePrefix("https://")
        .removeSuffix("/")
        .lowercase(Locale.ROOT)
        .takeIf { it.isNotBlank() && '/' !in it && ':' !in it }
        ?: return false
    val host = uri.host?.lowercase(Locale.ROOT) ?: return false
    val query = runCatching {
        uri.rawQuery.orEmpty()
            .split('&')
            .asSequence()
            .mapNotNull { parameter ->
                val key = parameter.substringBefore('=')
                if (key.isBlank()) return@mapNotNull null
                key to URLDecoder.decode(parameter.substringAfter('=', ""), "UTF-8")
            }
            .toMap()
    }.getOrNull() ?: return false
    val scope = query["scope"]
        ?.split(Regex("\\s+"))
        ?.filter(String::isNotBlank)
        ?.toSet()
        ?: emptySet()
    val redirect = runCatching { java.net.URI(query["redirect_uri"].orEmpty()) }.getOrNull()
    val redirectHost = redirect?.host?.lowercase(Locale.ROOT)
    return uri.scheme.equals("https", ignoreCase = true) &&
        host in setOf("auth.tesla.cn", "auth.tesla.com") &&
        (uri.port == -1 || uri.port == 443) &&
        uri.path == "/oauth2/v3/authorize" &&
        uri.userInfo == null &&
        uri.fragment == null &&
        !query["client_id"].isNullOrBlank() &&
        !query["redirect_uri"].isNullOrBlank() &&
        redirect != null &&
        redirect.scheme.equals("https", ignoreCase = true) &&
        redirectHost == normalizedRedirectHost &&
        (redirect.port == -1 || redirect.port == 443) &&
        redirect.path == "/v1/auth/tesla/callback" &&
        redirect.userInfo == null &&
        redirect.query == null &&
        redirect.fragment == null &&
        query["response_type"] == "code" &&
        scope.containsAll(setOf("openid", "offline_access"))
}
