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
            assertTrue(path, source.contains("batteryDetails = localSummary.$mapper().batteryDetails"))
            assertFalse(path, source.contains("startBatteryLevel = localSummary.startBatteryLevel"))
            assertFalse(path, source.contains("endBatteryLevel = localSummary.endBatteryLevel"))
        }
    }
}
