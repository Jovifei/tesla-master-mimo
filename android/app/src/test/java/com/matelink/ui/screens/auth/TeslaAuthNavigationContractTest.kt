package com.matelink.ui.screens.auth

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TeslaAuthNavigationContractTest {
    @Test
    fun officialAuthorizationUsesCustomTabAndMainAppLink() {
        val viewModel = source("ui/screens/auth/TeslaLoginViewModel.kt")
        val manifest = File("src/main/AndroidManifest.xml").readText()

        assertTrue(viewModel.contains("CustomTabsIntent.Builder()"))
        assertTrue(viewModel.contains("TeslaAuthExchangeRequest(ticket)"))
        assertTrue(viewModel.contains("isTrustedTeslaCallback"))
        assertTrue(viewModel.contains("consentStore.recordCurrent()"))
        assertTrue(viewModel.contains("termsVersion = consent.termsVersion"))
        assertTrue(viewModel.contains("privacyVersion = consent.privacyVersion"))
        assertTrue(viewModel.contains("allowLocalHttp = usesDebugMockBaseUrl"))
        assertTrue(viewModel.contains("BuildConfig.JOURVOLT_MOCK_BASE_URL"))
        assertTrue(File("src/main/java/com/matelink/ui/screens/auth/TeslaCallbackSecurity.kt").exists())
        assertTrue(manifest.contains("android:autoVerify=\"true\""))
        assertTrue(manifest.contains("android:scheme=\"https\""))
        assertTrue(manifest.contains("android:pathPrefix=\"/oauth/callback\""))
    }

    @Test
    fun debugMockSessionRefreshStaysOnMockApi() {
        val refresher = File(
            "src/main/java/com/matelink/data/local/JourVoltSessionRefresher.kt"
        ).readText()

        assertTrue(refresher.contains("BuildConfig.DEBUG && BuildConfig.JOURVOLT_MOCK_LOGIN"))
        assertTrue(refresher.contains("BuildConfig.JOURVOLT_MOCK_BASE_URL"))
        assertTrue(refresher.contains("BuildConfig.JOURVOLT_API_BASE_URL"))
    }

    @Test
    fun singlePackageKeepsOriginalDashboardAfterSessionExchange() {
        val navigation = source("ui/navigation/NavGraph.kt")
        val dashboard = source("ui/screens/dashboard/DashboardScreen.kt")
        val settings = source("ui/screens/settings/SettingsScreen.kt")
        val build = File("build.gradle.kts").readText()

        assertTrue(navigation.contains("navController.navigate(Screen.Dashboard)"))
        assertTrue(dashboard.contains("IconButton(onClick = onNavigateToSettings)"))
        assertTrue(settings.contains("TeslaAccountSection"))
        assertTrue(build.contains("applicationIdSuffix = \".test.mock\""))
        assertFalse(build.contains("com.jourvolt.app"))
        val oldConsumerDir = File("src/main/java/com/matelink/ui/screens/consumer")
        assertFalse(oldConsumerDir.exists() && !oldConsumerDir.listFiles().isNullOrEmpty())
    }

    @Test
    fun sessionAuthenticationReturnsToOriginalDashboardAndClearsLoginRoute() {
        val login = source("ui/screens/auth/TeslaLoginScreen.kt")
        val navigation = source("ui/navigation/NavGraph.kt")

        assertTrue(login.contains("LaunchedEffect(isAuthenticated)"))
        assertTrue(login.contains("if (isAuthenticated) onLoginSuccess()"))
        assertTrue(navigation.contains("popUpTo<Screen.TeslaLogin> { inclusive = true }"))
        assertTrue(navigation.contains("navController.navigate(Screen.Dashboard)"))
    }

    @Test
    fun runningActivityPropagatesASecondAppLinkIntentToCompose() {
        val activity = File("src/main/java/com/matelink/MainActivity.kt").readText()

        assertTrue(activity.contains("override fun onNewIntent(newIntent: Intent)"))
        assertTrue(activity.contains("setIntent(newIntent)"))
        assertTrue(activity.contains("currentIntent = newIntent"))
        assertTrue(activity.contains("MateLinkNavHost(intent = currentIntent)"))
    }

    @Test
    fun mockLoginIsIsolatedFromFormalReleaseSourceSet() {
        val mainMockEntry = File("src/main/java/com/matelink/ui/screens/auth/DebugMockLoginEntry.kt")
        val debugMockEntry = File("src/debug/java/com/matelink/ui/screens/auth/DebugMockLoginEntry.kt")
        val releaseMockEntry = File("src/release/java/com/matelink/ui/screens/auth/DebugMockLoginEntry.kt")
        val mainStrings = File("src/main/res/values/strings.xml").readText()
        val mainChineseStrings = File("src/main/res/values-zh/strings.xml").readText()

        assertFalse(mainMockEntry.exists())
        assertTrue(debugMockEntry.exists())
        assertTrue(releaseMockEntry.exists())
        assertFalse(mainStrings.contains("debug_mock_login"))
        assertFalse(mainChineseStrings.contains("debug_mock_login"))
    }

    @Test
    fun loginRequiresSeparatePublishedTermsAndPrivacyBeforeCloudAuthorization() {
        val login = source("ui/screens/auth/TeslaLoginScreen.kt")
        val api = source("ui/screens/auth/TeslaAuthApi.kt")

        assertTrue(login.contains("tesla_login_terms_consent"))
        assertTrue(login.contains("tesla_login_privacy_consent"))
        assertTrue(login.contains("PublicInfoLinks.Page.TERMS"))
        assertTrue(login.contains("PublicInfoLinks.Page.PRIVACY"))
        assertTrue(login.contains("legalDocumentsConfigured"))
        assertTrue(api.contains("X-JourVolt-Terms-Version"))
        assertTrue(api.contains("X-JourVolt-Privacy-Version"))
    }

    @Test
    fun accountDeletionOffersTeslaConsentRevocationPage() {
        val api = source("ui/screens/auth/TeslaAuthApi.kt")
        val viewModel = source("ui/screens/auth/TeslaLoginViewModel.kt")
        val account = source("ui/screens/auth/TeslaAccountSection.kt")

        assertTrue(api.contains("tesla_consent_revoke_url"))
        assertTrue(viewModel.contains("deletionResponse?.teslaConsentRevokeUrl"))
        assertTrue(account.contains("isTrustedTeslaConsentRevokeUrl"))
        assertTrue(account.contains("launchExternalIntentSafely"))
    }

    private fun source(relativePath: String): String =
        File("src/main/java/com/matelink/$relativePath").readText()
}
