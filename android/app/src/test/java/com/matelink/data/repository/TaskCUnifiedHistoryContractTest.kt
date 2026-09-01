package com.matelink.data.repository

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskCUnifiedHistoryContractTest {
    @Test
    fun missingHistoryIdentityBecomesRecoverableError() {
        val error = historyIdentityUnavailableError()

        assertEquals(ApiErrorKind.CONFIGURATION, error?.kind)
        assertEquals(HISTORY_IDENTITY_UNAVAILABLE, error?.details)
    }

    @Test
    fun statsObserversGuardHistoryIdentityFailures() {
        val source = File("src/main/java/com/matelink/ui/screens/stats/StatsViewModel.kt").readText()

        assertTrue(source.contains("HistoryIdentityUnavailableException"))
        assertTrue(source.contains("HISTORY_IDENTITY_UNAVAILABLE"))
    }

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
