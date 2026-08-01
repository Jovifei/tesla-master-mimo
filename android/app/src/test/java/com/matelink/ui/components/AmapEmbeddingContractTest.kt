package com.matelink.ui.components

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AmapEmbeddingContractTest {

    @Test
    fun legacyMapWrappersDelegateToTheNativeAmapRenderer() {
        val wrappers = listOf(
            "AmapPointView.kt",
            "AmapRouteView.kt",
            "AmapComposeView.kt"
        )

        wrappers.forEach { fileName ->
            val source = source("ui/components/$fileName")
            assertFalse("$fileName still renders the legacy placeholder", source.contains("amap_legacy_preview_unavailable"))
            assertTrue("$fileName must render a native AMap view", source.contains("AmapNativeMapView"))
        }
    }

    @Test
    fun dashboardLocationEntryNavigatesToAmapPreview() {
        val source = source("ui/screens/dashboard/DashboardScreen.kt")

        assertTrue(source.contains("onNavigateToAmapPreview"))
        assertFalse(source.contains("onClick = { onNavigateToDrives(carId, exteriorColor) }"))
    }

    private fun source(relativePath: String): String =
        File("src/main/java/com/matelink/$relativePath").readText()
}
