package com.matelink.data

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskCCodeQualityRedTest {
    @Test
    fun vehicleContextAllocationRequiresSynchronousDurableCommit() {
        val source = File("src/main/java/com/matelink/data/local/VehicleContextStore.kt").readText()

        assertTrue(source.contains("commit()"))
        assertFalse(source.contains(".apply()"))
    }

    @Test
    fun ordinaryVehicleResolutionDoesNotBindAnUnknownUpgradeArchive() {
        val source = File("src/main/java/com/matelink/data/local/VehicleContextRepository.kt").readText()

        assertFalse(source.contains("bindUnknownUpgradeArchive"))
        assertTrue(source.contains("recordExplicitUpgradeOrigin"))
    }

    @Test
    fun chargingDisplayAndCancelUseAResolvedHistoryNamespaceNotificationId() {
        val source = File("src/main/java/com/matelink/notification/ChargingNotificationManager.kt").readText()

        assertTrue(source.contains("chargingNotificationId(localHistoryCarId)"))
    }

    @Test
    fun historyCacheCarriesExplicitNullableApiEvidence() {
        val sources = listOf(
            "src/main/java/com/matelink/data/local/entity/DriveSummary.kt",
            "src/main/java/com/matelink/data/local/entity/ChargeSummary.kt",
            "src/main/java/com/matelink/data/local/dao/DriveSummaryDao.kt",
            "src/main/java/com/matelink/data/local/dao/ChargeSummaryDao.kt",
            "src/main/java/com/matelink/data/repository/UnifiedHistoryRepository.kt",
            "src/main/java/com/matelink/domain/analytics/HistorySummaryMapper.kt"
        ).joinToString("\n") { File(it).readText() }

        assertTrue(sources.contains("apiEvidence"))
        assertTrue(sources.contains("val merged = mergeDrives"))
        assertTrue(sources.contains("val merged = mergeCharges"))
    }

    @Test
    fun tpmsStateClaimLifecycleUsesTheCapturedHistoryNamespace() {
        val sources = listOf(
            "src/main/java/com/matelink/data/sync/TpmsPressureWorker.kt",
            "src/main/java/com/matelink/data/repository/TpmsStateRepository.kt"
        ).joinToString("\n") { File(it).readText() }

        assertTrue(sources.contains("claimStateChangeForHistoryCarId"))
        assertTrue(sources.contains("releaseStateChangeForHistoryCarId"))
    }
}
