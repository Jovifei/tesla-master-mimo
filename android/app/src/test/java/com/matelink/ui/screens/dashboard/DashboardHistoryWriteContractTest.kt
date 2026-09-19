package com.matelink.ui.screens.dashboard

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Test

class DashboardHistoryWriteContractTest {
    @Test
    fun dashboardPollingDoesNotWriteSnapshotHistory() {
        val source = File(
            "src/main/java/com/matelink/ui/screens/dashboard/DashboardViewModel.kt"
        ).readText()

        assertFalse(source.contains("recordSnapshot("))
        assertFalse(source.contains("SnapshotTripEngine"))
        assertFalse(source.contains("SnapshotChargeEngine"))
    }
}
