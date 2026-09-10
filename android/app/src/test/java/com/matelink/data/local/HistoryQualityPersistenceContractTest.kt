package com.matelink.data.local

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryQualityPersistenceContractTest {
    @Test
    fun migrationAddsAuditableQualityWithoutDeletingHistory() {
        val source = File("src/main/java/com/matelink/data/local/StatsDatabase.kt").readText()
        val migration = source.substringAfter("val MIGRATION_19_20").substringBefore("private fun")

        assertTrue(source.contains("version = 20"))
        assertTrue(source.contains("MIGRATION_18_19, MIGRATION_19_20"))
        assertTrue(migration.contains("ADD COLUMN `qualityState` TEXT NOT NULL DEFAULT 'incomplete'"))
        assertTrue(migration.contains("ADD COLUMN `qualityReason` TEXT NOT NULL DEFAULT 'missing_api_evidence'"))
        assertTrue(migration.contains("SET `qualityState` = 'observed'"))
        assertTrue(migration.contains("SET `qualityState` = 'quarantined'"))
        assertTrue(migration.contains("snapshot_session"))
        assertTrue(migration.contains("inferred_soc_jump"))
        assertFalse(migration.contains("DELETE FROM"))
        assertFalse(migration.contains("DROP TABLE"))
    }

    @Test
    fun defaultListsHideQuarantineAndStatisticsRequireEligibleQuality() {
        val driveDao = File("src/main/java/com/matelink/data/local/dao/DriveSummaryDao.kt").readText()
        val chargeDao = File("src/main/java/com/matelink/data/local/dao/ChargeSummaryDao.kt").readText()

        listOf(driveDao, chargeDao).forEach { source ->
            assertTrue(source.contains("qualityState != 'quarantined'"))
            assertTrue(source.contains("qualityState IN ('observed', 'derived')"))
        }
        assertTrue(driveDao.contains("fun observeAll"))
        assertTrue(driveDao.contains("fun getAllChronological"))
        assertTrue(driveDao.contains("fun getDrivesInRange"))
        assertTrue(chargeDao.contains("fun observeAll"))
        assertTrue(chargeDao.contains("fun getAllForCar"))
        assertTrue(chargeDao.contains("fun getChargesInRange"))
    }

    @Test
    fun everyUserVisibleDriveQueryExcludesQuarantinedRows() {
        val source = File("src/main/java/com/matelink/data/local/dao/DriveSummaryDao.kt").readText()

        listOf(
            "get", "observeAll", "getAllChronological", "getLatestWithEndAddress",
            "count", "observeCount", "countInRange", "getDrivesInRange",
            "sumDistance", "sumDistanceInRange", "sumEnergyConsumed", "sumEnergyConsumedInRange",
            "avgEfficiency", "avgEfficiencyInRange", "maxSpeed", "maxSpeedInRange",
            "longestDrive", "longestDriveInRange", "fastestDrive", "fastestDriveInRange",
            "mostEfficientDrive", "mostEfficientDriveInRange", "leastEfficientDrive",
            "leastEfficientDriveInRange", "avgDuration", "firstDriveDate", "busiestDay",
            "busiestDayInRange", "countDrivingDays", "countDrivingDaysInRange",
            "mostDistanceDay", "mostDistanceDayInRange", "getUnprocessedDriveIds",
            "countUnprocessedDrives", "getYears", "getDrivesBetweenDates",
            "longestGapBetweenDrives", "longestGapBetweenDrivesInRange",
            "biggestBatteryDrainDrive", "biggestBatteryDrainDriveInRange",
            "getDistinctDrivingDays", "getDistinctDrivingDaysInRange", "getMonthlyAggregation"
        ).forEach { method -> assertQueryExcludesQuarantine(source, method) }
    }

    @Test
    fun everyUserVisibleChargeQueryExcludesQuarantinedRows() {
        val source = File("src/main/java/com/matelink/data/local/dao/ChargeSummaryDao.kt").readText()

        listOf(
            "get", "observeAll", "getAllForCar", "getMaxNonNegativeOdometer", "count",
            "observeCount", "countInRange", "getChargesInRange", "sumEnergyAdded",
            "sumEnergyAddedInRange", "sumCost", "countWithCost", "sumCostInRange",
            "countWithCostInRange", "avgCostPerKwh", "avgCostPerKwhInRange", "biggestCharge",
            "biggestChargeInRange", "mostExpensiveCharge", "mostExpensiveChargeInRange",
            "mostExpensivePerKwhCharge", "mostExpensivePerKwhChargeInRange", "avgDuration",
            "firstChargeDate", "getUnprocessedChargeIds", "countUnprocessedCharges", "getYears",
            "maxDistanceBetweenCharges", "maxDistanceBetweenChargesInRange",
            "longestGapBetweenCharges", "longestGapBetweenChargesInRange",
            "biggestBatteryGainCharge", "biggestBatteryGainChargeInRange", "getMonthlyAggregation"
        ).forEach { method -> assertQueryExcludesQuarantine(source, method) }
    }

    @Test
    fun statisticalQueriesExcludeIncompleteAliasRows() {
        val driveDao = File("src/main/java/com/matelink/data/local/dao/DriveSummaryDao.kt").readText()
        val chargeDao = File("src/main/java/com/matelink/data/local/dao/ChargeSummaryDao.kt").readText()
        listOf(
            "getDrivesBetweenDates", "longestGapBetweenDrives", "longestGapBetweenDrivesInRange",
            "getDistinctDrivingDays", "getDistinctDrivingDaysInRange"
        ).forEach { method -> assertQueryRequiresEligibleQuality(driveDao, method) }
        listOf(
            "maxDistanceBetweenCharges", "maxDistanceBetweenChargesInRange",
            "longestGapBetweenCharges", "longestGapBetweenChargesInRange"
        ).forEach { method -> assertQueryRequiresEligibleQuality(chargeDao, method) }
    }

    @Test
    fun statsRecommendationsFilterIncompleteHistoryBeforeBuildingEvidence() {
        val source = File("src/main/java/com/matelink/data/repository/StatsRepository.kt").readText()
        assertTrue(Regex("analysisDrives = drives\\.map[\\s\\S]*?filter \\{ isAnalysisEligible").containsMatchIn(source))
        assertTrue(Regex("analysisCharges = charges\\.map[\\s\\S]*?filter \\{ isAnalysisEligible").containsMatchIn(source))
        assertTrue(source.contains("isAnalysisEligible"))
    }

    private fun assertQueryExcludesQuarantine(source: String, method: String) {
        val methodOffset = source.indexOf("fun $method(")
        assertTrue("Missing DAO method $method", methodOffset >= 0)
        val queryOffset = source.lastIndexOf("@Query", methodOffset)
        val query = source.substring(queryOffset, methodOffset)
        assertTrue("$method must exclude quarantined rows", query.contains("qualityState"))
        assertTrue(
            "$method must use an exclusion or eligible-state predicate",
            query.contains("!= 'quarantined'") || query.contains("IN ('observed', 'derived')")
        )
    }

    private fun assertQueryRequiresEligibleQuality(source: String, method: String) {
        val methodOffset = source.indexOf("fun $method(")
        assertTrue("Missing DAO method $method", methodOffset >= 0)
        val queryOffset = source.lastIndexOf("@Query", methodOffset)
        val query = source.substring(queryOffset, methodOffset)
        assertTrue("$method must exclude incomplete alias rows", query.contains("qualityState IN ('observed', 'derived')"))
    }
}
