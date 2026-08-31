package com.matelink.data

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskCReviewRedTest {
    @Test
    fun reverseLookupMustBindCloudAccountAndVehicleUid() {
        val source = File("src/main/java/com/matelink/data/local/VehicleContextStore.kt").readText()
        assertTrue(source.contains("stableIdentity") && source.contains("identityKey"))
        assertTrue("reverse lookup must be derived from the stable cloud identity", !source.contains("remoteKey("))
    }

    @Test
    fun cloudResolutionMustFailClosedInsteadOfReturningPositiveRemoteId() {
        val source = listOf(
            File("src/main/java/com/matelink/data/repository/StatsRepository.kt"),
            File("src/main/java/com/matelink/data/local/VehicleContextRepository.kt"),
            File("src/main/java/com/matelink/data/local/VehicleContext.kt")
        ).joinToString("\n") { it.readText() }
        assertTrue(!source.contains("?: return remoteApiCarId"))
        assertTrue(source.contains("HistoryIdentityUnavailableException"))
    }

    @Test
    fun legacyMigrationNeedsPersistedFingerprintAndExplicitUpgradeMarker() {
        val sources = listOf(
            File("src/main/java/com/matelink/data/local/entity/LegacyHistoryArchive.kt"),
            File("src/main/java/com/matelink/data/local/dao/LegacyHistoryArchiveDao.kt"),
            File("src/main/java/com/matelink/data/repository/LegacyHistoryMigrationRepository.kt")
        ).joinToString("\n") { if (it.exists()) it.readText() else "" }
        assertTrue(sources.contains("vehicleFingerprint"))
        assertTrue(sources.contains("EXPLICIT_UPGRADE_ARCHIVE"))
        assertTrue(sources.contains("upgradeOrigin"))
    }

    @Test
    fun tpmsAndNotificationWorkersResolveLocalHistoryKeysBeforePersistence() {
        val files = listOf(
            "src/main/java/com/matelink/data/repository/TpmsHistoryRepository.kt",
            "src/main/java/com/matelink/data/repository/TpmsStateRepository.kt",
            "src/main/java/com/matelink/data/local/TpmsCustomAlertStateStore.kt",
            "src/main/java/com/matelink/data/sync/TpmsPressureWorker.kt",
            "src/main/java/com/matelink/data/sync/ChargingNotificationWorker.kt",
            "src/main/java/com/matelink/service/ChargingMonitorService.kt"
        ).joinToString("\n") { File(it).readText() }
        assertTrue(files.contains("VehicleContextRepository"))
        assertTrue(files.contains("localHistoryCarId"))
    }

    @Test
    fun everyHistoryJoinMustBindTheVehicleKey() {
        val daoSources = listOf(
            "src/main/java/com/matelink/data/local/dao/DriveSummaryDao.kt",
            "src/main/java/com/matelink/data/local/dao/ChargeSummaryDao.kt",
            "src/main/java/com/matelink/data/local/dao/AggregateDao.kt"
        ).joinToString("\n") { File(it).readText() }
        assertTrue(!Regex("ON (?:[a-z]+\\.)?(?:driveId|chargeId) = (?:[a-z]+\\.)?(?:driveId|chargeId)").containsMatchIn(daoSources))
    }

    @Test
    fun unifiedHistoryReadsLocalRowsWithinTheRequestedRange() {
        val source = File("src/main/java/com/matelink/data/repository/UnifiedHistoryRepository.kt").readText()
        assertTrue(source.contains("getDrivesInRange"))
        assertTrue(source.contains("getChargesInRange"))
    }

    @Test
    fun analysisCacheUsesStableHistoryIdentity() {
        val source = File("src/main/java/com/matelink/domain/analytics/AnalysisHistoryRepository.kt").readText()
        assertTrue(source.contains("stableIdentity"))
        assertTrue(!source.contains("cache.put(carId"))
    }

    @Test
    fun migrationTestsValidateLatestSchemaAndExerciseRepositoryTwice() {
        val source = File("src/androidTest/java/com/matelink/data/local/StatsDatabaseMigrationTest.kt").readText()
        assertTrue(source.contains("assertEquals(19"))
        assertTrue(source.contains("LegacyHistoryMigrationRepository"))
        assertTrue(source.contains("migrate("))
        assertTrue(source.contains("secondMigration"))
    }
}
