package com.matelink.data.repository

import com.matelink.data.local.dao.AggregateDao
import com.matelink.data.local.dao.ChargeSummaryDao
import com.matelink.data.local.dao.DriveSummaryDao
import com.matelink.data.local.entity.SchemaVersion
import com.matelink.data.sync.SyncManager
import com.matelink.domain.model.CarStats
import com.matelink.domain.model.ChargePowerRecord
import com.matelink.domain.model.ChargeTempRecord
import com.matelink.domain.model.DeepStats
import com.matelink.domain.model.DriveElevationRecord
import com.matelink.domain.model.DriveTempRecord
import com.matelink.domain.model.QuickStats
import com.matelink.domain.model.BatteryChangeRecord
import com.matelink.domain.model.ChargeLocation
import com.matelink.domain.model.CountryRecord
import com.matelink.domain.model.DriveLocation
import com.matelink.domain.model.RegionRecord
import com.matelink.domain.model.GapRecord
import com.matelink.domain.model.MaxDistanceBetweenChargesRecord
import com.matelink.domain.model.StreakRecord
import com.matelink.domain.model.YearFilter
import com.matelink.domain.analytics.RecommendationChargeSample
import com.matelink.domain.analytics.RecommendationDriveSample
import com.matelink.domain.analytics.AnalysisChargeCoverageSample
import com.matelink.domain.analytics.AnalysisDriveCoverageSample
import com.matelink.domain.analytics.buildAnalysisCoverage
import com.matelink.domain.analytics.buildRecommendationEvidence
import com.matelink.domain.analytics.buildRecommendations
import com.matelink.domain.analytics.observedAggregateCostOrNull
import com.matelink.domain.analytics.toAnalysisChargeData
import com.matelink.domain.analytics.toAnalysisDriveData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for computing and retrieving stats for a car.
 */
@Singleton
class StatsRepository @Inject constructor(
    private val driveSummaryDao: DriveSummaryDao,
    private val chargeSummaryDao: ChargeSummaryDao,
    private val aggregateDao: AggregateDao,
    private val syncManager: SyncManager,
    private val geocodingRepository: GeocodingRepository,
    private val vehicleContextRepository: com.matelink.data.local.VehicleContextRepository
) {

    private suspend fun resolveHistoryCarId(remoteApiCarId: Int): Int {
        return vehicleContextRepository.resolveRemote(remoteApiCarId).localHistoryCarId
    }

    /**
     * Get complete stats for a car with the given year filter.
     */
    suspend fun getStats(carId: Int, yearFilter: YearFilter): CarStats {
        val historyCarId = resolveHistoryCarId(carId)
        val quickStats = getQuickStats(carId, yearFilter)
        val deepStats = getDeepStats(carId, yearFilter)
        val syncProgress = syncManager.getProgressForCar(historyCarId)
        val (recommendations, analysisCoverage) = getRecommendationsAndCoverage(carId, yearFilter)

        return CarStats(
            carId = carId,
            yearFilter = yearFilter,
            quickStats = quickStats,
            deepStats = deepStats,
            syncProgress = syncProgress,
            recommendations = recommendations,
            analysisCoverage = analysisCoverage
        )
    }

    private suspend fun getRecommendationsAndCoverage(carId: Int, yearFilter: YearFilter) = withContext(Dispatchers.Default) {
        val historyCarId = resolveHistoryCarId(carId)
        val (drives, charges) = when (yearFilter) {
            is YearFilter.AllTime ->
                driveSummaryDao.getAllChronological(historyCarId) to chargeSummaryDao.getAllForCar(historyCarId)
            is YearFilter.Year -> {
                val startDate = "${yearFilter.year}-01-01T00:00:00"
                val endDate = "${yearFilter.year + 1}-01-01T00:00:00"
                driveSummaryDao.getDrivesInRange(historyCarId, startDate, endDate) to
                chargeSummaryDao.getChargesInRange(historyCarId, startDate, endDate)
            }
        }
        // Room summaries retain legacy non-null columns for schema compatibility.
        // Rehydrate through the neutral API models before analytics so placeholder
        // zeros are not treated as observed measurements.
        val analysisDrives = drives.map { it.toAnalysisDriveData() }
        val analysisCharges = charges.map { it.toAnalysisChargeData() }
        val recommendations = buildRecommendations(
            buildRecommendationEvidence(
                drives = analysisDrives.map {
                    RecommendationDriveSample(
                        distanceKm = it.distance,
                        energyKwh = it.energyConsumedNet,
                        averageSpeedKmh = it.speedAvg,
                        outsideTemperatureC = it.outsideTempAvg,
                        observedAt = it.startDate
                    )
                },
                charges = analysisCharges.map {
                    RecommendationChargeSample(
                        energyAddedKwh = it.chargeEnergyAdded,
                        energyUsedKwh = it.chargeEnergyUsed,
                        observedAt = it.startDate
                    )
                }
            )
        )
        val coverage = buildAnalysisCoverage(
            drives = analysisDrives.map {
                AnalysisDriveCoverageSample(
                    distanceKm = it.distance,
                    energyKwh = it.energyConsumedNet,
                    observedAt = it.startDate
                )
            },
            charges = analysisCharges.map {
                AnalysisChargeCoverageSample(
                    energyAddedKwh = it.chargeEnergyAdded,
                    cost = it.cost,
                    observedAt = it.startDate,
                    energyUsedKwh = it.chargeEnergyUsed
                )
            }
        )
        recommendations to coverage
    }

    /**
     * Get quick stats (from summary tables, instant).
     */
    suspend fun getQuickStats(carId: Int, yearFilter: YearFilter): QuickStats {
        val historyCarId = resolveHistoryCarId(carId)
        return when (yearFilter) {
            is YearFilter.AllTime -> getQuickStatsAllTime(historyCarId)
            is YearFilter.Year -> getQuickStatsForYear(historyCarId, yearFilter.year)
        }
    }

    private suspend fun getQuickStatsAllTime(carId: Int): QuickStats {
        val pricedChargeCount = chargeSummaryDao.countWithCost(carId)
        return QuickStats(
            totalDrives = driveSummaryDao.count(carId),
            totalDistanceKm = driveSummaryDao.sumDistance(carId),
            totalEnergyConsumedKwh = driveSummaryDao.sumEnergyConsumed(carId),
            avgEfficiencyWhKm = driveSummaryDao.avgEfficiency(carId),
            maxSpeedKmh = driveSummaryDao.maxSpeed(carId),
            avgDriveMinutes = driveSummaryDao.avgDuration(carId),
            totalDrivingDays = driveSummaryDao.countDrivingDays(carId),

            totalCharges = chargeSummaryDao.count(carId),
            totalEnergyAddedKwh = chargeSummaryDao.sumEnergyAdded(carId),
            totalCost = observedAggregateCostOrNull(
                totalCost = chargeSummaryDao.sumCost(carId),
                pricedRecordCount = pricedChargeCount
            ),
            avgCostPerKwh = observedAggregateCostOrNull(
                totalCost = chargeSummaryDao.avgCostPerKwh(carId),
                pricedRecordCount = pricedChargeCount
            ),
            avgChargeMinutes = chargeSummaryDao.avgDuration(carId),

            longestDrive = driveSummaryDao.longestDrive(carId),
            fastestDrive = driveSummaryDao.fastestDrive(carId),
            mostEfficientDrive = driveSummaryDao.mostEfficientDrive(carId),
            leastEfficientDrive = driveSummaryDao.leastEfficientDrive(carId),
            biggestCharge = chargeSummaryDao.biggestCharge(carId),
            mostExpensiveCharge = chargeSummaryDao.mostExpensiveCharge(carId),
            mostExpensivePerKwhCharge = chargeSummaryDao.mostExpensivePerKwhCharge(carId),

            firstDriveDate = driveSummaryDao.firstDriveDate(carId),
            firstChargeDate = chargeSummaryDao.firstChargeDate(carId),
            busiestDay = driveSummaryDao.busiestDay(carId),
            mostDistanceDay = driveSummaryDao.mostDistanceDay(carId),

            maxDistanceBetweenCharges = chargeSummaryDao.maxDistanceBetweenCharges(carId)?.let {
                MaxDistanceBetweenChargesRecord(
                    distance = it.distance,
                    fromChargeId = it.fromChargeId,
                    toChargeId = it.toChargeId,
                    fromDate = it.fromDate,
                    toDate = it.toDate
                )
            },

            longestGapWithoutCharging = chargeSummaryDao.longestGapBetweenCharges(carId)?.let {
                GapRecord(gapDays = it.gapDays, fromDate = it.fromDate, toDate = it.toDate)
            },
            longestGapWithoutDriving = driveSummaryDao.longestGapBetweenDrives(carId)?.let {
                GapRecord(gapDays = it.gapDays, fromDate = it.fromDate, toDate = it.toDate)
            },

            longestDrivingStreak = computeLongestStreak(
                driveSummaryDao.getDistinctDrivingDays(carId)
            ),

            biggestBatteryGainCharge = chargeSummaryDao.biggestBatteryGainCharge(carId)?.let {
                BatteryChangeRecord(
                    percentChange = it.endBatteryLevel - it.startBatteryLevel,
                    startLevel = it.startBatteryLevel,
                    endLevel = it.endBatteryLevel,
                    recordId = it.chargeId,
                    date = it.startDate,
                    isCharge = true
                )
            },
            biggestBatteryDrainDrive = driveSummaryDao.biggestBatteryDrainDrive(carId)?.let {
                BatteryChangeRecord(
                    percentChange = it.startBatteryLevel - it.endBatteryLevel,
                    startLevel = it.startBatteryLevel,
                    endLevel = it.endBatteryLevel,
                    recordId = it.driveId,
                    date = it.startDate,
                    isCharge = false
                )
            }
        )
    }

    private suspend fun getQuickStatsForYear(carId: Int, year: Int): QuickStats {
        val startDate = "$year-01-01T00:00:00"
        val endDate = "${year + 1}-01-01T00:00:00"
        val pricedChargeCount = chargeSummaryDao.countWithCostInRange(carId, startDate, endDate)

        return QuickStats(
            totalDrives = driveSummaryDao.countInRange(carId, startDate, endDate),
            totalDistanceKm = driveSummaryDao.sumDistanceInRange(carId, startDate, endDate),
            totalEnergyConsumedKwh = driveSummaryDao.sumEnergyConsumedInRange(carId, startDate, endDate),
            avgEfficiencyWhKm = driveSummaryDao.avgEfficiencyInRange(carId, startDate, endDate),
            maxSpeedKmh = driveSummaryDao.maxSpeedInRange(carId, startDate, endDate),
            avgDriveMinutes = null, // Not critical for year view
            totalDrivingDays = driveSummaryDao.countDrivingDaysInRange(carId, startDate, endDate),

            totalCharges = chargeSummaryDao.countInRange(carId, startDate, endDate),
            totalEnergyAddedKwh = chargeSummaryDao.sumEnergyAddedInRange(carId, startDate, endDate),
            totalCost = observedAggregateCostOrNull(
                totalCost = chargeSummaryDao.sumCostInRange(carId, startDate, endDate),
                pricedRecordCount = pricedChargeCount
            ),
            avgCostPerKwh = observedAggregateCostOrNull(
                totalCost = chargeSummaryDao.avgCostPerKwhInRange(carId, startDate, endDate),
                pricedRecordCount = pricedChargeCount
            ),
            avgChargeMinutes = null, // Not critical for year view

            longestDrive = driveSummaryDao.longestDriveInRange(carId, startDate, endDate),
            fastestDrive = driveSummaryDao.fastestDriveInRange(carId, startDate, endDate),
            mostEfficientDrive = driveSummaryDao.mostEfficientDriveInRange(carId, startDate, endDate),
            leastEfficientDrive = driveSummaryDao.leastEfficientDriveInRange(carId, startDate, endDate),
            biggestCharge = chargeSummaryDao.biggestChargeInRange(carId, startDate, endDate),
            mostExpensiveCharge = chargeSummaryDao.mostExpensiveChargeInRange(carId, startDate, endDate),
            mostExpensivePerKwhCharge = chargeSummaryDao.mostExpensivePerKwhChargeInRange(carId, startDate, endDate),

            firstDriveDate = driveSummaryDao.firstDriveDate(carId), // Always show first ever
            firstChargeDate = chargeSummaryDao.firstChargeDate(carId), // Always show first ever
            busiestDay = driveSummaryDao.busiestDayInRange(carId, startDate, endDate),
            mostDistanceDay = driveSummaryDao.mostDistanceDayInRange(carId, startDate, endDate),

            maxDistanceBetweenCharges = chargeSummaryDao.maxDistanceBetweenChargesInRange(carId, startDate, endDate)?.let {
                MaxDistanceBetweenChargesRecord(
                    distance = it.distance,
                    fromChargeId = it.fromChargeId,
                    toChargeId = it.toChargeId,
                    fromDate = it.fromDate,
                    toDate = it.toDate
                )
            },

            longestGapWithoutCharging = chargeSummaryDao.longestGapBetweenChargesInRange(carId, startDate, endDate)?.let {
                GapRecord(gapDays = it.gapDays, fromDate = it.fromDate, toDate = it.toDate)
            },
            longestGapWithoutDriving = driveSummaryDao.longestGapBetweenDrivesInRange(carId, startDate, endDate)?.let {
                GapRecord(gapDays = it.gapDays, fromDate = it.fromDate, toDate = it.toDate)
            },

            longestDrivingStreak = computeLongestStreak(
                driveSummaryDao.getDistinctDrivingDaysInRange(carId, startDate, endDate)
            ),

            biggestBatteryGainCharge = chargeSummaryDao.biggestBatteryGainChargeInRange(carId, startDate, endDate)?.let {
                BatteryChangeRecord(
                    percentChange = it.endBatteryLevel - it.startBatteryLevel,
                    startLevel = it.startBatteryLevel,
                    endLevel = it.endBatteryLevel,
                    recordId = it.chargeId,
                    date = it.startDate,
                    isCharge = true
                )
            },
            biggestBatteryDrainDrive = driveSummaryDao.biggestBatteryDrainDriveInRange(carId, startDate, endDate)?.let {
                BatteryChangeRecord(
                    percentChange = it.startBatteryLevel - it.endBatteryLevel,
                    startLevel = it.startBatteryLevel,
                    endLevel = it.endBatteryLevel,
                    recordId = it.driveId,
                    date = it.startDate,
                    isCharge = false
                )
            }
        )
    }

    /**
     * Get deep stats (from aggregate tables, requires sync).
     * Returns null if no aggregates exist yet.
     */
    suspend fun getDeepStats(carId: Int, yearFilter: YearFilter): DeepStats? {
        val historyCarId = resolveHistoryCarId(carId)
        val driveAggregates = aggregateDao.countDriveAggregates(historyCarId)
        val chargeAggregates = aggregateDao.countChargeAggregates(historyCarId)

        // Return null if no aggregates exist at all
        if (driveAggregates == 0 && chargeAggregates == 0) {
            return null
        }

        return when (yearFilter) {
            is YearFilter.AllTime -> getDeepStatsAllTime(historyCarId, driveAggregates, chargeAggregates)
            is YearFilter.Year -> getDeepStatsForYear(historyCarId, yearFilter.year, driveAggregates, chargeAggregates)
        }
    }

    private suspend fun getDeepStatsAllTime(carId: Int, driveCount: Int, chargeCount: Int): DeepStats {
        // Elevation records
        val driveWithMaxElev = aggregateDao.driveWithMaxElevation(carId)
        val driveWithMinElev = aggregateDao.driveWithMinElevation(carId)
        val driveWithMostGain = aggregateDao.driveWithMostElevationGain(carId)

        // Temperature records (driving)
        val hottestDriveAgg = aggregateDao.hottestDrive(carId)
        val coldestDriveAgg = aggregateDao.coldestDrive(carId)

        // Temperature records (charging)
        val hottestChargeAgg = aggregateDao.hottestCharge(carId)
        val coldestChargeAgg = aggregateDao.coldestCharge(carId)

        // Power record
        val chargeWithMaxPowerAgg = aggregateDao.chargeWithMaxPower(carId)

        return DeepStats(
            maxElevationM = aggregateDao.maxElevation(carId),
            minElevationM = aggregateDao.minElevation(carId),
            driveWithMaxElevation = driveWithMaxElev?.let { agg ->
                val drive = driveSummaryDao.get(carId, agg.driveId)
                DriveElevationRecord(
                    driveId = agg.driveId,
                    elevationM = agg.maxElevation,
                    elevationGainM = agg.elevationGain,
                    date = drive?.startDate
                )
            },
            driveWithMinElevation = driveWithMinElev?.let { agg ->
                val drive = driveSummaryDao.get(carId, agg.driveId)
                DriveElevationRecord(
                    driveId = agg.driveId,
                    elevationM = agg.minElevation,
                    elevationGainM = agg.elevationGain,
                    date = drive?.startDate
                )
            },
            driveWithMostClimbing = driveWithMostGain?.let { agg ->
                val drive = driveSummaryDao.get(carId, agg.driveId)
                DriveElevationRecord(
                    driveId = agg.driveId,
                    elevationM = agg.maxElevation,
                    elevationGainM = agg.elevationGain,
                    date = drive?.startDate
                )
            },

            maxOutsideTempDrivingC = aggregateDao.maxOutsideTempDriving(carId),
            minOutsideTempDrivingC = aggregateDao.minOutsideTempDriving(carId),
            maxCabinTempC = aggregateDao.maxInsideTemp(carId),
            minCabinTempC = aggregateDao.minInsideTemp(carId),
            hottestDrive = hottestDriveAgg?.let { agg ->
                val drive = driveSummaryDao.get(carId, agg.driveId)
                DriveTempRecord(
                    driveId = agg.driveId,
                    tempC = agg.maxOutsideTemp,
                    date = drive?.startDate
                )
            },
            coldestDrive = coldestDriveAgg?.let { agg ->
                val drive = driveSummaryDao.get(carId, agg.driveId)
                DriveTempRecord(
                    driveId = agg.driveId,
                    tempC = agg.minOutsideTemp,
                    date = drive?.startDate
                )
            },

            maxOutsideTempChargingC = aggregateDao.maxOutsideTempCharging(carId),
            minOutsideTempChargingC = aggregateDao.minOutsideTempCharging(carId),
            hottestCharge = hottestChargeAgg?.let { agg ->
                val charge = chargeSummaryDao.get(carId, agg.chargeId)
                ChargeTempRecord(
                    chargeId = agg.chargeId,
                    tempC = agg.maxOutsideTemp,
                    date = charge?.startDate
                )
            },
            coldestCharge = coldestChargeAgg?.let { agg ->
                val charge = chargeSummaryDao.get(carId, agg.chargeId)
                ChargeTempRecord(
                    chargeId = agg.chargeId,
                    tempC = agg.minOutsideTemp,
                    date = charge?.startDate
                )
            },

            maxChargerPowerKw = aggregateDao.maxChargerPower(carId),
            chargeWithMaxPower = chargeWithMaxPowerAgg?.let { agg ->
                val charge = chargeSummaryDao.get(carId, agg.chargeId)
                ChargePowerRecord(
                    chargeId = agg.chargeId,
                    powerKw = agg.maxChargerPower,
                    date = charge?.startDate
                )
            },

            acChargeCount = aggregateDao.countAcCharges(carId),
            dcChargeCount = aggregateDao.countDcCharges(carId),
            acChargeEnergyKwh = aggregateDao.sumAcChargeEnergy(carId),
            dcChargeEnergyKwh = aggregateDao.sumDcChargeEnergy(carId),

            countriesVisitedCount = aggregateDao.countUniqueCountries(carId).takeIf { it > 0 },

            driveDetailsProcessed = driveCount,
            chargeDetailsProcessed = chargeCount
        )
    }

    private suspend fun getDeepStatsForYear(
        carId: Int,
        year: Int,
        driveCount: Int,
        chargeCount: Int
    ): DeepStats {
        val startDate = "$year-01-01T00:00:00"
        val endDate = "${year + 1}-01-01T00:00:00"

        // Elevation records for year
        val driveWithMaxElev = aggregateDao.driveWithMaxElevationInRange(carId, startDate, endDate)
        val driveWithMostGain = aggregateDao.driveWithMostElevationGainInRange(carId, startDate, endDate)

        // Temperature records for year
        val hottestDriveAgg = aggregateDao.hottestDriveInRange(carId, startDate, endDate)
        val coldestDriveAgg = aggregateDao.coldestDriveInRange(carId, startDate, endDate)
        val hottestChargeAgg = aggregateDao.hottestChargeInRange(carId, startDate, endDate)
        val coldestChargeAgg = aggregateDao.coldestChargeInRange(carId, startDate, endDate)

        // Power record for year
        val chargeWithMaxPowerAgg = aggregateDao.chargeWithMaxPowerInRange(carId, startDate, endDate)

        return DeepStats(
            maxElevationM = aggregateDao.maxElevationInRange(carId, startDate, endDate),
            minElevationM = null, // Not shown in UI
            driveWithMaxElevation = driveWithMaxElev?.let { agg ->
                val drive = driveSummaryDao.get(carId, agg.driveId)
                DriveElevationRecord(
                    driveId = agg.driveId,
                    elevationM = agg.maxElevation,
                    elevationGainM = agg.elevationGain,
                    date = drive?.startDate
                )
            },
            driveWithMinElevation = null, // Not shown in UI
            driveWithMostClimbing = driveWithMostGain?.let { agg ->
                val drive = driveSummaryDao.get(carId, agg.driveId)
                DriveElevationRecord(
                    driveId = agg.driveId,
                    elevationM = agg.maxElevation,
                    elevationGainM = agg.elevationGain,
                    date = drive?.startDate
                )
            },

            maxOutsideTempDrivingC = aggregateDao.maxOutsideTempDrivingInRange(carId, startDate, endDate),
            minOutsideTempDrivingC = aggregateDao.minOutsideTempDrivingInRange(carId, startDate, endDate),
            maxCabinTempC = aggregateDao.maxInsideTempInRange(carId, startDate, endDate),
            minCabinTempC = aggregateDao.minInsideTempInRange(carId, startDate, endDate),
            hottestDrive = hottestDriveAgg?.let { agg ->
                val drive = driveSummaryDao.get(carId, agg.driveId)
                DriveTempRecord(
                    driveId = agg.driveId,
                    tempC = agg.maxOutsideTemp,
                    date = drive?.startDate
                )
            },
            coldestDrive = coldestDriveAgg?.let { agg ->
                val drive = driveSummaryDao.get(carId, agg.driveId)
                DriveTempRecord(
                    driveId = agg.driveId,
                    tempC = agg.minOutsideTemp,
                    date = drive?.startDate
                )
            },

            maxOutsideTempChargingC = aggregateDao.maxOutsideTempChargingInRange(carId, startDate, endDate),
            minOutsideTempChargingC = aggregateDao.minOutsideTempChargingInRange(carId, startDate, endDate),
            hottestCharge = hottestChargeAgg?.let { agg ->
                val charge = chargeSummaryDao.get(carId, agg.chargeId)
                ChargeTempRecord(
                    chargeId = agg.chargeId,
                    tempC = agg.maxOutsideTemp,
                    date = charge?.startDate
                )
            },
            coldestCharge = coldestChargeAgg?.let { agg ->
                val charge = chargeSummaryDao.get(carId, agg.chargeId)
                ChargeTempRecord(
                    chargeId = agg.chargeId,
                    tempC = agg.minOutsideTemp,
                    date = charge?.startDate
                )
            },

            maxChargerPowerKw = aggregateDao.maxChargerPowerInRange(carId, startDate, endDate),
            chargeWithMaxPower = chargeWithMaxPowerAgg?.let { agg ->
                val charge = chargeSummaryDao.get(carId, agg.chargeId)
                ChargePowerRecord(
                    chargeId = agg.chargeId,
                    powerKw = agg.maxChargerPower,
                    date = charge?.startDate
                )
            },

            acChargeCount = aggregateDao.countAcChargesInRange(carId, startDate, endDate),
            dcChargeCount = aggregateDao.countDcChargesInRange(carId, startDate, endDate),
            acChargeEnergyKwh = aggregateDao.sumAcChargeEnergyInRange(carId, startDate, endDate),
            dcChargeEnergyKwh = aggregateDao.sumDcChargeEnergyInRange(carId, startDate, endDate),

            countriesVisitedCount = aggregateDao.countUniqueCountriesInRange(carId, startDate, endDate).takeIf { it > 0 },

            driveDetailsProcessed = driveCount,
            chargeDetailsProcessed = chargeCount
        )
    }

    /**
     * Get available years for the year filter dropdown.
     */
    suspend fun getAvailableYears(carId: Int): List<Int> {
        val historyCarId = resolveHistoryCarId(carId)
        val driveYears = driveSummaryDao.getYears(historyCarId)
        val chargeYears = chargeSummaryDao.getYears(historyCarId)
        return (driveYears + chargeYears).distinct().sortedDescending()
    }

    /**
     * Check if any data is available for stats.
     */
    suspend fun hasData(carId: Int): Boolean {
        val historyCarId = resolveHistoryCarId(carId)
        return driveSummaryDao.count(historyCarId) > 0 || chargeSummaryDao.count(historyCarId) > 0
    }

    /**
     * Check if deep stats are being processed.
     */
    suspend fun isDeepSyncInProgress(carId: Int): Boolean {
        val progress = syncManager.getProgressForCar(resolveHistoryCarId(carId))
        return progress != null && progress.phase.isProcessing()
    }

    /**
     * Get drives between two dates (for range record details).
     */
    suspend fun getDrivesBetweenDates(carId: Int, afterDate: String, beforeDate: String) =
        driveSummaryDao.getDrivesBetweenDates(resolveHistoryCarId(carId), afterDate, beforeDate)

    /**
     * Get the sync completion percentage for deep stats.
     * Returns 1.0 if sync is marked complete, regardless of actual count
     * (some items may have failed but sync is done).
     * Only counts aggregates with current schema version to accurately reflect
     * progress when schema changes require reprocessing.
     */
    suspend fun getDeepSyncProgress(carId: Int): Float {
        val historyCarId = resolveHistoryCarId(carId)
        // If sync is marked complete, return 1.0
        val progress = syncManager.getProgressForCar(historyCarId)
        if (progress?.phase == com.matelink.domain.model.SyncPhase.COMPLETE) {
            return 1f
        }

        val totalDrives = driveSummaryDao.count(historyCarId)
        val totalCharges = chargeSummaryDao.count(historyCarId)
        // Only count aggregates with current schema version
        val processedDrives = aggregateDao.countDriveAggregatesWithSchema(historyCarId, SchemaVersion.CURRENT)
        val processedCharges = aggregateDao.countChargeAggregatesWithSchema(historyCarId, SchemaVersion.CURRENT)

        val total = totalDrives + totalCharges
        val processed = processedDrives + processedCharges

        return if (total > 0) processed.toFloat() / total else 0f
    }

    /**
     * Observe sync progress as a Flow. Room automatically emits when tables change.
     * This provides real-time progress updates without relying on StateFlow propagation.
     * Only counts aggregates with current schema version to accurately reflect
     * progress when schema changes require reprocessing.
     */
    fun observeDeepSyncProgress(carId: Int): kotlinx.coroutines.flow.Flow<Float> {
        return flow {
            val historyCarId = resolveHistoryCarId(carId)
            emitAll(kotlinx.coroutines.flow.combine(
                driveSummaryDao.observeCount(historyCarId),
                chargeSummaryDao.observeCount(historyCarId),
                aggregateDao.observeDriveAggregateCountWithSchema(historyCarId, SchemaVersion.CURRENT),
                aggregateDao.observeChargeAggregateCountWithSchema(historyCarId, SchemaVersion.CURRENT)
            ) { totalDrives, totalCharges, processedDrives, processedCharges ->
                val total = totalDrives + totalCharges
                val processed = processedDrives + processedCharges
                if (total > 0) processed.toFloat() / total else 0f
            })
        }
    }

    /**
     * Get countries visited with aggregated data.
     */
    suspend fun getCountriesVisited(carId: Int, yearFilter: YearFilter): List<CountryRecord> {
        val historyCarId = resolveHistoryCarId(carId)
        val results = when (yearFilter) {
            is YearFilter.AllTime -> aggregateDao.getCountriesVisited(historyCarId)
            is YearFilter.Year -> {
                val startDate = "${yearFilter.year}-01-01T00:00:00"
                val endDate = "${yearFilter.year + 1}-01-01T00:00:00"
                aggregateDao.getCountriesVisitedInRange(historyCarId, startDate, endDate)
            }
        }
        return results.map { it.toCountryRecord() }
    }

    /**
     * Get regions visited within a specific country with aggregated data.
     */
    suspend fun getRegionsVisited(carId: Int, countryCode: String, yearFilter: YearFilter): List<RegionRecord> {
        val historyCarId = resolveHistoryCarId(carId)
        val results = when (yearFilter) {
            is YearFilter.AllTime -> aggregateDao.getRegionsVisited(historyCarId, countryCode)
            is YearFilter.Year -> {
                val startDate = "${yearFilter.year}-01-01T00:00:00"
                val endDate = "${yearFilter.year + 1}-01-01T00:00:00"
                aggregateDao.getRegionsVisitedInRange(historyCarId, countryCode, startDate, endDate)
            }
        }
        return results.map { it.toRegionRecord() }
    }

    /**
     * Observe geocoding progress for a car.
     * Returns null when geocoding is complete or hasn't started.
     */
    fun observeGeocodeProgress(carId: Int): Flow<GeocodeProgressInfo?> {
        return flow { emitAll(geocodingRepository.observeGeocodeProgress(resolveHistoryCarId(carId))) }
    }

    /**
     * Get all charge locations for a specific country (for map display).
     */
    suspend fun getChargeLocationsForCountry(
        carId: Int,
        countryCode: String,
        yearFilter: YearFilter
    ): List<ChargeLocation> {
        val historyCarId = resolveHistoryCarId(carId)
        val results = when (yearFilter) {
            is YearFilter.AllTime -> aggregateDao.getChargeLocationsForCountry(historyCarId, countryCode)
            is YearFilter.Year -> {
                val startDate = "${yearFilter.year}-01-01T00:00:00"
                val endDate = "${yearFilter.year + 1}-01-01T00:00:00"
                aggregateDao.getChargeLocationsForCountryInRange(historyCarId, countryCode, startDate, endDate)
            }
        }
        return results.map { it.toChargeLocation() }
    }

    /**
     * Get country boundary polygon for map overlay.
     */
    suspend fun getCountryBoundary(countryCode: String): CountryBoundary? {
        return geocodingRepository.getCountryBoundary(countryCode)
    }

    /**
     * Get all drive start locations for a specific country (for map display).
     */
    suspend fun getDriveLocationsForCountry(
        carId: Int,
        countryCode: String,
        yearFilter: YearFilter
    ): List<DriveLocation> {
        val historyCarId = resolveHistoryCarId(carId)
        val results = when (yearFilter) {
            is YearFilter.AllTime -> aggregateDao.getDriveLocationsForCountry(historyCarId, countryCode)
            is YearFilter.Year -> {
                val startDate = "${yearFilter.year}-01-01T00:00:00"
                val endDate = "${yearFilter.year + 1}-01-01T00:00:00"
                aggregateDao.getDriveLocationsForCountryInRange(historyCarId, countryCode, startDate, endDate)
            }
        }
        return results.map { it.toDriveLocation() }
    }

    // === Monthly Aggregation ===

    suspend fun getMonthlyDriveAggregation(carId: Int, year: Int): List<com.matelink.data.local.dao.MonthlyDriveAggregation> {
        return driveSummaryDao.getMonthlyAggregation(resolveHistoryCarId(carId), year.toString())
    }

    suspend fun getMonthlyChargeAggregation(carId: Int, year: Int): List<com.matelink.data.local.dao.MonthlyChargeAggregation> {
        return chargeSummaryDao.getMonthlyAggregation(resolveHistoryCarId(carId), year.toString())
    }
}

/**
 * Convert ISO 3166-1 alpha-2 country code to flag emoji.
 * Uses Regional Indicator Symbols to create flag emojis.
 * Example: "IT" -> "🇮🇹", "US" -> "🇺🇸"
 */
fun countryCodeToFlag(countryCode: String): String {
    if (countryCode.length != 2) return ""
    val firstChar = countryCode[0].uppercaseChar()
    val secondChar = countryCode[1].uppercaseChar()
    // Regional Indicator Symbol Letter A starts at U+1F1E6
    val first = 0x1F1E6 - 'A'.code + firstChar.code
    val second = 0x1F1E6 - 'A'.code + secondChar.code
    return String(intArrayOf(first, second), 0, 2)
}

private fun com.matelink.data.local.dao.CountryVisitResult.toCountryRecord() = CountryRecord(
    countryCode = countryCode,
    countryName = countryName,
    flagEmoji = countryCodeToFlag(countryCode),
    firstVisitDate = firstVisitDate,
    lastVisitDate = lastVisitDate,
    driveCount = driveCount,
    totalDistanceKm = totalDistanceKm,
    totalChargeEnergyKwh = totalChargeEnergyKwh,
    chargeCount = chargeCount
)

private fun com.matelink.data.local.dao.RegionVisitResult.toRegionRecord() = RegionRecord(
    regionName = regionName,
    countryCode = countryCode,
    firstVisitDate = firstVisitDate,
    lastVisitDate = lastVisitDate,
    driveCount = driveCount,
    totalDistanceKm = totalDistanceKm,
    totalChargeEnergyKwh = totalChargeEnergyKwh,
    chargeCount = chargeCount
)

private fun com.matelink.data.local.dao.ChargeLocationResult.toChargeLocation() = ChargeLocation(
    chargeId = chargeId,
    latitude = latitude,
    longitude = longitude,
    energyAddedKwh = energyAdded,
    date = startDate,
    isDcCharge = isFastCharger,
    address = address
)

private fun com.matelink.data.local.dao.DriveLocationResult.toDriveLocation() = DriveLocation(
    driveId = driveId,
    latitude = latitude,
    longitude = longitude,
    distanceKm = distance,
    date = startDate,
    address = address
)

private fun com.matelink.domain.model.SyncPhase.isProcessing(): Boolean {
    return this == com.matelink.domain.model.SyncPhase.SYNCING_SUMMARIES ||
            this == com.matelink.domain.model.SyncPhase.SYNCING_DRIVE_DETAILS ||
            this == com.matelink.domain.model.SyncPhase.SYNCING_CHARGE_DETAILS
}

/**
 * Compute the longest consecutive driving streak from a sorted list of date strings.
 * Each date string should be in "YYYY-MM-DD" format.
 */
private fun computeLongestStreak(sortedDays: List<String>): StreakRecord? {
    if (sortedDays.isEmpty()) return null
    if (sortedDays.size == 1) {
        return StreakRecord(
            streakDays = 1,
            startDate = sortedDays.first(),
            endDate = sortedDays.first()
        )
    }

    var maxStreak = 1
    var maxStreakStart = sortedDays.first()
    var maxStreakEnd = sortedDays.first()

    var currentStreak = 1
    var currentStreakStart = sortedDays.first()

    for (i in 1 until sortedDays.size) {
        val prevDate = runCatching { java.time.LocalDate.parse(sortedDays[i - 1]) }.getOrNull() ?: continue
        val currDate = runCatching { java.time.LocalDate.parse(sortedDays[i]) }.getOrNull() ?: continue

        if (currDate == prevDate.plusDays(1)) {
            // Consecutive day
            currentStreak++
        } else {
            // Gap found - check if previous streak was longest
            if (currentStreak > maxStreak) {
                maxStreak = currentStreak
                maxStreakStart = currentStreakStart
                maxStreakEnd = sortedDays[i - 1]
            }
            // Start new streak
            currentStreak = 1
            currentStreakStart = sortedDays[i]
        }
    }

    // Check final streak
    if (currentStreak > maxStreak) {
        maxStreak = currentStreak
        maxStreakStart = currentStreakStart
        maxStreakEnd = sortedDays.last()
    }

    return StreakRecord(
        streakDays = maxStreak,
        startDate = maxStreakStart,
        endDate = maxStreakEnd
    )
}
