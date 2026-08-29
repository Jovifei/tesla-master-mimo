package com.matelink.ui.navigation

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PearlDriveVisualContractTest {
    @Test
    fun bottomNavigationIconsDescribeTheirDestination() {
        assertEquals(TopLevelIconKey.VEHICLE, TopLevelDestination.Dashboard.iconKey)
        assertEquals(TopLevelIconKey.ROUTE, TopLevelDestination.Drives.iconKey)
        assertEquals(TopLevelIconKey.ENERGY, TopLevelDestination.Charges.iconKey)
        assertEquals(TopLevelIconKey.ANALYSIS, TopLevelDestination.More.iconKey)
    }

    @Test
    fun selectedNavigationMotionStaysSubtleAndShort() {
        assertEquals(1f, PearlDriveMotion.iconScale(false), 0f)
        assertEquals(1.06f, PearlDriveMotion.iconScale(true), 0f)
        assertEquals(180, PearlDriveMotion.NavigationSelectionDurationMillis)
    }

    @Test
    fun telemetryPanelUsesSoftLayeringInsteadOfAccentGradientBorder() {
        val source = File("src/main/java/com/matelink/ui/components/TelemetryPanel.kt").readText()

        assertTrue(source.contains("shadowElevation = 1.dp"))
        assertTrue(source.contains("border = BorderStroke("))
        assertTrue(source.contains("0.5.dp"))
        assertFalse(source.contains("accent.copy(alpha = 0.34f)"))
    }

    @Test
    fun dashboardMotionIsFiniteAndOnlyAttachedToUserRefreshOrValidValues() {
        val source = File("src/main/java/com/matelink/ui/screens/dashboard/DashboardScreen.kt").readText()

        assertTrue(source.contains("PearlDriveMotion.RefreshDurationMillis"))
        assertTrue(source.contains("PearlDriveMotion.ValueTransitionDurationMillis"))
        assertTrue(source.contains("AnimatedContent"))
        assertFalse(source.contains("repeatable("))
    }

    @Test
    fun dashboardValueTransitionOnlyRunsWhenTheFirstValidValueArrives() {
        val source = File("src/main/java/com/matelink/ui/screens/dashboard/DashboardScreen.kt").readText()

        assertTrue(source.contains("initialState == \"--\""))
        assertTrue(source.contains("targetState != \"--\""))
    }
}
