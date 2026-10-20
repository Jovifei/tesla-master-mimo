package com.matelink.data.local

import android.app.Application
import com.matelink.data.repository.StatsRepository
import com.matelink.data.repository.SettingsRepository
import com.matelink.data.repository.GeocodingRepository
import com.matelink.data.sync.HistoryResourceFixture
import com.matelink.data.sync.SyncRepository
import com.matelink.domain.model.YearFilter
import com.matelink.domain.analytics.RecommendationKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate
import java.util.TimeZone
import com.matelink.data.api.models.ChargeData
import com.matelink.data.api.models.DriveData
import com.matelink.data.api.models.DriveOdometerDetails
import com.matelink.data.repository.buildQualifiedMonthlyChargeAggregations
import com.matelink.data.repository.buildQualifiedMonthlyDriveAggregations
import com.matelink.data.sync.toSyncSummary
import java.io.File
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class HistoryQualityPersistenceContractTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun migrationAddsAuditableQualityWithoutDeletingHistory() {
        val source = File("src/main/java/com/matelink/data/local/StatsDatabase.kt").readText()
        val migration = source.substringAfter("val MIGRATION_19_20").substringBefore("private fun")

        // The current schema version and complete chain are exercised by native Room migration tests.
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
            "getDistinctDrivingDays", "getDistinctDrivingDaysInRange", "getHistoryDrivesInRange"
        ).forEach { method -> assertQueryExcludesQuarantine(source, method) }
        assertBoundedHistoryQuery(source, "getHistoryDrivesInRange")
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
            "biggestBatteryGainCharge", "biggestBatteryGainChargeInRange", "getHistoryChargesInRange"
        ).forEach { method -> assertQueryExcludesQuarantine(source, method) }
        assertBoundedHistoryQuery(source, "getHistoryChargesInRange")
    }

    @Test
    fun qualifiedMonthlyConsumersRejectQuarantinedRoomRows() {
        val start = "2026-10-20T00:00:00Z"
        val end = "2026-10-20T01:00:00Z"
        val drive = DriveData(1, startDate = start, endDate = end,
            odometerDetails = DriveOdometerDetails(distance = 10.0), lengthUnit = "km",
            source = "teslamate_archive", qualityState = "observed")
            .toSyncSummary(42)!!.copy(qualityState = "quarantined")
        val charge = ChargeData(2, startDate = start, endDate = end,
            chargeEnergyAdded = 5.0, source = "teslamate_archive", qualityState = "observed")
            .toSyncSummary(42)!!.copy(qualityState = "quarantined")
        val zone = ZoneId.of("Asia/Shanghai")

        assertTrue(buildQualifiedMonthlyDriveAggregations(listOf(drive), 42, 2026, zone).isEmpty())
        assertTrue(buildQualifiedMonthlyChargeAggregations(listOf(charge), 42, 2026, zone).isEmpty())
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
    fun statsRecommendationsFilterIncompleteHistoryBeforeBuildingEvidence() = runBlocking(Dispatchers.IO) {
        val previousZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        val fixture = HistoryResourceFixture(File(temp.root, "stats-quality-cohort.db"))
        try {
            fixture.initialize(cloud = true)
            SettingsRepository(fixture.context).setMockMode(false)
            fixture.settings.saveCurrency("CNY")
            val car = fixture.localId(1)
            val geo = SyncRepository::class.java.getDeclaredField("geocodingRepository")
                .apply { isAccessible = true }.get(fixture.repo) as GeocodingRepository
            val stats = StatsRepository(fixture.db.driveSummaryDao(), fixture.db.chargeSummaryDao(),
                fixture.db.aggregateDao(), fixture.syncManager, geo, fixture.vehicleContexts,
                ChargeCostOverrideStore(fixture.db.chargeCostOverrideDao(), fixture.settings), fixture.db,
                fixture.settings)
            fun drive(id: Int, date: String, distance: Double, energy: Double, speed: Double,
                state: String, reason: String) = DriveData(id, startDate = date,
                endDate = Instant.parse(date).plusSeconds(3600).toString(),
                odometerDetails = DriveOdometerDetails(distance = distance), lengthUnit = "km",
                energyConsumedNet = energy, speedAvg = speed, outsideTempAvg = 20.0,
                source = "teslamate_archive", qualityState = state, qualityReason = reason).toSyncSummary(car)!!
            fun charge(id: Int, energy: Double, cost: Double, state: String, reason: String) = ChargeData(id,
                startDate = "2026-10-03T12:00:00Z", endDate = "2026-10-03T13:00:00Z",
                chargeEnergyAdded = energy, cost = cost, source = "teslamate_archive",
                qualityState = state, qualityReason = reason, currencyCode = "CNY").toSyncSummary(car)!!
            val baseline = (0..4).map { index -> drive(index + 1,
                "2026-10-${(1 + index * 2).toString().padStart(2, '0')}T12:00:00Z",
                20.0, 2.0, 70.0, "observed", "api") }
            val untrusted = (0..5).map { index -> drive(index + 10,
                "2026-09-${(index + 1).toString().padStart(2, '0')}T12:00:00Z",
                100.0, 100.0, 110.0, "incomplete", "legacy_alias_unverified") }
            fixture.db.driveSummaryDao().upsertAll(baseline + untrusted)
            fixture.db.chargeSummaryDao().upsertAll(listOf(
                charge(1, 5.0, 2.0, "observed", "api"),
                charge(2, 999.0, 999.0, "incomplete", "missing_api_evidence")))
            val filters = listOf(YearFilter.AllTime, YearFilter.Year(2026))
            for (filter in filters) {
                val excluded = stats.getStats(1, filter)
                val coverage = requireNotNull(excluded.analysisCoverage)
                assertEquals(5, excluded.quickStats.totalDrives)
                assertEquals(5, coverage.driveRecordCount)
                assertEquals(1, excluded.quickStats.totalCharges)
                assertEquals(1, coverage.chargeRecordCount)
                assertEquals(100.0, excluded.quickStats.totalDistanceKm, 0.001)
                assertEquals(10.0, requireNotNull(coverage.qualifiedDriveEnergyTotalKwh), 0.001)
                assertEquals(5.0, excluded.quickStats.totalEnergyAddedKwh, 0.001)
                assertEquals(2.0, requireNotNull(excluded.quickStats.totalCost), 0.001)
                assertTrue("Untrusted high-speed aliases must not generate recommendations", excluded.recommendations.isEmpty())
            }
            // Only the reason-specific legacy exception is eligible; valid-looking numbers alone are insufficient.
            val legacy = (0..4).map { index -> drive(index + 20,
                "2026-10-${(11 + index * 2).toString().padStart(2, '0')}T12:00:00Z",
                20.0, 4.0, 110.0, "incomplete", "remote_quality_unavailable") }
            fixture.db.driveSummaryDao().upsertAll(legacy)
            fixture.db.chargeSummaryDao().upsert(charge(3, 7.0, 3.0, "incomplete", "remote_quality_unavailable"))
            for (filter in filters) {
                val accepted = stats.getStats(1, filter)
                val coverage = requireNotNull(accepted.analysisCoverage)
                assertEquals(10, accepted.quickStats.totalDrives)
                assertEquals(10, coverage.driveRecordCount)
                assertEquals(10, coverage.driveDistanceSampleCount)
                assertEquals(10, coverage.driveEnergySampleCount)
                assertEquals(2, accepted.quickStats.totalCharges)
                assertEquals(2, coverage.chargeRecordCount)
                assertEquals(2, coverage.chargeEnergySampleCount)
                assertEquals(2, coverage.chargeCostSampleCount)
                assertEquals(200.0, accepted.quickStats.totalDistanceKm, 0.001)
                assertEquals(200.0, requireNotNull(coverage.qualifiedDistanceTotalKm), 0.001)
                assertEquals(30.0, requireNotNull(accepted.quickStats.totalEnergyConsumedKwh), 0.001)
                assertEquals(30.0, requireNotNull(coverage.qualifiedDriveEnergyTotalKwh), 0.001)
                assertEquals(12.0, requireNotNull(coverage.qualifiedChargeEnergyTotalKwh), 0.001)
                assertEquals(5.0, requireNotNull(accepted.quickStats.totalCost), 0.001)
                assertEquals(LocalDate.of(2026, 10, 1), coverage.firstObservedDate)
                assertEquals(LocalDate.of(2026, 10, 19), coverage.lastObservedDate)
                val recommendation = accepted.recommendations.single()
                assertEquals(RecommendationKind.HIGH_SPEED_EFFICIENCY, recommendation.kind)
                assertEquals(10, recommendation.sampleCount)
                assertEquals(200.0, recommendation.coverage, 0.001)
                assertEquals(200.0, recommendation.triggerValue, 0.001)
                assertEquals(115.0, recommendation.thresholdValue, 0.001)
                assertEquals(19, recommendation.observationDays)
            }
            // Admission filters consumers; they never delete the disqualified source receipts.
            assertEquals(16, fixture.db.driveSummaryDao().getAllChronological(car).size)
            assertEquals(3, fixture.db.chargeSummaryDao().getAllForCar(car).size)
            assertTrue(fixture.calls.isEmpty())
        } finally {
            try { fixture.close() } finally { TimeZone.setDefault(previousZone) }
        }
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

    private fun assertBoundedHistoryQuery(source: String, method: String) {
        val methodOffset = source.indexOf("fun $method(")
        assertTrue("Missing bounded history method $method", methodOffset >= 0)
        val query = source.substring(source.lastIndexOf("@Query", methodOffset), methodOffset)
        assertTrue(query.contains("carId = :carId"))
        assertTrue(query.contains("startDate >= :startDate"))
        assertTrue(query.contains("startDate < :endDate"))
    }
}
