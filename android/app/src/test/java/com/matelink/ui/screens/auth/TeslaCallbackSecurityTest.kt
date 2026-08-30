package com.matelink.ui.screens.auth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TeslaCallbackSecurityTest {
    private val host = "auth.jourvolt.com"
    private val apiHost = "api.jourvolt.com"
    private val apiRedirect =
        "https%3A%2F%2Fapi.jourvolt.com%2Fv1%2Fauth%2Ftesla%2Fcallback"

    @Test
    fun acceptsOnlyTheConfiguredHttpsCallback() {
        assertTrue(
            isTrustedTeslaCallback("https", host, "/oauth/callback", host)
        )
        assertTrue(
            isTrustedTeslaCallback("intent", host, "/oauth/callback", host)
        )
    }

    @Test
    fun rejectsWrongSchemeHostAndPath() {
        assertFalse(isTrustedTeslaCallback("http", host, "/oauth/callback", host))
        assertFalse(isTrustedTeslaCallback("https", "evil.example", "/oauth/callback", host))
        assertFalse(isTrustedTeslaCallback("https", host, "/other", host))
        assertFalse(isTrustedTeslaCallback(null, host, "/oauth/callback", host))
    }

    @Test
    fun acceptsOnlyOfficialTeslaAuthorizationEndpoints() {
        val query = "client_id=client&redirect_uri=$apiRedirect" +
            "&response_type=code&scope=openid%20offline_access%20vehicle_device_data"
        assertTrue(isTrustedTeslaAuthorizationUrl("https://auth.tesla.cn/oauth2/v3/authorize?$query", apiHost))
        assertTrue(isTrustedTeslaAuthorizationUrl("https://auth.tesla.com/oauth2/v3/authorize?$query", apiHost))
    }

    @Test
    fun rejectsAuthorizationUrlWithUntrustedOriginOrMissingMinimumScope() {
        val query = "client_id=client&redirect_uri=$apiRedirect" +
            "&response_type=code&scope=vehicle_device_data"
        assertFalse(isTrustedTeslaAuthorizationUrl("http://auth.tesla.cn/oauth2/v3/authorize?$query", apiHost))
        assertFalse(isTrustedTeslaAuthorizationUrl("https://auth.tesla.cn.evil/oauth2/v3/authorize?$query", apiHost))
        assertFalse(isTrustedTeslaAuthorizationUrl("https://auth.tesla.cn/wrong?$query", apiHost))
        assertFalse(isTrustedTeslaAuthorizationUrl("https://auth.tesla.cn/oauth2/v3/authorize?$query", apiHost))
    }

    @Test
    fun rejectsAuthorizationRedirectOutsideJourVoltApiCallback() {
        val query = "client_id=client&redirect_uri=https%3A%2F%2Fevil.example%2Foauth%2Fcallback" +
            "&response_type=code&scope=openid%20offline_access"
        assertFalse(
            isTrustedTeslaAuthorizationUrl(
                "https://auth.tesla.cn/oauth2/v3/authorize?$query",
                apiHost
            )
        )
        // The App Link hop (/oauth/callback on the auth host) is the SECOND hop,
        // issued by the JourVolt API after ticket issuance; Tesla must never be
        // pointed there directly.
        val appLinkQuery = "client_id=client&redirect_uri=https%3A%2F%2Fauth.jourvolt.com%2Foauth%2Fcallback" +
            "&response_type=code&scope=openid%20offline_access"
        assertFalse(
            isTrustedTeslaAuthorizationUrl(
                "https://auth.tesla.cn/oauth2/v3/authorize?$appLinkQuery",
                apiHost
            )
        )
    }

    @Test
    fun acceptsOnlyOfficialTeslaConsentManagementPages() {
        assertTrue(
            isTrustedTeslaConsentRevokeUrl(
                "https://auth.tesla.cn/user/revoke/consent?revoke_client_id=client"
            )
        )
        assertTrue(
            isTrustedTeslaConsentRevokeUrl(
                "https://auth.tesla.com/user/revoke/consent?revoke_client_id=client"
            )
        )
        assertFalse(
            isTrustedTeslaConsentRevokeUrl(
                "https://evil.example/user/revoke/consent?revoke_client_id=client"
            )
        )
        assertFalse(
            isTrustedTeslaConsentRevokeUrl(
                "https://auth.tesla.cn/user/revoke/consent"
            )
        )
    }
}
