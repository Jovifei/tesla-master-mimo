package com.matelink.data.repository

import com.matelink.data.local.dao.AggregateDao
import com.matelink.data.local.dao.ChargeSummaryDao
import com.matelink.data.local.dao.DriveSummaryDao
import com.matelink.data.local.dao.MonthlyChargeAggregation
import com.matelink.data.local.dao.MonthlyDriveAggregation
import com.matelink.data.local.entity.ChargeSummary
import com.matelink.data.local.entity.DriveSummary
import com.matelink.data.local.entity.DriveDetailAggregate
import com.matelink.data.local.entity.ChargeDetailAggregate
import com.matelink.data.local.ChargeCostOverrideStore
import com.matelink.data.local.HistoryIdentityUnavailableException
import com.matelink.data.api.models.DriveData
import com.matelink.data.local.HistoryReadScope
import com.matelink.data.local.VehicleContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.CoroutineContext
import com.matelink.data.api.models.ChargeData
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
import com.matelink.domain.analytics.Recommendation
import com.matelink.domain.analytics.AnalysisCoverage
import com.matelink.domain.analytics.AnalysisChargeCoverageSample
import com.matelink.domain.analytics.AnalysisDriveCoverageSample
import com.matelink.domain.analytics.buildAnalysisCoverage
import com.matelink.domain.analytics.CostTotals
import com.matelink.domain.analytics.calculateCostTotals
import com.matelink.domain.analytics.chargeTotalOverrideKey
import com.matelink.domain.analytics.qualifiedAcEnergyBalance
import com.matelink.domain.analytics.buildRecommendationEvidence
import com.matelink.domain.analytics.buildRecommendations
import com.matelink.domain.analytics.toAnalysisChargeData
import com.matelink.domain.analytics.toAnalysisDriveData
import com.matelink.domain.history.isAnalysisEligible
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import com.matelink.util.parseIsoInstant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

internal data class StatsChargeCostProjection(
    val charges: List<ChargeData>,
    val totals: CostTotals,
    val effectiveCosts: Map<Int, Double?>
)

private data class StatsCohort(
    val drives: List<Pair<DriveSummary, DriveData>>,
    val charges: List<Pair<ChargeSummary, ChargeData>>
)

private data class StatsAnalysis(
    val recommendations: List<Recommendation>,
    val coverage: AnalysisCoverage,
    val cost: StatsChargeCostProjection,
    val drives: List<Pair<DriveSummary, DriveData>>,
    val charges: List<Pair<ChargeSummary, ChargeData>>
)

/** Apply one vehicle's manual prices to the same locally dated charge set used by coverage. */
internal fun projectStatsChargeCosts(
    charges: List<ChargeData>,
    localHistoryCarId: Int,
    manualTotals: Map<String, Double>,
    filter: YearFilter,
    zone: ZoneId = ZoneId.systemDefault()
): StatsChargeCostProjection {
    val selected = charges.filter { charge ->
        val date = localHistoryDate(charge.startDate, zone)
        isAnalysisEligible(charge.qualityState, charge.qualityReason) && date != null &&
            (filter is YearFilter.AllTime || date.year == (filter as YearFilter.Year).year)
    }
    fun costFor(charge: ChargeData): Double? {
        val manual = manualTotals[chargeTotalOverrideKey(localHistoryCarId, charge.chargeId)]
            ?.takeIf { it.isFinite() && it >= 0.0 }
        return manual ?: charge.cost?.takeIf { it.isFinite() && it >= 0.0 }
    }
    return StatsChargeCostProjection(
        charges = selected,
        totals = calculateCostTotals(selected, ::costFor, null),
        effectiveCosts = selected.associate { it.chargeId to costFor(it) }
    )
}

private fun localHistoryDate(raw: String?, zone: ZoneId): LocalDate? =
    raw?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        ?: parseIsoInstant(raw)?.atZone(zone)?.toLocalDate()

internal fun qualifiedQuickStatsTotals(stats: QuickStats, coverage: AnalysisCoverage): QuickStats = stats.copy(
    totalDistanceKm = coverage.qualifiedDistanceTotalKm ?: 0.0,
    totalEnergyConsumedKwh = coverage.qualifiedDriveEnergyTotalKwh,
    avgEfficiencyWhKm = coverage.pairedEfficiency?.efficiencyWhKm
)

/** Include offset boundary candidates, then decide year/month from the local instant. */
private fun chargeYearCandidateBounds(year: Int): Pair<String, String> =
    (LocalDate.of(year, 1, 1).minusDays(2).toString() + "T00:00:00") to
        (LocalDate.of(year + 1, 1, 1).plusDays(2).toString() + "T00:00:00")

internal fun buildQualifiedMonthlyChargeAggregations(
    rows: List<ChargeSummary>, localHistoryCarId: Int, year: Int,
    zone: ZoneId = ZoneId.systemDefault()
): List<MonthlyChargeAggregation> = rows.mapNotNull { row ->
    if (row.carId != localHistoryCarId) return@mapNotNull null
    val charge = row.toAnalysisChargeData()
    if (!isAnalysisEligible(charge.qualityState, charge.qualityReason)) return@mapNotNull null
    val date = localHistoryDate(charge.startDate, zone)
    date?.takeIf { it.year == year }?.monthValue?.let { it to charge }
}.groupBy({ it.first }, { it.second }).map { (month, charges) ->
    val energyValues = charges.mapNotNull { it.batteryInputKwh?.takeIf { value -> value.isFinite() && value >= 0.0 } }
    val costValues = charges.mapNotNull { it.cost?.takeIf { value -> value.isFinite() && value >= 0.0 } }
    val energyTotal = energyValues.takeIf { it.isNotEmpty() }?.sum()?.takeIf(Double::isFinite)
    val costTotal = costValues.takeIf { it.isNotEmpty() }?.sum()?.takeIf(Double::isFinite)
    MonthlyChargeAggregation(month, energyTotal, charges.size,
        if (energyTotal != null) energyValues.size else 0,
        costTotal, if (costTotal != null) costValues.size else 0)
}.sortedBy { it.month }

internal fun buildQualifiedMonthlyDriveAggregations(
    rows: List<DriveSummary>, localHistoryCarId: Int, year: Int,
    zone: ZoneId = ZoneId.systemDefault()
): List<MonthlyDriveAggregation> = rows.mapNotNull { row ->
    if (row.carId != localHistoryCarId) return@mapNotNull null
    val drive = row.toAnalysisDriveData()
    if (!isAnalysisEligible(drive.qualityState, drive.qualityReason)) return@mapNotNull null
    localHistoryDate(drive.startDate, zone)?.takeIf { it.year == year }?.monthValue?.let { it to drive }
}.groupBy({ it.first }, { it.second }).map { (month, drives) ->
    val distances = drives.mapNotNull { it.distanceKm?.takeIf { value -> value.isFinite() && value >= 0.0 } }
    val energies = drives.mapNotNull { it.netEnergyKwh?.takeIf(Double::isFinite) }
    val totalDistance = distances.takeIf { it.isNotEmpty() }?.sum()?.takeIf(Double::isFinite)
    val totalEnergy = energies.takeIf { it.isNotEmpty() }?.sum()?.takeIf(Double::isFinite)
    MonthlyDriveAggregation(month, totalDistance, totalEnergy, drives.size,
        if (totalDistance != null) distances.size else 0,
        if (totalEnergy != null) energies.size else 0)
}.sortedBy { it.month }

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
    private val vehicleContextRepository: com.matelink.data.local.VehicleContextRepository,
    private val chargeCostOverrideStore: ChargeCostOverrideStore
) {

    private suspend fun resolveHistoryCarId(remoteApiCarId: Int): Int {
        return vehicleContextRepository.resolveRemote(remoteApiCarId).localHistoryCarId
    }

    /** A request owns one identity proof and calendar, including across Room suspensions. */
    private inner class StatsRead(
        val scope: HistoryReadScope,
        val context: VehicleContext,
        val zone: ZoneId,
        private val coroutine: CoroutineContext
    ) {
        val carId: Int get() = context.localHistoryCarId
        fun verify() {
            coroutine.ensureActive()
            if (!vehicleContextRepository.isHistoryContextCurrentNow(scope, context))
                throw HistoryIdentityUnavailableException()
        }
        suspend fun <T> read(block: suspend () -> T): T {
            verify()
            val result = block()
            verify()
            return result
        }
    }

    private suspend fun beginStatsRead(remoteId: Int): StatsRead {
        val scope = vehicleContextRepository.captureReadScope()
        // Statistics are local reads; a previously verified scoped mapping is sufficient.
        val context = vehicleContextRepository.cachedContextForRemote(remoteId, scope)
            ?: vehicleContextRepository.resolveRemote(remoteId)
        return StatsRead(scope, context, ZoneId.systemDefault(), currentCoroutineContext()).also { it.verify() }
    }

    /**
     * Get complete stats for a car with the given year filter.
     */
    suspend fun getStats(carId: Int, yearFilter: YearFilter): CarStats {
        val request = beginStatsRead(carId)
        val analysis = getRecommendationsAndCoverage(request, yearFilter)
        val quickStats = getQuickStats(request, analysis)
        val deepStats = getDeepStats(request, yearFilter, StatsCohort(analysis.drives, analysis.charges))
        val syncProgress = request.read { syncManager.getProgressForCar(request.carId) }
        request.verify()

        return CarStats(
            carId = carId,
            yearFilter = yearFilter,
            quickStats = qualifiedQuickStatsTotals(quickStats, analysis.coverage).copy(
                totalEnergyAddedKwh = analysis.coverage.qualifiedChargeEnergyTotalKwh ?: 0.0,
                totalCost = analysis.cost.totals.knownTotal,
                avgCostPerKwh = analysis.cost.totals.pairedUnitCost
            ),
            deepStats = deepStats,
            syncProgress = syncProgress,
            recommendations = analysis.recommendations,
            analysisCoverage = analysis.coverage
        )
    }

    private suspend fun readStatsCohort(request: StatsRead, yearFilter: YearFilter): StatsCohort {
        val historyCarId = request.carId
        val (drives, charges) = when (yearFilter) {
            is YearFilter.AllTime -> request.read { driveSummaryDao.getAllChronological(historyCarId) } to
                request.read { chargeSummaryDao.getAllForCar(historyCarId) }
            is YearFilter.Year -> {
                val (start, end) = chargeYearCandidateBounds(yearFilter.year)
                request.read { driveSummaryDao.getHistoryDrivesInRange(historyCarId, start, end) } to
                    request.read { chargeSummaryDao.getHistoryChargesInRange(historyCarId, start, end) }
            }
        }
        fun belongs(raw: String?): Boolean {
            val date = localHistoryDate(raw, request.zone) ?: return false
            return yearFilter is YearFilter.AllTime || date.year == (yearFilter as YearFilter.Year).year
        }
        // Room summaries retain legacy non-null columns for schema compatibility.
        // Rehydrate through the neutral API models before analytics so placeholder
        // zeros are not treated as observed measurements.
        val selectedDrives = drives.filter { it.carId == historyCarId }.map { it to it.toAnalysisDriveData() }
            .filter { (_, data) -> isAnalysisEligible(data.qualityState, data.qualityReason) && belongs(data.startDate) }
        val selectedChargeRows = charges.filter { it.carId == historyCarId }.map { it to it.toAnalysisChargeData() }
            .filter { (_, data) -> isAnalysisEligible(data.qualityState, data.qualityReason) && belongs(data.startDate) }
        request.verify()
        return StatsCohort(selectedDrives, selectedChargeRows)
    }

    private suspend fun getRecommendationsAndCoverage(request: StatsRead, yearFilter: YearFilter) = request.read {
        withContext(Dispatchers.Default) {
            val cohort = readStatsCohort(request, yearFilter)
            val selectedDrives = cohort.drives
            val selectedChargeRows = cohort.charges
            val historyCarId = request.carId
            val analysisDrives = selectedDrives.map { it.second }
            val analysisCharges = selectedChargeRows.map { it.second }
            val manualTotals = if (analysisCharges.isEmpty()) emptyMap() else request.read { chargeCostOverrideStore.getAll() }
            val cost = projectStatsChargeCosts(analysisCharges, historyCarId, manualTotals, yearFilter, request.zone)
            val selectedCharges = cost.charges
            val recommendations = buildRecommendations(
                buildRecommendationEvidence(
                    drives = analysisDrives.map {
                        RecommendationDriveSample(
                            distanceKm = it.distanceKm,
                            energyKwh = it.netEnergyKwh,
                            averageSpeedKmh = it.speedAvgKmh,
                            outsideTemperatureC = it.outsideTempAvg,
                            observedAt = localHistoryDate(it.startDate, request.zone)?.toString()
                        )
                    },
                    charges = selectedCharges.map {
                        RecommendationChargeSample(
                            energyAddedKwh = it.batteryInputKwh,
                            energyUsedKwh = qualifiedAcEnergyBalance(it.energyContract, it.startDate, it.endDate, it.chargeType)?.inputKwh,
                            observedAt = localHistoryDate(it.startDate, request.zone)?.toString()
                        )
                    }
                )
            )
            val coverage = buildAnalysisCoverage(
                drives = analysisDrives.map {
                    AnalysisDriveCoverageSample(
                        distanceKm = it.distanceKm,
                        energyKwh = it.netEnergyKwh,
                        observedAt = localHistoryDate(it.startDate, request.zone)?.toString(),
                        energyQuality = it.energyContract?.netEnergy?.quality
                    )
                },
                charges = selectedCharges.map {
                    AnalysisChargeCoverageSample(
                        energyAddedKwh = it.batteryInputKwh,
                        cost = cost.effectiveCosts[it.chargeId],
                        observedAt = localHistoryDate(it.startDate, request.zone)?.toString(),
                        energyUsedKwh = qualifiedAcEnergyBalance(it.energyContract, it.startDate, it.endDate, it.chargeType)?.inputKwh,
                        acBalanceQualified = qualifiedAcEnergyBalance(it.energyContract, it.startDate, it.endDate, it.chargeType) != null
                    )
                }
            )
            StatsAnalysis(recommendations, coverage, cost, selectedDrives, selectedChargeRows)
        }
    }

    /** All summary values and records come from the same qualified, locally dated cohort. */
    private suspend fun getQuickStats(request: StatsRead, analysis: StatsAnalysis): QuickStats {
        val drives = analysis.drives
        val charges = analysis.charges
        fun driveRecord(smallest: Boolean = false, value: (DriveData) -> Double?): DriveSummary? {
            val candidates = drives.mapNotNull { (row, data) -> value(data)?.takeIf(Double::isFinite)?.let { row to it } }
            return (if (smallest) candidates.minByOrNull { it.second } else candidates.maxByOrNull { it.second })?.first
        }
        fun chargeRecord(value: (ChargeData) -> Double?): ChargeSummary? = charges.mapNotNull { (row, data) ->
            value(data)?.takeIf { it.isFinite() && it >= 0.0 }?.let { row to it }
        }.maxByOrNull { it.second }?.first
        val byDay = drives.groupBy { localHistoryDate(it.second.startDate, request.zone)!!.toString() }
        val days = byDay.keys.sorted()
        val dailyDistance = byDay.mapValues { (_, rows) ->
            rows.mapNotNull { it.second.distanceKm?.takeIf { value -> value.isFinite() && value >= 0.0 } }.sum()
        }
        fun instant(raw: String?) = parseIsoInstant(raw)
            ?: localHistoryDate(raw, request.zone)?.atStartOfDay(request.zone)?.toInstant()
        fun gap(rows: List<Pair<String?, String?>>): GapRecord? = rows
            .sortedBy { instant(it.first) }.zipWithNext().mapNotNull { (previous, current) ->
                val end = instant(previous.second) ?: return@mapNotNull null
                val start = instant(current.first) ?: return@mapNotNull null
                val daysBetween = java.time.Duration.between(end, start).toMillis() / 86_400_000.0
                if (daysBetween < 0.0) null else GapRecord(daysBetween, previous.second!!, current.first!!)
            }.maxByOrNull { it.gapDays }
        val chargeOrder = charges.sortedBy { instant(it.second.startDate) }
        // Prefix sums avoid re-scanning the entire drive archive for every charge gap.
        val distanceTimeline = drives.mapNotNull { (_, data) ->
            val at = instant(data.startDate) ?: return@mapNotNull null
            data.distanceKm?.takeIf { it.isFinite() && it >= 0.0 }?.let { at to it }
        }.sortedBy { it.first }
        val prefixDistance = DoubleArray(distanceTimeline.size + 1)
        distanceTimeline.forEachIndexed { index, entry -> prefixDistance[index + 1] = prefixDistance[index] + entry.second }
        fun boundary(at: java.time.Instant, includeEqual: Boolean): Int {
            var low = 0
            var high = distanceTimeline.size
            while (low < high) {
                val middle = (low + high) / 2
                val time = distanceTimeline[middle].first
                if (time < at || (includeEqual && time == at)) low = middle + 1 else high = middle
            }
            return low
        }
        val range = chargeOrder.zipWithNext().mapNotNull { (previous, current) ->
            val after = instant(previous.second.endDate) ?: return@mapNotNull null
            val before = instant(current.second.startDate) ?: return@mapNotNull null
            if (before <= after) return@mapNotNull null
            val distance = prefixDistance[boundary(before, false)] - prefixDistance[boundary(after, true)]
            if (distance <= 0.0) null else MaxDistanceBetweenChargesRecord(distance,
                previous.first.chargeId, current.first.chargeId, previous.second.endDate!!, current.second.startDate!!)
        }.maxByOrNull { it.distance }
        fun battery(id: Int, date: String?, start: Int?, end: Int?, charge: Boolean): BatteryChangeRecord? {
            if (date == null || start == null || end == null || start !in 0..100 || end !in 0..100) return null
            val change = if (charge) end - start else start - end
            return if (change <= 0) null else BatteryChangeRecord(change, start, end, id, date, charge)
        }
        return QuickStats(
            totalDrives = drives.size,
            totalDistanceKm = analysis.coverage.qualifiedDistanceTotalKm ?: 0.0,
            totalEnergyConsumedKwh = analysis.coverage.qualifiedDriveEnergyTotalKwh,
            avgEfficiencyWhKm = analysis.coverage.pairedEfficiency?.efficiencyWhKm,
            maxSpeedKmh = drives.mapNotNull { it.second.speedMaxKmh?.takeIf { speed -> speed.isFinite() && speed >= 0.0 } }.maxOrNull()?.toInt(),
            avgDriveMinutes = drives.mapNotNull { it.second.durationMin?.takeIf { duration -> duration >= 0 } }.takeIf { it.isNotEmpty() }?.average(),
            totalDrivingDays = days.size,
            totalCharges = charges.size,
            totalEnergyAddedKwh = analysis.coverage.qualifiedChargeEnergyTotalKwh ?: 0.0,
            totalCost = analysis.cost.totals.knownTotal,
            avgCostPerKwh = analysis.cost.totals.pairedUnitCost,
            avgChargeMinutes = charges.mapNotNull { it.second.durationMin?.takeIf { duration -> duration >= 0 } }.takeIf { it.isNotEmpty() }?.average(),
            longestDrive = driveRecord { it.distanceKm?.takeIf { distance -> distance >= 0.0 } },
            fastestDrive = driveRecord { it.speedMaxKmh?.takeIf { speed -> speed >= 0.0 } },
            mostEfficientDrive = driveRecord(true) { it.efficiencyWhKm?.takeIf { value -> value > 0.0 && (it.distanceKm ?: 0.0) > 5.0 } },
            leastEfficientDrive = driveRecord { it.efficiencyWhKm?.takeIf { value -> value > 0.0 && (it.distanceKm ?: 0.0) > 5.0 } },
            biggestCharge = chargeRecord { it.batteryInputKwh },
            mostExpensiveCharge = chargeRecord { it.cost },
            mostExpensivePerKwhCharge = chargeRecord { charge ->
                val energy = charge.batteryInputKwh?.takeIf { it.isFinite() && it > 0.0 }
                val cost = charge.cost?.takeIf { it.isFinite() && it >= 0.0 }
                if (energy != null && cost != null) cost / energy else null
            },
            // Keep these two labels as the first-ever dates, including in a year view.
            firstDriveDate = request.read { driveSummaryDao.firstDriveDate(request.carId) },
            firstChargeDate = request.read { chargeSummaryDao.firstChargeDate(request.carId) },
            busiestDay = byDay.maxByOrNull { it.value.size }?.let { com.matelink.data.local.dao.BusiestDayResult(it.key, it.value.size) },
            mostDistanceDay = dailyDistance.maxByOrNull { it.value }?.let { com.matelink.data.local.dao.MostDistanceDayResult(it.key, it.value) },
            maxDistanceBetweenCharges = range,
            longestGapWithoutCharging = gap(charges.map { it.second.startDate to it.second.endDate }),
            longestGapWithoutDriving = gap(drives.map { it.second.startDate to it.second.endDate }),
            longestDrivingStreak = computeLongestStreak(days),
            biggestBatteryGainCharge = charges.mapNotNull { (_, data) -> battery(data.chargeId, data.startDate, data.startBatteryLevel, data.endBatteryLevel, true) }.maxByOrNull { it.percentChange },
            biggestBatteryDrainDrive = drives.mapNotNull { (_, data) -> battery(data.driveId, data.startDate, data.startBatteryLevel, data.endBatteryLevel, false) }.maxByOrNull { it.percentChange }
        )
    }

    /**
     * Get deep stats (from aggregate tables, requires sync).
     * Returns null if no aggregates exist yet.
     */
    suspend fun getDeepStats(carId: Int, yearFilter: YearFilter): DeepStats? {
        return getDeepStats(beginStatsRead(carId), yearFilter)
    }

    private suspend fun getDeepStats(
        request: StatsRead,
        yearFilter: YearFilter,
        cohort: StatsCohort? = null
    ): DeepStats? {
        if (yearFilter is YearFilter.Year) {
            return getDeepStatsForYear(request, cohort ?: readStatsCohort(request, yearFilter))
        }
        val historyCarId = request.carId
        val driveAggregates = request.read { aggregateDao.countDriveAggregates(historyCarId) }
        val chargeAggregates = request.read { aggregateDao.countChargeAggregates(historyCarId) }
        if (driveAggregates == 0 && chargeAggregates == 0) return null
        return getDeepStatsAllTime(request, historyCarId, driveAggregates, chargeAggregates)
    }

    private suspend fun getDeepStatsAllTime(request: StatsRead, carId: Int, driveCount: Int, chargeCount: Int): DeepStats {
        // Elevation records
        val driveWithMaxElev = request.read { aggregateDao.driveWithMaxElevation(carId) }
        val driveWithMinElev = request.read { aggregateDao.driveWithMinElevation(carId) }
        val driveWithMostGain = request.read { aggregateDao.driveWithMostElevationGain(carId) }

        // Temperature records (driving)
        val hottestDriveAgg = request.read { aggregateDao.hottestDrive(carId) }
        val coldestDriveAgg = request.read { aggregateDao.coldestDrive(carId) }

        // Temperature records (charging)
        val hottestChargeAgg = request.read { aggregateDao.hottestCharge(carId) }
        val coldestChargeAgg = request.read { aggregateDao.coldestCharge(carId) }

        // Power record
        val chargeWithMaxPowerAgg = request.read { aggregateDao.chargeWithMaxPower(carId) }

        return DeepStats(
            maxElevationM = request.read { aggregateDao.maxElevation(carId) },
            minElevationM = request.read { aggregateDao.minElevation(carId) },
            driveWithMaxElevation = driveWithMaxElev?.let { agg ->
                val drive = request.read { driveSummaryDao.get(carId, agg.driveId) }
                DriveElevationRecord(
                    driveId = agg.driveId,
                    elevationM = agg.maxElevation,
                    elevationGainM = agg.elevationGain,
                    date = drive?.startDate
                )
            },
            driveWithMinElevation = driveWithMinElev?.let { agg ->
                val drive = request.read { driveSummaryDao.get(carId, agg.driveId) }
                DriveElevationRecord(
                    driveId = agg.driveId,
                    elevationM = agg.minElevation,
                    elevationGainM = agg.elevationGain,
                    date = drive?.startDate
                )
            },
            driveWithMostClimbing = driveWithMostGain?.let { agg ->
                val drive = request.read { driveSummaryDao.get(carId, agg.driveId) }
                DriveElevationRecord(
                    driveId = agg.driveId,
                    elevationM = agg.maxElevation,
                    elevationGainM = agg.elevationGain,
                    date = drive?.startDate
                )
            },

            maxOutsideTempDrivingC = request.read { aggregateDao.maxOutsideTempDriving(carId) },
            minOutsideTempDrivingC = request.read { aggregateDao.minOutsideTempDriving(carId) },
            maxCabinTempC = request.read { aggregateDao.maxInsideTemp(carId) },
            minCabinTempC = request.read { aggregateDao.minInsideTemp(carId) },
            hottestDrive = hottestDriveAgg?.let { agg ->
                val drive = request.read { driveSummaryDao.get(carId, agg.driveId) }
                DriveTempRecord(
                    driveId = agg.driveId,
                    tempC = agg.maxOutsideTemp,
                    date = drive?.startDate
                )
            },
            coldestDrive = coldestDriveAgg?.let { agg ->
                val drive = request.read { driveSummaryDao.get(carId, agg.driveId) }
                DriveTempRecord(
                    driveId = agg.driveId,
                    tempC = agg.minOutsideTemp,
                    date = drive?.startDate
                )
            },

            maxOutsideTempChargingC = request.read { aggregateDao.maxOutsideTempCharging(carId) },
            minOutsideTempChargingC = request.read { aggregateDao.minOutsideTempCharging(carId) },
            hottestCharge = hottestChargeAgg?.let { agg ->
                val charge = request.read { chargeSummaryDao.get(carId, agg.chargeId) }
                ChargeTempRecord(
                    chargeId = agg.chargeId,
                    tempC = agg.maxOutsideTemp,
                    date = charge?.startDate
                )
            },
            coldestCharge = coldestChargeAgg?.let { agg ->
                val charge = request.read { chargeSummaryDao.get(carId, agg.chargeId) }
                ChargeTempRecord(
                    chargeId = agg.chargeId,
                    tempC = agg.minOutsideTemp,
                    date = charge?.startDate
                )
            },

            maxChargerPowerKw = request.read { aggregateDao.maxChargerPower(carId) },
            chargeWithMaxPower = chargeWithMaxPowerAgg?.let { agg ->
                val charge = request.read { chargeSummaryDao.get(carId, agg.chargeId) }
                ChargePowerRecord(
                    chargeId = agg.chargeId,
                    powerKw = agg.maxChargerPower,
                    date = charge?.startDate
                )
            },

            acChargeCount = request.read { aggregateDao.countAcCharges(carId) },
            dcChargeCount = request.read { aggregateDao.countDcCharges(carId) },
            acChargeEnergyKwh = request.read { aggregateDao.sumAcChargeEnergy(carId) },
            dcChargeEnergyKwh = request.read { aggregateDao.sumDcChargeEnergy(carId) },

            countriesVisitedCount = request.read { aggregateDao.countUniqueCountries(carId) }.takeIf { it > 0 },

            driveDetailsProcessed = driveCount,
            chargeDetailsProcessed = chargeCount
        )
    }

    private suspend fun getDeepStatsForYear(request: StatsRead, cohort: StatsCohort): DeepStats? {
        // RFC3339 strings with different offsets cannot form a local-calendar SQL window.
        // Bind the qualified summary IDs instead; each batch stays below SQLite's bind limit.
        val drives = mutableListOf<DriveDetailAggregate>()
        for (ids in cohort.drives.map { it.first.driveId }.chunked(400)) {
            drives += request.read { aggregateDao.getDriveAggregatesForStats(request.carId, ids) }
        }
        val charges = mutableListOf<ChargeDetailAggregate>()
        for (ids in cohort.charges.map { it.first.chargeId }.chunked(400)) {
            charges += request.read { aggregateDao.getChargeAggregatesForStats(request.carId, ids) }
        }
        if (drives.isEmpty() && charges.isEmpty()) return null
        val driveDates = cohort.drives.associate { it.first.driveId to it.second.startDate }
        val chargeDates = cohort.charges.associate { it.first.chargeId to it.second.startDate }
        val chargeEnergy = cohort.charges.associate { it.first.chargeId to it.second.batteryInputKwh }
        fun <T> extreme(rows: List<T>, minimum: Boolean = false, value: (T) -> Double?): T? {
            val known = rows.mapNotNull { row -> value(row)?.takeIf(Double::isFinite)?.let { row to it } }
            return (if (minimum) known.minByOrNull { it.second } else known.maxByOrNull { it.second })?.first
        }
        val maxElevation = extreme(drives.filter { it.hasElevationData }) { it.maxElevation?.toDouble() }
        val mostGain = extreme(drives) { it.elevationGain?.toDouble() }
        val hottestDrive = extreme(drives) { it.maxOutsideTemp }
        val coldestDrive = extreme(drives, true) { it.minOutsideTemp }
        val hottestCharge = extreme(charges) { it.maxOutsideTemp }
        val coldestCharge = extreme(charges, true) { it.minOutsideTemp }
        val maxPower = extreme(charges) { it.maxChargerPower?.toDouble() }
        fun energy(dc: Boolean): Double = charges.filter { it.isFastCharger == dc }
            .mapNotNull { chargeEnergy[it.chargeId]?.takeIf { value -> value.isFinite() && value >= 0.0 } }.sum()
        request.verify()
        return DeepStats(
            maxElevationM = maxElevation?.maxElevation,
            minElevationM = null, // Preserve the annual view's existing field contract.
            driveWithMaxElevation = maxElevation?.let {
                DriveElevationRecord(it.driveId, it.maxElevation, it.elevationGain, driveDates[it.driveId])
            },
            driveWithMinElevation = null,
            driveWithMostClimbing = mostGain?.let {
                DriveElevationRecord(it.driveId, it.maxElevation, it.elevationGain, driveDates[it.driveId])
            },
            maxOutsideTempDrivingC = hottestDrive?.maxOutsideTemp,
            minOutsideTempDrivingC = coldestDrive?.minOutsideTemp,
            maxCabinTempC = extreme(drives) { it.maxInsideTemp }?.maxInsideTemp,
            minCabinTempC = extreme(drives, true) { it.minInsideTemp }?.minInsideTemp,
            hottestDrive = hottestDrive?.let { DriveTempRecord(it.driveId, it.maxOutsideTemp, driveDates[it.driveId]) },
            coldestDrive = coldestDrive?.let { DriveTempRecord(it.driveId, it.minOutsideTemp, driveDates[it.driveId]) },
            maxOutsideTempChargingC = hottestCharge?.maxOutsideTemp,
            minOutsideTempChargingC = coldestCharge?.minOutsideTemp,
            hottestCharge = hottestCharge?.let { ChargeTempRecord(it.chargeId, it.maxOutsideTemp, chargeDates[it.chargeId]) },
            coldestCharge = coldestCharge?.let { ChargeTempRecord(it.chargeId, it.minOutsideTemp, chargeDates[it.chargeId]) },
            maxChargerPowerKw = maxPower?.maxChargerPower,
            chargeWithMaxPower = maxPower?.let { ChargePowerRecord(it.chargeId, it.maxChargerPower, chargeDates[it.chargeId]) },
            acChargeCount = charges.count { !it.isFastCharger },
            dcChargeCount = charges.count { it.isFastCharger },
            acChargeEnergyKwh = energy(false),
            dcChargeEnergyKwh = energy(true),
            countriesVisitedCount = drives.mapNotNull { it.startCountryCode }.distinct().size.takeIf { it > 0 },
            driveDetailsProcessed = drives.size,
            chargeDetailsProcessed = charges.size
        )
    }

    /**
     * Get available years for the year filter dropdown.
     */
    suspend fun getAvailableYears(carId: Int): List<Int> {
        val request = beginStatsRead(carId)
        val cohort = readStatsCohort(request, YearFilter.AllTime)
        val years = (cohort.drives.mapNotNull { localHistoryDate(it.second.startDate, request.zone)?.year } +
            cohort.charges.mapNotNull { localHistoryDate(it.second.startDate, request.zone)?.year })
            .distinct().sortedDescending()
        request.verify()
        return years
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
        val processedDrives = aggregateDao.countDriveAggregatesWithSchema(historyCarId, SchemaVersion.DRIVE_ENERGY)
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
                aggregateDao.observeDriveAggregateCountWithSchema(historyCarId, SchemaVersion.DRIVE_ENERGY),
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

    suspend fun getMonthlyDriveAggregation(carId: Int, year: Int): List<MonthlyDriveAggregation> {
        val request = beginStatsRead(carId)
        val (start, end) = chargeYearCandidateBounds(year)
        val rows = request.read { driveSummaryDao.getHistoryDrivesInRange(request.carId, start, end) }
        val monthly = buildQualifiedMonthlyDriveAggregations(rows, request.carId, year, request.zone)
        request.verify()
        return monthly
    }

    suspend fun getMonthlyChargeAggregation(carId: Int, year: Int): List<MonthlyChargeAggregation> {
        val request = beginStatsRead(carId)
        val (start, end) = chargeYearCandidateBounds(year)
        val rows = request.read { chargeSummaryDao.getHistoryChargesInRange(request.carId, start, end) }
        val monthly = buildQualifiedMonthlyChargeAggregations(rows, request.carId, year, request.zone)
        request.verify()
        return monthly
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
