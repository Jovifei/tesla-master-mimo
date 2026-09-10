package com.matelink.ui.screens.auth

import com.matelink.data.api.models.TelemetryPairingStatus
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TeslaPostLoginOnboardingContractTest {
    @Test
    fun onlyPairingRequiredBlocksPostLoginDashboard() {
        assertTrue(
            shouldSurfaceTeslaVirtualKey(
                TelemetryPairingStatus(status = "pairing_required", configSynced = false)
            )
        )
        assertFalse(
            shouldSurfaceTeslaVirtualKey(
                TelemetryPairingStatus(status = "waiting_vehicle", configSynced = false)
            )
        )
        assertFalse(
            shouldSurfaceTeslaVirtualKey(
                TelemetryPairingStatus(status = "available", configSynced = true)
            )
        )
    }

    @Test
    fun loginScreenUsesOnboardingStateBeforeCallingSuccess() {
        val source = File("src/main/java/com/matelink/ui/screens/auth/TeslaLoginScreen.kt").readText()

        assertTrue(source.contains("postLoginOnboarding"))
        assertTrue(source.contains("onTeslaPairingFlowResumed"))
        assertTrue(source.contains("tesla_onboarding_pairing_title"))
        assertTrue(source.contains("launchExternalIntentSafely"))
        assertTrue(source.contains("TeslaLoginOnboardingState.Pending"))
        assertTrue(
            File("src/main/java/com/matelink/ui/screens/auth/TeslaLoginViewModel.kt")
                .readText()
                .contains("pairing.configSynced == true")
        )
        assertTrue(source.contains("TeslaPermissionOnboardingPanel"))
    }

    @Test
    fun loginViewModelPreservesSessionWhenPairingIsCancelled() {
        val source = File("src/main/java/com/matelink/ui/screens/auth/TeslaLoginViewModel.kt").readText()
        val store = File("src/main/java/com/matelink/data/local/TeslaOnboardingStateStore.kt").readText()

        assertTrue(source.contains("fun cancelReauthorization()"))
        assertTrue(source.contains("fun continueAfterTeslaPairing()"))
        assertTrue(source.contains("fun continueAfterTeslaOnboarding()"))
        assertTrue(source.contains("restorePersistedOnboarding"))
        assertTrue(source.contains("snapshot.launchPending"))
        assertTrue(source.contains("sessionCommitLock"))
        assertTrue(source.contains("invalidateCurrentRequest()"))
        assertTrue(store.contains("launch_pending"))
        assertTrue(store.contains("retry_used"))
        assertTrue(
            File("src/main/java/com/matelink/ui/navigation/StartDestinationViewModel.kt")
                .readText()
                .contains("hasPendingOnboarding")
        )
        assertFalse(
            source.substringAfter("fun cancelReauthorization()").substringBefore("fun deleteAccount")
                .contains("sessionStore.clear()")
        )
    }
}
