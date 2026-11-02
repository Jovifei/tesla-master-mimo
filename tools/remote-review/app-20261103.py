from pathlib import Path
r = Path.cwd()
def put(p, text):
    q = r / p
    q.parent.mkdir(parents=True, exist_ok=True)
    q.write_text(text)
def replace(p, old, new, n=1):
    text = (r / p).read_text()
    assert text.count(old) == n, (p, old[:80], text.count(old), n)
    put(p, text.replace(old, new))
root = 'android/app/src/main/java/com/matelink/'
u = root + 'data/repository/UnifiedHistoryRepository.kt'
replace(u, 'data class UnifiedHistory(', '''/** A page reads only its declared data family; the combined entry remains available. */
internal enum class HistoryFamilies(val drives: Boolean, val charges: Boolean) {
    ALL(true, true), DRIVES(true, false), CHARGES(false, true)
}

data class UnifiedHistory(''')
replace(u, '    val localArchiveLinkPending: Boolean = false\n)', '''    val localArchiveLinkPending: Boolean = false,
    /** Request success does not prove that every retained local receipt was refreshed. */
    val drivesIncludeCachedEvidence: Boolean = false,
    val chargesIncludeCachedEvidence: Boolean = false
)''')
replace(u, '    suspend fun loadCached(remoteApiCarId: Int): ApiResult<UnifiedHistory> {', '''    suspend fun loadCached(remoteApiCarId: Int): ApiResult<UnifiedHistory> =
        loadCached(remoteApiCarId, HistoryFamilies.ALL)

    internal suspend fun loadCached(remoteApiCarId: Int, families: HistoryFamilies): ApiResult<UnifiedHistory> {''')
replace(u, '''        val drives = reads.localDrives(context.localHistoryCarId)
        val charges = reads.localCharges(context.localHistoryCarId)''', '''        val drives = if (families.drives) reads.localDrives(context.localHistoryCarId) else emptyList()
        val charges = if (families.charges) reads.localCharges(context.localHistoryCarId) else emptyList()''')
replace(u, '            drivesSyncError = "cached_only", chargesSyncError = "cached_only"', '''            drivesSyncError = if (families.drives) "cached_only" else null,
            chargesSyncError = if (families.charges) "cached_only" else null,
            drivesIncludeCachedEvidence = drives.isNotEmpty(),
            chargesIncludeCachedEvidence = charges.isNotEmpty()''')
replace(u, '''    /** Live consumers adopt this read's identity before any history endpoint can stall. */
    internal suspend fun load(''', '''    internal suspend fun loadDrives(
        remoteApiCarId: Int, startDate: String? = null, endDate: String? = null,
        onResolvedContext: suspend (VerifiedHistoryReadContext) -> Unit = {}
    ): ApiResult<UnifiedHistory> = load(remoteApiCarId, startDate, endDate,
        HistoryFamilies.DRIVES, onResolvedContext)

    internal suspend fun loadCharges(
        remoteApiCarId: Int, startDate: String? = null, endDate: String? = null
    ): ApiResult<UnifiedHistory> = load(remoteApiCarId, startDate, endDate, HistoryFamilies.CHARGES) {}

    /** Live consumers adopt this read's identity before any history endpoint can stall. */
    internal suspend fun load(''')
replace(u, '''        endDate: String?,
        onResolvedContext: suspend (VerifiedHistoryReadContext) -> Unit''', '''        endDate: String?,
        families: HistoryFamilies = HistoryFamilies.ALL,
        onResolvedContext: suspend (VerifiedHistoryReadContext) -> Unit''')
replace(u, '''        val localDrives = reads.localDrives(context.localHistoryCarId)
            .filter { historyInRange(it.startDate, startDate, endDate) }
        val localCharges = reads.localCharges(context.localHistoryCarId)
            .filter { historyInRange(it.startDate, startDate, endDate) }''', '''        val localDrives = if (families.drives) reads.localDrives(context.localHistoryCarId)
            .filter { historyInRange(it.startDate, startDate, endDate) } else emptyList()
        val localCharges = if (families.charges) reads.localCharges(context.localHistoryCarId)
            .filter { historyInRange(it.startDate, startDate, endDate) } else emptyList()''')
replace(u, '        val remoteDrives = if (canReadHistory) {', '        val remoteDrives = if (canReadHistory && families.drives) {')
replace(u, '        } else HistoryPageLoad<DriveData>(emptyList(), unavailable)', '        } else HistoryPageLoad<DriveData>(emptyList(), if (families.drives) unavailable else null)')
replace(u, '            reads.persistDrives(driveRows)', '            if (families.drives) reads.persistDrives(driveRows)')
replace(u, '        val remoteCharges = if (canReadHistory) {', '        val remoteCharges = if (canReadHistory && families.charges) {')
replace(u, '        } else HistoryPageLoad<ChargeData>(emptyList(), unavailable)', '        } else HistoryPageLoad<ChargeData>(emptyList(), if (families.charges) unavailable else null)')
replace(u, '            reads.persistCharges(chargeRows)', '            if (families.charges) reads.persistCharges(chargeRows)')
replace(u, '''                drivesFromRemote = !driveArchiveBlocked && remoteDrives.error == null,
                chargesFromRemote = !chargeArchiveBlocked && remoteCharges.error == null,''', '''                drivesFromRemote = families.drives && !driveArchiveBlocked && canReadHistory && remoteDrives.error == null,
                chargesFromRemote = families.charges && !chargeArchiveBlocked && canReadHistory && remoteCharges.error == null,
                drivesIncludeCachedEvidence = driveArchiveBlocked || rawDrives.any {
                    remoteDriveCandidates[it.driveId]?.withLegacyRemoteQuality() != it
                },
                chargesIncludeCachedEvidence = chargeArchiveBlocked || rawCharges.any {
                    remoteChargeCandidates[it.chargeId]?.withLegacyRemoteQuality() != it
                },''')
a = root + 'domain/analytics/AnalysisHistoryRepository.kt'
replace(a, 'import com.matelink.data.repository.UnifiedHistoryRepository', '''import com.matelink.data.repository.UnifiedHistoryRepository
import com.matelink.data.repository.HistoryFamilies
import com.matelink.data.repository.UnifiedHistory''')
replace(a, 'filter { isAnalysisEligible(it.qualityState, it.qualityReason) }\n    return AnalysisHistorySnapshot(', '''filter {
        isAnalysisEligible(it.qualityState, it.qualityReason) ||
            (!it.qualityState.equals("quarantined", ignoreCase = true) && it.partialBatteryInputKwh != null)
    }
    return AnalysisHistorySnapshot(''')
replace(a, '''    private suspend fun project(
        carId: Int,
        result: ApiResult<com.matelink.data.repository.UnifiedHistory>
    )''', '''    internal suspend fun loadDrives(carId: Int): ApiResult<AnalysisHistorySnapshot> =
        project(carId, historyRepository.loadDrives(carId), HistoryFamilies.DRIVES)

    internal suspend fun loadCharges(carId: Int): ApiResult<AnalysisHistorySnapshot> =
        project(carId, historyRepository.loadCharges(carId), HistoryFamilies.CHARGES)

    internal suspend fun loadCachedDrives(carId: Int): ApiResult<AnalysisHistorySnapshot> =
        project(carId, historyRepository.loadCached(carId, HistoryFamilies.DRIVES), HistoryFamilies.DRIVES)

    internal suspend fun loadCachedCharges(carId: Int): ApiResult<AnalysisHistorySnapshot> =
        project(carId, historyRepository.loadCached(carId, HistoryFamilies.CHARGES), HistoryFamilies.CHARGES)

    private suspend fun project(
        carId: Int,
        result: ApiResult<UnifiedHistory>,
        families: HistoryFamilies = HistoryFamilies.ALL
    )''')
replace(a, 'val eligibleCharges = history.charges.filter { isAnalysisEligible(it.qualityState, it.qualityReason) }', '''val eligibleCharges = history.charges.filter {
                    isAnalysisEligible(it.qualityState, it.qualityReason) ||
                        (!it.qualityState.equals("quarantined", ignoreCase = true) && it.partialBatteryInputKwh != null)
                }''')
replace(a, '''                    freshness = if (history.drivesFromRemote && history.chargesFromRemote) {
                        HistoryFreshness.FRESH
                    } else {
                        HistoryFreshness.STALE
                    },
                    staleReason = if (history.drivesFromRemote && history.chargesFromRemote) null else "remote unavailable",''', '''                    freshness = history.analysisFreshness(families),
                    staleReason = if (history.analysisFreshness(families) == HistoryFreshness.FRESH)
                        null else "retained local evidence or refresh unavailable",''')
put(a, (r/a).read_text() + '''
/** Request success and receipt coverage are separate; absence never deletes the archive. */
internal fun UnifiedHistory.analysisFreshness(families: HistoryFamilies): HistoryFreshness {
    val drivesCurrent = !families.drives || (drivesFromRemote && !drivesIncludeCachedEvidence)
    val chargesCurrent = !families.charges || (chargesFromRemote && !chargesIncludeCachedEvidence)
    return if (drivesCurrent && chargesCurrent) HistoryFreshness.FRESH else HistoryFreshness.STALE
}
''')
for path in ['ui/screens/efficiency/EfficiencyViewModel.kt', 'ui/screens/range/RangeViewModel.kt']:
    replace(root+path, 'historyRepository.load(carId)', 'historyRepository.loadDrives(carId)')
    replace(root+path, 'historyRepository.loadCached(carId)', 'historyRepository.loadCachedDrives(carId)')
b = root + 'ui/screens/battery/BatteryViewModel.kt'
replace(b, 'historyRepository.loadCached(id)', 'historyRepository.loadCachedDrives(id)')
replace(b, 'historyRepository.load(id)', 'historyRepository.loadDrives(id)')
replace(b, '''                val statusResult = repository.getCarStatus(id)
                requireVehicle()
                val historyResult = try {''', '''                val statusResult = repository.getCarStatus(id)
                requireVehicle()
                // Status/health are independent observations. Do not hold the first usable
                // frame hostage to a history provider or an optional readiness request.
                synchronized(evidencePublicationLock) {
                    if (isBoundNow()) _uiState.update { previous ->
                        val live = (statusResult as? ApiResult.Success)?.data
                        previous.withHealthStatus(healthResult,
                            live?.status ?: previous.carStatus, live?.units ?: previous.units, null)
                            .copy(isCachedStatus = live == null && previous.carStatus != null)
                    }
                }
                val historyResult = try {''')
replace(root+'ui/screens/drives/DrivesViewModel.kt', 'historyRepository.load(id, startDateStr, endDateStr) { proof ->', 'historyRepository.loadDrives(id, startDateStr, endDateStr) { proof ->')
replace(root+'ui/screens/charges/ChargesViewModel.kt', 'historyRepository.load(id, startDateStr, endDateStr)', 'historyRepository.loadCharges(id, startDateStr, endDateStr)')
c = root + 'ui/screens/cost/CostViewModel.kt'
replace(c, '    val estimatedCost: Double? = null,', '''    val estimatedCost: Double? = null,
    val partialEstimatedCost: Double? = null,
    val partialEstimatedCoverage: Int = 0,''')
replace(c, 'import kotlinx.coroutines.flow.collect', 'import kotlinx.coroutines.flow.collect\nimport kotlinx.coroutines.flow.catch')
replace(c, 'historyRepository.loadCached(carId)', 'historyRepository.loadCachedCharges(carId)')
replace(c, 'historyRepository.load(carId)', 'historyRepository.loadCharges(carId)')
replace(c, '''                val records = chargeCostOverrideStore.getAllRecords()
                checkScope()''', '''                chargeCostOverrideStore.ensureLegacyMigrated()
                checkScope()''')
replace(c, '''                    checkScope()
                    if (!vehicleContexts.isHistoryContextCurrent(scope, publishedContext))''', '''                    val records = chargeCostOverrideStore.getAllRecords()
                    checkScope()
                    if (!vehicleContexts.isHistoryContextCurrent(scope, publishedContext))''')
replace(c, '''    init {
        viewModelScope.launch {
            vehicleContexts.historyScopeChanges.collect { scope ->''', '''    init {
        // Room user edits invalidate projections, not network history or authentication.
        viewModelScope.launch {
            chargeCostOverrideStore.observeAllRecords().catch { error ->
                if (error is CancellationException) throw error
            }.collect { records ->
                val scope = activeScope
                val context = activeContext
                if (scope != null && context != null) {
                    if (vehicleContexts.isHistoryContextCurrentNow(scope, context)) {
                        manualRecords = records
                        recalculate(_uiState.value.selectedWindow)
                    } else publishIdentityUnavailable(_uiState.value.selectedWindow)
                }
            }
        }
        viewModelScope.launch {
            vehicleContexts.historyScopeChanges.collect { scope ->''')
replace(c, '            estimatedCost = totals.estimatedTotal,', '''            estimatedCost = totals.estimatedTotal,
            partialEstimatedCost = totals.partialEstimatedTotal,
            partialEstimatedCoverage = totals.partialEstimatedCount,''')
cs = root + 'ui/screens/cost/CostScreen.kt'
replace(cs, 'private fun SummarySection(uiState: CostUiState, currencySymbol: String)', 'internal fun SummarySection(uiState: CostUiState, currencySymbol: String)')
replace(cs, '    val hasEnergy = uiState.energyCoverage > 0 && uiState.totalEnergy != null', '''    val hasPartial = uiState.partialEstimatedCoverage > 0 && uiState.partialEstimatedCost != null
    val hasEnergy = uiState.energyCoverage > 0 && uiState.totalEnergy != null''')
replace(cs, '                formatMoney(currencySymbol, estimate, 2),', '                "≈" + formatMoney(currencySymbol, estimate, 2),')
replace(cs, '    if (hasEnergy || hasPair) Row(', '''    if (hasPartial) {
        SummaryCard(Modifier.fillMaxWidth(), stringResource(R.string.cost_partial_observation_estimate),
            "≈" + formatMoney(currencySymbol, uiState.partialEstimatedCost!!, 2),
            stringResource(R.string.cost_partial_observation_coverage, uiState.partialEstimatedCoverage))
    }
    if (hasEnergy || hasPair) Row(''')
replace(cs, '    if (!hasCost && !hasEstimate && !hasEnergy) {', '    if (!hasCost && !hasEstimate && !hasPartial && !hasEnergy) {')
replace(cs, '    if (hasCost || hasEstimate) Text(', '    if (hasCost || hasEstimate || hasPartial) Text(')
replace(root+'ui/screens/drives/ParkedDetailScreen.kt', '                        "驻车详情",', '                        stringResource(R.string.parked_detail_title),')
for folder, zh in [('values', False), ('values-zh', True)]:
    put(f'android/app/src/main/res/{folder}/review_stage_strings.xml', '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n' + ('''    <string name="parked_detail_title">驻车详情</string>
    <string name="cost_partial_observation_estimate">已采集部分估算</string>
    <string name="cost_partial_observation_coverage">%1$d 次充电的已采集部分；不是整次账单，不计入完整电量或账单均价。</string>
''' if zh else '''    <string name="parked_detail_title">Parking details</string>
    <string name="cost_partial_observation_estimate">Observed-part estimate</string>
    <string name="cost_partial_observation_coverage">Observed part of %1$d charging sessions; not whole-session bills and excluded from full energy and bill averages.</string>
''') + '</resources>\n')
replace(root+'ui/navigation/MateLinkNavHost.kt', '                    onClick = { navigateToTopLevel(navController, dest, currentCarId) },', '''                    onClick = {
                        if (dest != selected) navigateToTopLevel(navController, dest, currentCarId)
                    },''')
ar = root+'ui/screens/reports/AnnualReportViewModel.kt'
replace(ar, '''                val annual = statsRepository.getAnnualSnapshot(id, year)
                request.requireCurrent()
                val currency = Currency.findByCode(annual.currencyCode).symbol
                var freshness = HistoryFreshness.FRESH''', '                var freshness = HistoryFreshness.STALE')
replace(ar, '''                request.requireCurrent()
                AnnualReportResult(annual.stats, annual.monthlyDrives, annual.monthlyCharges,''', '''                request.requireCurrent()
                // Read all annual aggregates from the same Room transaction AFTER refresh.
                val annual = statsRepository.getAnnualSnapshot(id, year)
                request.requireCurrent()
                val currency = Currency.findByCode(annual.currencyCode).symbol
                AnnualReportResult(annual.stats, annual.monthlyDrives, annual.monthlyCharges,''')
replace('android/app/build.gradle.kts', 'versionCode = 83', 'versionCode = 84')
replace('android/app/build.gradle.kts', 'versionName = "2.1.64"', 'versionName = "2.1.65"')
print('Wired App consumers prepared; tests are required before publication.')
