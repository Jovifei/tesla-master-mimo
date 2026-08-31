package com.matelink.ui.screens.readiness

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskCMigrationUiContractTest {
    @Test
    fun readinessOffersOnlyAnExplicitVerifiedMigrationAction() {
        val screen = File("src/main/java/com/matelink/ui/screens/readiness/DataReadinessScreen.kt").readText()
        val viewModel = File("src/main/java/com/matelink/ui/screens/readiness/DataReadinessViewModel.kt").readText()
        val strings = File("src/main/res/values-zh/strings.xml").readText()

        assertTrue(screen.contains("data_readiness_migrate_legacy"))
        assertTrue(strings.contains("验证并迁移本机历史"))
        assertTrue(screen.contains("AlertDialog"))
        assertTrue(viewModel.contains("migrateLegacyHistory"))
        assertTrue(viewModel.contains("eligible"))
        assertTrue(screen.contains("data_readiness_bind_legacy_archive"))
        assertTrue(viewModel.contains("recordExplicitUpgradeOrigin"))
    }
}
