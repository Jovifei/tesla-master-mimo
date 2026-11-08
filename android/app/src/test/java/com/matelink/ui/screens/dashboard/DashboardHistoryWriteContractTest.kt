package com.matelink.ui.screens.dashboard

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardHistoryWriteContractTest {
    private fun source() = File(
        "src/main/java/com/matelink/ui/screens/dashboard/DashboardViewModel.kt"
    ).readText()

    @Test
    fun dashboardPollingDoesNotWriteSnapshotHistory() {
        val source = source()

        assertFalse(source.contains("recordSnapshot("))
        assertFalse(source.contains("SnapshotTripEngine"))
        assertFalse(source.contains("SnapshotChargeEngine"))
    }

    @Test
    fun manualRefreshReplacesARetryingHistoryWorker() {
        val trigger = source()
            .substringAfter("private fun triggerDataSync()")
            .substringBefore("private suspend fun loadCachedAddress")

        assertTrue(trigger.contains("ExistingWorkPolicy.REPLACE"))
        assertFalse(trigger.contains("ExistingWorkPolicy.KEEP"))
    }
}
