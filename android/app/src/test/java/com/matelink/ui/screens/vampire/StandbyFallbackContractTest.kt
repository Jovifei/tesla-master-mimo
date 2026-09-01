package com.matelink.ui.screens.vampire

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class StandbyFallbackContractTest {
    @Test
    fun cloudStandby404UsesLocalHistoryAndACollectingState() {
        val repository = File("src/main/java/com/matelink/data/repository/TeslamateRepository.kt").readText()
        val viewModel = File("src/main/java/com/matelink/ui/screens/vampire/VampireViewModel.kt").readText()

        assertTrue("404 must be handled as an unavailable optional endpoint", repository.contains("response.code() == 404"))
        assertTrue("standby must have a local-history fallback", viewModel.contains("buildLocalStandbyWindows"))
        assertTrue("fallback must use the unified vehicle history", viewModel.contains("UnifiedHistoryRepository"))
        assertTrue("fallback must preserve a non-error collecting state", viewModel.contains("NoDataReason.COLLECTING"))
    }

    @Test
    fun standbyFallbackDoesNotInventEnergyOrPower() {
        val derivation = File("src/main/java/com/matelink/domain/analytics/StandbyHistoryDerivation.kt").readText()

        assertTrue(derivation.contains("energyKwh = null"))
        assertTrue(derivation.contains("averagePowerW = null"))
        assertTrue(derivation.contains("charges.any"))
    }

    @Test
    fun standbyErrorsUseLocalizedCopyInsteadOfProviderText() {
        val screen = File("src/main/java/com/matelink/ui/screens/vampire/VampireScreen.kt").readText()

        assertTrue(screen.contains("vampire_data_unavailable"))
        assertTrue(screen.contains("vampire_auth_required"))
        assertTrue(!screen.contains("body = uiState.error"))
    }
}
