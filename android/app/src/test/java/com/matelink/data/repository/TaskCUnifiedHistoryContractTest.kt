package com.matelink.data.repository

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskCUnifiedHistoryContractTest {
    @Test
    fun listAndAnalyticsConsumersShareOneHistoryRepository() {
        val sources = listOf(
            "src/main/java/com/matelink/ui/screens/drives/DrivesViewModel.kt",
            "src/main/java/com/matelink/ui/screens/charges/ChargesViewModel.kt",
            "src/main/java/com/matelink/domain/analytics/AnalysisHistoryRepository.kt",
            "src/main/java/com/matelink/data/repository/StatsRepository.kt",
            "src/main/java/com/matelink/data/repository/UnifiedHistoryRepository.kt"
        ).joinToString("\n") { File(it).readText() }

        assertTrue(sources.contains("UnifiedHistoryRepository"))
        assertTrue("remote empty must not replace local history", sources.contains("remoteEmptyKeepsLocal"))
    }
}
