package com.matelink.analytics

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryDetailSocFallbackContractTest {
    @Test
    fun offlineDetailsUseNullableEvidenceInsteadOfNonNullableRoomSocColumns() {
        listOf(
            "drives/DriveDetailViewModel.kt" to "toAnalysisDriveData",
            "charges/ChargeDetailViewModel.kt" to "toAnalysisChargeData"
        ).forEach { (path, mapper) ->
            val source = File("src/main/java/com/matelink/ui/screens/$path").readText()
            assertTrue(path, source.contains(mapper))
            // The drive fallback uses the canonical cached-detail mapper.
            if (path.startsWith("drives/")) {
                assertTrue(path, source.contains("asCachedDetail()"))
            } else {
                assertTrue(path, source.contains("batteryDetails = cached.batteryDetails"))
            }
            assertFalse(path, source.contains("startBatteryLevel = localSummary.startBatteryLevel"))
            assertFalse(path, source.contains("endBatteryLevel = localSummary.endBatteryLevel"))
        }
    }
}
