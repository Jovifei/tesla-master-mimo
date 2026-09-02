package com.matelink.ui.navigation

import com.matelink.data.local.ConnectionMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionExpiryNavigationTest {
    @Test
    fun expiredCloudSessionRedirectsFromDashboard() {
        assertTrue(
            shouldRedirectToTeslaLogin(
                startDestination = Screen.Dashboard,
                connectionMode = ConnectionMode.TESLA_CLOUD,
                isAuthenticated = false,
                currentRoute = "Dashboard"
            )
        )
    }

    @Test
    fun selfHostedModeDoesNotRedirectWhenNoJourVoltSessionExists() {
        assertFalse(
            shouldRedirectToTeslaLogin(
                startDestination = Screen.Dashboard,
                connectionMode = ConnectionMode.SELF_HOSTED,
                isAuthenticated = false,
                currentRoute = "Dashboard"
            )
        )
    }

    @Test
    fun loginRouteDoesNotRedirectAgain() {
        assertFalse(
            shouldRedirectToTeslaLogin(
                startDestination = Screen.Dashboard,
                connectionMode = ConnectionMode.TESLA_CLOUD,
                isAuthenticated = false,
                currentRoute = "TeslaLogin"
            )
        )
    }

    @Test
    fun reauthorizationLogoutWindowDoesNotClearSettingsRoute() {
        assertFalse(
            shouldRedirectToTeslaLogin(
                startDestination = Screen.Dashboard,
                connectionMode = ConnectionMode.TESLA_CLOUD,
                isAuthenticated = false,
                currentRoute = "Settings",
                suppressAutoRedirect = true
            )
        )
    }
}
