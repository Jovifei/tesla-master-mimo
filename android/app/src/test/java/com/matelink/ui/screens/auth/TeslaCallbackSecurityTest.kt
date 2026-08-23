package com.matelink.ui.screens.auth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TeslaCallbackSecurityTest {
    private val host = "auth.jourvolt.com"

    @Test
    fun acceptsOnlyTheConfiguredHttpsCallback() {
        assertTrue(
            isTrustedTeslaCallback("https", host, "/oauth/callback", host)
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
        val query = "client_id=client&redirect_uri=https%3A%2F%2Fauth.jourvolt.com%2Foauth%2Fcallback" +
            "&response_type=code&scope=openid%20offline_access%20vehicle_device_data"
        assertTrue(isTrustedTeslaAuthorizationUrl("https://auth.tesla.cn/oauth2/v3/authorize?$query", host))
        assertTrue(isTrustedTeslaAuthorizationUrl("https://auth.tesla.com/oauth2/v3/authorize?$query", host))
    }

    @Test
    fun rejectsAuthorizationUrlWithUntrustedOriginOrMissingMinimumScope() {
        val query = "client_id=client&redirect_uri=https%3A%2F%2Fauth.jourvolt.com%2Foauth%2Fcallback" +
            "&response_type=code&scope=vehicle_device_data"
        assertFalse(isTrustedTeslaAuthorizationUrl("http://auth.tesla.cn/oauth2/v3/authorize?$query", host))
        assertFalse(isTrustedTeslaAuthorizationUrl("https://auth.tesla.cn.evil/oauth2/v3/authorize?$query", host))
        assertFalse(isTrustedTeslaAuthorizationUrl("https://auth.tesla.cn/wrong?$query", host))
        assertFalse(isTrustedTeslaAuthorizationUrl("https://auth.tesla.cn/oauth2/v3/authorize?$query", host))
    }

    @Test
    fun rejectsAuthorizationRedirectOutsideJourVoltAppLink() {
        val query = "client_id=client&redirect_uri=https%3A%2F%2Fevil.example%2Foauth%2Fcallback" +
            "&response_type=code&scope=openid%20offline_access"
        assertFalse(
            isTrustedTeslaAuthorizationUrl(
                "https://auth.tesla.cn/oauth2/v3/authorize?$query",
                host
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
