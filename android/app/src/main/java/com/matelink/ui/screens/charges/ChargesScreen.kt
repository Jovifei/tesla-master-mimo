package com.matelink.ui.screens.charges

import com.matelink.ui.components.EmptyHistoryVisual
import com.matelink.ui.components.historyReadErrorMessageRes
import com.matelink.ui.components.showHistoryReadFailure
import com.matelink.domain.model.UnitFormatter

import androidx.compose.foundation.background
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.BatteryStd
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ElectricBolt
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Paid
import androidx.compose.material.icons.filled.PriceCheck
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import com.matelink.ui.components.HistoryForegroundRefreshEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import com.matelink.R
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Bolt
import com.matelink.ui.components.AppleDateGroupHeader
import com.matelink.ui.components.HistoryDateGroup
import com.matelink.ui.theme.AppleTokens
import com.matelink.data.api.models.ChargeData
import com.matelink.data.repository.HISTORY_IDENTITY_UNAVAILABLE
import com.matelink.ui.components.BarChartData
import com.matelink.ui.components.BarSegment
import com.matelink.ui.components.DateRangePickerDialog
import com.matelink.ui.components.HistoryDateFilterMenu
import com.matelink.ui.components.CompactHistoryMetricStrip
import com.matelink.ui.components.HistoryReadNotices
import com.matelink.ui.components.HistoryArchiveNotice
import com.matelink.ui.components.HistoryOnceNotice
import com.matelink.ui.components.MetricPanelKind
import com.matelink.ui.components.MetricStatusPanel
import com.matelink.ui.components.InteractiveBarChart
import com.matelink.ui.components.MateLinkLoadingPlaceholder
import com.matelink.ui.components.MateLinkLoadingMark
import com.matelink.ui.components.MonthScrollIndicator
import com.matelink.ui.components.TelemetryMetricSpec
import com.matelink.ui.components.TelemetryMetricStrip
import com.matelink.ui.components.TelemetryPanel
import com.matelink.ui.components.rememberDebouncedLoading
import com.matelink.ui.components.formatEditorialDate
import com.matelink.ui.components.formatShortDate
import com.matelink.ui.components.parseListItemDate
import com.matelink.ui.theme.CarColorPalette
import com.matelink.ui.theme.CarColorPalettes
import com.matelink.ui.theme.telemetryPalette
import com.matelink.domain.analytics.ChargeCostSource
import com.matelink.domain.analytics.EffectiveChargeCost
import com.matelink.util.toChineseDisplayAddress
import java.time.LocalDate

data class ChargeEnergyPresentation(
    val energyKwh: Double?
)

internal fun presentChargeEnergy(energyKwh: Double?): ChargeEnergyPresentation =
    ChargeEnergyPresentation(energyKwh?.takeIf { it.isFinite() && it >= 0.0 })

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChargesScreen(
    carId: Int,
    exteriorColor: String? = null,
    onNavigateToChargeDetail: (Int) -> Unit = {},
    viewModel: ChargesViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val feedbackContext = LocalContext.current
    val isDarkTheme = isSystemInDarkTheme()
    val palette = telemetryPalette()
    var priceEditTarget by remember { mutableStateOf<ChargeData?>(null) }

    HistoryForegroundRefreshEffect { viewModel.refresh() }
    LaunchedEffect(carId) {
        viewModel.setCarId(carId)
    }

    LaunchedEffect(uiState.error) {
        uiState.error?.let { error ->
            val hasContent = uiState.charges.isNotEmpty()
            val retried = snackbarHostState.showHistoryReadFailure(feedbackContext, error, hasContent) {
                viewModel.clearError()
                viewModel.refresh()
            }
            if (!retried && hasContent) viewModel.clearError()
        }
    }

    val archiveNoticeVisible = HistoryArchiveNotice(uiState.localArchiveLinkPending, uiState.historyNoticeLocalCarId)
    HistoryOnceNotice("charge_estimate_v1", R.string.charge_estimate_once_notice,
        enabled = !archiveNoticeVisible && uiState.charges.isNotEmpty())

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.charges_title),
                        fontWeight = FontWeight.SemiBold
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = uiState.isRefreshing,
            onRefresh = { viewModel.refresh() },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            ChargesContent(
                isLoading = uiState.isLoading,
                isRefreshing = uiState.isRefreshing,
                isLoadingMore = uiState.isLoadingMore,
                canLoadMore = uiState.canLoadMore,
                onLoadMore = { viewModel.loadMore() },
                historySyncWarning = uiState.historySyncWarning,
                readError = uiState.error,
                localArchiveLinkPending = uiState.localArchiveLinkPending &&
                    uiState.historyNoticeLocalCarId?.let { it < 0 } != true,
                charges = uiState.charges,
                dcChargeIds = uiState.dcChargeIds,
                acChargeIds = uiState.acChargeIds,
                processedChargeIds = uiState.processedChargeIds,
                chartData = uiState.chartData,
                chartGranularity = uiState.chartGranularity,
                summary = uiState.summary,
                currencySymbol = uiState.currencySymbol,
                selectedDateFilter = uiState.selectedFilter,
                selectedChargeTypeFilter = uiState.chargeTypeFilter,
                selectedCostFilter = uiState.costFilter,
                freeSupercharging = uiState.freeSupercharging,
                effectiveCosts = uiState.effectiveCosts,
                pendingLegacyAmounts = uiState.pendingLegacyAmounts,
                pendingBoundAmounts = uiState.pendingBoundAmounts,
                otherCurrencyAmounts = uiState.otherCurrencyAmounts,
                referenceEnergyKwh = uiState.referenceEnergyKwh,
                referenceEstimates = uiState.referenceEstimates,
                customStartDate = uiState.customStartDate,
                customEndDate = uiState.customEndDate,
                initialScrollPosition = uiState.scrollPosition,
                initialScrollOffset = uiState.scrollOffset,
                palette = palette,
                isFilterLoading = uiState.isFilterLoading,
                onDateFilterSelected = { viewModel.setDateFilter(it) },
                onCustomRangeSelected = { start, end -> viewModel.setCustomDateRange(start, end) },
                onClearFilters = { viewModel.clearFilters() },
                availableLocations = uiState.availableLocations,
                locationLabels = uiState.locationLabels,
                selectedLocations = uiState.selectedLocations,
                onLocationFilterToggled = { viewModel.setLocationFilter(it) },
                onLocationFilterCleared = { viewModel.clearLocationFilter() },
                onChargeTypeFilterSelected = { viewModel.setChargeTypeFilter(it) },
                onCostFilterSelected = { viewModel.setCostFilter(it) },
                onEditCost = { charge -> priceEditTarget = charge },
                onChargeClick = { chargeId, scrollIndex, scrollOffset ->
                    viewModel.saveScrollPosition(scrollIndex, scrollOffset)
                    onNavigateToChargeDetail(chargeId)
                }
            )
        }
    }

    priceEditTarget?.let { charge ->
        ChargePriceDialog(
            currentTotal = uiState.priceOverrides[charge.chargeId],
            pendingLegacyAmount = if (uiState.priceOverrides[charge.chargeId] == null)
                uiState.pendingLegacyAmounts[charge.chargeId] else null,
            otherCurrencyAmount = uiState.otherCurrencyAmounts[charge.chargeId],
            pendingBoundAmount = uiState.pendingBoundAmounts[charge.chargeId],
            currencySymbol = uiState.currencySymbol,
            onDismiss = { priceEditTarget = null },
            onSave = { total ->
                viewModel.saveManualTotalAmount(charge.chargeId, total)
                priceEditTarget = null
            },
            onClear = {
                viewModel.saveManualTotalAmount(charge.chargeId, null)
                priceEditTarget = null
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChargesContent(
    isLoading: Boolean = false,
    isRefreshing: Boolean = false,
    isLoadingMore: Boolean = false,
    canLoadMore: Boolean = false,
    onLoadMore: () -> Unit = {},
    historySyncWarning: String?,
    readError: String?,
    localArchiveLinkPending: Boolean,
    charges: List<ChargeData>,
    dcChargeIds: Set<Int>,
    acChargeIds: Set<Int> = emptySet(),
    processedChargeIds: Set<Int>,
    chartData: List<ChargeChartData>,
    chartGranularity: ChartGranularity,
    summary: ChargesSummary,
    currencySymbol: String,
    selectedDateFilter: DateFilter,
    selectedChargeTypeFilter: ChargeTypeFilter,
    selectedCostFilter: CostFilter,
    freeSupercharging: Boolean,
    effectiveCosts: Map<Int, EffectiveChargeCost>,
    pendingLegacyAmounts: Map<Int, Double>,
    pendingBoundAmounts: Map<Int, Pair<String?, Double>>,
    otherCurrencyAmounts: Map<Int, Pair<String, Double>>,
    referenceEnergyKwh: Map<Int, Double>,
    referenceEstimates: Map<Int, Double>,
    customStartDate: LocalDate?,
    customEndDate: LocalDate?,
    availableLocations: List<String>,
    locationLabels: Map<String, String>,
    selectedLocations: Set<String>,
    onLocationFilterToggled: (String) -> Unit,
    onLocationFilterCleared: () -> Unit,
    initialScrollPosition: Int,
    initialScrollOffset: Int,
    palette: CarColorPalette,
    isFilterLoading: Boolean,
    onDateFilterSelected: (DateFilter) -> Unit,
    onCustomRangeSelected: (LocalDate, LocalDate) -> Unit,
    onClearFilters: () -> Unit,
    onChargeTypeFilterSelected: (ChargeTypeFilter) -> Unit,
    onCostFilterSelected: (CostFilter) -> Unit,
    onEditCost: (ChargeData) -> Unit,
    onChargeClick: (chargeId: Int, scrollIndex: Int, scrollOffset: Int) -> Unit
) {
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = initialScrollPosition,
        initialFirstVisibleItemScrollOffset = initialScrollOffset
    )

    // Summary, secondary filters, optional notices/chart, then history heading.
    val showFreeHint = freeSupercharging && selectedCostFilter == CostFilter.NO_COST
    val headerCount = 3 +
        (if (historySyncWarning != null || localArchiveLinkPending) 1 else 0) +
        (if (showFreeHint) 1 else 0) +
        (if (chartData.isNotEmpty()) 1 else 0)

    val groupedCharges = remember(charges) {
        val groups = mutableListOf<HistoryDateGroup<ChargeData>>()
        var currentDate: LocalDate? = null
        var currentGroupItems = mutableListOf<ChargeData>()

        for (charge in charges) {
            val chargeDate = charge.startDate.parseListItemDate()
            if (currentGroupItems.isEmpty()) {
                currentDate = chargeDate
                currentGroupItems.add(charge)
            } else if (chargeDate == currentDate) {
                currentGroupItems.add(charge)
            } else {
                val key = currentDate?.toString() ?: "unknown_${groups.size}"
                groups.add(HistoryDateGroup(currentDate, key, currentGroupItems))
                currentDate = chargeDate
                currentGroupItems = mutableListOf(charge)
            }
        }
        if (currentGroupItems.isNotEmpty()) {
            val key = currentDate?.toString() ?: "unknown_${groups.size}"
            groups.add(HistoryDateGroup(currentDate, key, currentGroupItems))
        }
        groups
    }

    val indexToDate = remember(headerCount, groupedCharges) {
        buildList {
            repeat(headerCount) { add(null) }
            for (group in groupedCharges) {
                add(group.date) // header
                add(group.date) // group container
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (historySyncWarning != null || localArchiveLinkPending) {
            item(key = "history_sync_warning") {
                HistoryReadNotices(localArchiveLinkPending, historySyncWarning)
            }
        }

        item {
            SummaryCard(summary = summary, currencySymbol = currencySymbol, palette = palette,
                selectedDateFilter = selectedDateFilter, customStartDate = customStartDate,
                customEndDate = customEndDate, onDateFilterSelected = onDateFilterSelected,
                onCustomRangeSelected = onCustomRangeSelected)
        }

        item {
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                item { ChargeTypeFilterDropdown(
                    selectedFilter = selectedChargeTypeFilter,
                    palette = palette,
                    onFilterSelected = onChargeTypeFilterSelected
                ) }
                item { CostFilterDropdown(
                    selectedFilter = selectedCostFilter,
                    palette = palette,
                    onFilterSelected = onCostFilterSelected
                ) }
                item { LocationFilterDropdown(
                    availableLocations = availableLocations,
                    locationLabels = locationLabels,
                    selectedLocations = selectedLocations,
                    palette = palette,
                    onLocationToggled = onLocationFilterToggled,
                    onClearAll = onLocationFilterCleared
                ) }
            }
        }

        if (freeSupercharging && selectedCostFilter == CostFilter.NO_COST) {
            item {
                Text(
                    text = stringResource(R.string.charges_free_supercharging_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }
        }

        // Charges charts (daily/weekly/monthly based on date range) - swipeable
        if (chartData.isNotEmpty()) {
            item {
                ChargesChartsPager(
                    chartData = chartData,
                    granularity = chartGranularity,
                    currencySymbol = currencySymbol,
                    palette = palette
                )
            }
        }

        item {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.charge_history),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }

        if (charges.isEmpty()) {
            item {
                val hasFilters = selectedDateFilter != DateFilter.ALL_TIME ||
                    selectedChargeTypeFilter != ChargeTypeFilter.ALL ||
                    selectedCostFilter != CostFilter.ALL || selectedLocations.isNotEmpty()
                if (readError == null && hasFilters) {
                    MetricStatusPanel(
                        kind = MetricPanelKind.EMPTY,
                        title = stringResource(R.string.metric_state_filter_empty_title),
                        body = stringResource(R.string.metric_state_filter_empty_body),
                        actionLabel = stringResource(R.string.history_clear_filters),
                        onAction = onClearFilters
                    )
                } else {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            EmptyHistoryVisual(charging = true, failed = readError != null)
                            Text(
                                text = stringResource(readError?.let(::historyReadErrorMessageRes) ?: R.string.no_charges_found),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        } else {
            groupedCharges.forEach { group ->
                item(key = "header_${group.dateKey}") {
                    AppleDateGroupHeader(date = group.date)
                }
                item(key = "group_${group.dateKey}") {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(AppleTokens.groupCornerRadius),
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        tonalElevation = 0.dp
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            group.items.forEachIndexed { index, charge ->
                                if (index > 0) {
                                    HorizontalDivider(
                                        modifier = Modifier.padding(
                                            start = AppleTokens.dividerIndent,
                                            end = AppleTokens.rowHorizontal
                                        ),
                                        thickness = AppleTokens.hairline,
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                                    )
                                }
                                ChargeItem(
                                    charge = charge,
                                    isDcCharge = when (historyChargeType(charge, dcChargeIds, processedChargeIds, acChargeIds)) {
                                        ChargeType.DC -> true
                                        ChargeType.AC -> false
                                        ChargeType.UNKNOWN -> null
                                    },
                                    currencySymbol = currencySymbol,
                                    effectiveCost = effectiveCosts[charge.chargeId]
                                        ?: EffectiveChargeCost(null, ChargeCostSource.UNAVAILABLE),
                                    pendingLegacyAmount = pendingLegacyAmounts[charge.chargeId],
                                    pendingBoundAmount = pendingBoundAmounts[charge.chargeId],
                                    otherCurrencyAmount = otherCurrencyAmounts[charge.chargeId],
                                    referenceEnergyKwh = referenceEnergyKwh[charge.chargeId],
                                    referenceEstimate = referenceEstimates[charge.chargeId],
                                    palette = palette,
                                    onEditCost = { onEditCost(charge) },
                                    onClick = {
                                        onChargeClick(
                                            charge.chargeId,
                                            listState.firstVisibleItemIndex,
                                            listState.firstVisibleItemScrollOffset
                                        )
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }

        if (isLoadingMore) {
            item(key = "loading_more_indicator") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = palette.accent
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.loading_more_history),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        if (canLoadMore && !isLoadingMore) {
            item(key = "show_earlier_history") {
                TextButton(
                    onClick = onLoadMore,
                    enabled = !isLoading && !isRefreshing && !isFilterLoading,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.history_show_earlier))
                }
            }
        }
    }

    MonthScrollIndicator(
        state = listState,
        dateAt = { index -> indexToDate.getOrNull(index) },
        accent = palette.accent,
        modifier = Modifier.align(Alignment.CenterEnd),
    )

    // Sub-100ms loads never see a spinner — only sustained ones cross the
    // perceptual threshold worth giving feedback for.
    val showSpinner = rememberDebouncedLoading((isFilterLoading || (isLoading && !isRefreshing)) && charges.isEmpty())
    if (showSpinner) {
        // Overlay scrim lets the existing UI content show through clearly with a subtle dim.
        Box(
            modifier = Modifier
                .matchParentSize()
                .zIndex(10f)
                .background(Color.Black.copy(alpha = 0.20f)),
            contentAlignment = Alignment.Center,
        ) {
            MateLinkLoadingMark(size = 150.dp)
        }
    }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateFilterDropdown(
    selectedFilter: DateFilter,
    customStartDate: LocalDate?,
    customEndDate: LocalDate?,
    onFilterSelected: (DateFilter) -> Unit,
    onCustomRangeSelected: (LocalDate, LocalDate) -> Unit
) {
    var showDatePicker by remember { mutableStateOf(false) }
    val filters = listOf(DateFilter.LAST_7_DAYS, DateFilter.LAST_30_DAYS,
        DateFilter.LAST_90_DAYS, DateFilter.LAST_YEAR, DateFilter.ALL_TIME, DateFilter.TODAY)
    val labels = listOf(stringResource(R.string.history_last_week),
        stringResource(R.string.history_last_month), stringResource(R.string.history_last_three_months),
        stringResource(R.string.history_last_year), stringResource(R.string.filter_all_time),
        stringResource(R.string.filter_today))
    val index = filters.indexOf(selectedFilter)
    val label = if (selectedFilter == DateFilter.CUSTOM && customStartDate != null && customEndDate != null)
        "${formatShortDate(customStartDate)} – ${formatShortDate(customEndDate)}"
    else labels.getOrNull(index) ?: stringResource(R.string.history_choose_date)
    HistoryDateFilterMenu(label, labels, index, "charge_date_filters",
        onSelect = { onFilterSelected(filters[it]) }, onChooseDates = { showDatePicker = true })

    if (showDatePicker) {
        DateRangePickerDialog(
            onDismiss = { showDatePicker = false },
            onRangeSelected = { start, end ->
                showDatePicker = false
                onCustomRangeSelected(start, end)
            },
            initialStart = customStartDate,
            initialEnd = customEndDate
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChargeTypeFilterDropdown(
    selectedFilter: ChargeTypeFilter,
    palette: CarColorPalette,
    onFilterSelected: (ChargeTypeFilter) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val themeColor = when (selectedFilter) {
        ChargeTypeFilter.ALL -> palette.onSurfaceVariant
        ChargeTypeFilter.AC -> palette.acColor
        ChargeTypeFilter.DC -> palette.dcColor
    }
    Box {
        TextButton(onClick = { expanded = true }, contentPadding = PaddingValues(horizontal = 8.dp)) {
            Text(if (selectedFilter == ChargeTypeFilter.ALL) stringResource(R.string.history_charge_type)
                else getChargeTypeFilterLabel(selectedFilter), color = themeColor)
            Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = themeColor)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            ChargeTypeFilter.entries.forEach { entry ->
                DropdownMenuItem(
                    text = { Text(getChargeTypeFilterLabel(entry)) },
                    leadingIcon = {
                        if (entry == selectedFilter)
                            Icon(Icons.Default.Check, null, tint = palette.accent)
                        else
                            Spacer(Modifier.size(24.dp))
                    },
                    onClick = {
                        onFilterSelected(entry)
                        expanded = false
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CostFilterDropdown(
    selectedFilter: CostFilter,
    palette: CarColorPalette,
    onFilterSelected: (CostFilter) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val themeColor = palette.onSurfaceVariant
    Box {
        TextButton(onClick = { expanded = true }, contentPadding = PaddingValues(horizontal = 8.dp)) {
            Text(stringResource(if (selectedFilter == CostFilter.ALL) R.string.cost else selectedFilter.labelRes),
                color = themeColor)
            Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = themeColor)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            CostFilter.entries.forEach { entry ->
                DropdownMenuItem(
                    text = { Text(stringResource(entry.labelRes)) },
                    leadingIcon = {
                        if (entry == selectedFilter)
                            Icon(Icons.Default.Check, null, tint = palette.accent)
                        else
                            Spacer(Modifier.size(24.dp))
                    },
                    onClick = {
                        onFilterSelected(entry)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun getChargeTypeFilterLabel(filter: ChargeTypeFilter): String {
    return when (filter) {
        ChargeTypeFilter.ALL -> stringResource(R.string.filter_all)
        ChargeTypeFilter.AC -> stringResource(R.string.charging_ac)
        ChargeTypeFilter.DC -> stringResource(R.string.charging_dc)
    }
}

@Composable
internal fun SummaryCard(
    summary: ChargesSummary, currencySymbol: String, palette: CarColorPalette,
    selectedDateFilter: DateFilter, customStartDate: LocalDate?, customEndDate: LocalDate?,
    onDateFilterSelected: (DateFilter) -> Unit, onCustomRangeSelected: (LocalDate, LocalDate) -> Unit
) {
    val avgPricePerKwh = if (summary.totalCost != null && summary.totalEnergyAdded != null && summary.totalEnergyAdded > 0.05) {
        summary.totalCost / summary.totalEnergyAdded
    } else null
    val avgEnergyPerCharge = if (summary.totalEnergyAdded != null && summary.totalCharges > 0) {
        summary.totalEnergyAdded / summary.totalCharges
    } else null

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = palette.surface
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                Text(text = stringResource(R.string.summary), style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold, color = palette.onSurface)
                DateFilterDropdown(selectedDateFilter, customStartDate, customEndDate,
                    onDateFilterSelected, onCustomRangeSelected)
            }

            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = MaterialTheme.shapes.medium
            ) {
                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                    TelemetryMetricStrip(
                        metrics = listOf(
                            TelemetryMetricSpec(
                                Icons.Default.ElectricBolt,
                                stringResource(R.string.summary_sessions_compact),
                                summary.totalCharges.toString(),
                                palette.accent,
                                unit = stringResource(
                                    if (summary.totalCharges == 1) R.string.charge_session_unit
                                    else R.string.charge_sessions_unit
                                ),
                                valueFontSize = 18.sp
                            ),
                            TelemetryMetricSpec(
                                Icons.Default.BatteryChargingFull,
                                stringResource(R.string.energy),
                                summary.totalEnergyAdded?.let { energy ->
                                    val value = when {
                                        energy > 999 -> "%.2f MWh".format(energy / 1000)
                                        energy < 10 -> "%.1f kWh".format(energy)
                                        else -> "%.1f kWh".format(energy)
                                    }
                                    if (summary.partialEnergyCount > 0 || summary.energyCoverage < summary.totalCharges) "≈$value" else value
                                } ?: stringResource(R.string.not_available),
                                palette.acColor,
                                valueFontSize = 18.sp
                            ),
                            TelemetryMetricSpec(
                                Icons.Default.Paid,
                                stringResource(R.string.cost),
                                summary.totalCost?.let { cost ->
                                    val value = when {
                                        cost < 100 -> "$currencySymbol%.2f".format(cost)
                                        cost < 1000 -> "$currencySymbol%.1f".format(cost)
                                        else -> "$currencySymbol%.0f".format(cost)
                                    }
                                    if (summary.estimatedCostCount > 0 || summary.costCoverage < summary.totalCharges) "≈$value" else value
                                } ?: stringResource(R.string.not_available),
                                Color(0xFF22C55E),
                                valueFontSize = 18.sp
                            ),
                            TelemetryMetricSpec(
                                Icons.Default.Paid,
                                stringResource(R.string.summary_avg_charge_compact),
                                summary.avgCostPerCharge?.let { cost ->
                                    val value = "$currencySymbol%.2f".format(cost)
                                    if (summary.estimatedCostCount > 0 || summary.costCoverage < summary.totalCharges) "≈$value" else value
                                } ?: stringResource(R.string.not_available),
                                palette.dcColor,
                                valueFontSize = 18.sp
                            )
                        ),
                        adaptive = false,
                        maxColumns = 4
                    )

                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.PriceCheck,
                                contentDescription = null,
                                tint = Color(0xFF22C55E),
                                modifier = Modifier.size(15.dp)
                            )
                            Text(
                                text = "${stringResource(R.string.charge_unit_price)}: " +
                                    (avgPricePerKwh?.let { "$currencySymbol%.2f / kWh".format(it) } ?: stringResource(R.string.not_available)),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Speed,
                                contentDescription = null,
                                tint = palette.accent,
                                modifier = Modifier.size(15.dp)
                            )
                            Text(
                                text = "${stringResource(R.string.charge_avg_energy)}: " +
                                    (avgEnergyPerCharge?.let { "%.1f kWh / 次".format(it) } ?: stringResource(R.string.not_available)),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ChargeItem(
    charge: ChargeData,
    isDcCharge: Boolean?,
    currencySymbol: String,
    effectiveCost: EffectiveChargeCost,
    pendingLegacyAmount: Double?,
    pendingBoundAmount: Pair<String?, Double>?,
    otherCurrencyAmount: Pair<String, Double>?,
    palette: CarColorPalette,
    onEditCost: (() -> Unit)? = null,
    onClick: () -> Unit,
    referenceEnergyKwh: Double? = null,
    referenceEstimate: Double? = null
) {
    val unknownLocation = stringResource(R.string.unknown_location)
    val freeLabel = stringResource(R.string.charge_free)
    val notAvailableLabel = stringResource(R.string.not_available)
    val energy = presentChargeEnergy(charge.batteryInputKwh ?: charge.partialBatteryInputKwh)
    val referenceEnergy = presentChargeEnergy(referenceEnergyKwh).energyKwh
    val isReferenceEnergy = energy.energyKwh == null && referenceEnergy != null
    val displayedEnergy = energy.energyKwh ?: referenceEnergy
    val isPartialEnergy = charge.batteryInputKwh == null && charge.partialBatteryInputKwh != null
    val start = charge.startBatteryLevel
    val end = charge.endBatteryLevel

    val durationHours = charge.durationMin?.let { if (it > 0) it / 60.0 else null }
    val avgPowerKw = if (displayedEnergy != null && durationHours != null) displayedEnergy / durationHours else null
    val resolvedDc = isDcCharge ?: (charge.chargeType?.equals("dc", true) == true || (avgPowerKw != null && avgPowerKw > 22.0))

    val accent = if (resolvedDc) palette.dcColor else palette.acColor

    val pending = pendingChargeAmountForDisplay(
        effectiveCost.source == ChargeCostSource.MANUAL && effectiveCost.cost != null,
        pendingBoundAmount, pendingLegacyAmount)
    val isFree = pending == null && effectiveCost.source == ChargeCostSource.FREE
    val isReferenceCost = pending == null && effectiveCost.cost == null && referenceEstimate != null
    val displayCost = pending?.amount ?: effectiveCost.cost ?: referenceEstimate
    val costUnit = when {
        pending != null -> pending.currencySymbol ?: stringResource(R.string.charge_cost_currency_pending_short)
        displayCost != null && !isFree -> currencySymbol
        else -> null
    }
    val secondaryCostText = if (pending != null && effectiveCost.cost != null) {
        when (effectiveCost.source) {
            ChargeCostSource.ESTIMATE -> "${stringResource(R.string.charge_cost_estimate)} ≈$currencySymbol%.2f".format(effectiveCost.cost)
            ChargeCostSource.TESLAMATE -> "${stringResource(R.string.charge_cost_actual)} $currencySymbol%.2f".format(effectiveCost.cost)
            ChargeCostSource.FREE -> freeLabel
            else -> null
        }
    } else null
    val is24Hour = true

    val resolvedUnitPrice = if (displayCost != null && displayedEnergy != null && displayedEnergy > 0.05) {
        displayCost / displayedEnergy
    } else null
    val rangeAddedKm = if (charge.endRatedRangeKm != null && charge.startRatedRangeKm != null && charge.endRatedRangeKm!! > charge.startRatedRangeKm!!) {
        charge.endRatedRangeKm!! - charge.startRatedRangeKm!!
    } else null

    val addressText = charge.address?.toChineseDisplayAddress()?.takeIf { it.isNotBlank() }
        ?: charge.address?.takeIf { it.isNotBlank() }
        ?: if (charge.latitude != null && charge.longitude != null) "%.3f°N, %.3f°E".format(charge.latitude, charge.longitude)
        else unknownLocation

    val dateAndTempText = buildString {
        append(formatEditorialDate(charge.startDate, is24Hour))
        if (charge.outsideTempAvg != null) {
            append(" · ")
            append(stringResource(R.string.outside_temp))
            append(" ")
            append("%.1f°C".format(charge.outsideTempAvg))
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = AppleTokens.rowHorizontal, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            color = accent.copy(alpha = 0.14f),
            shape = CircleShape,
            modifier = Modifier.size(32.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = if (resolvedDc) Icons.Default.Bolt else Icons.Default.ElectricBolt,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = addressText,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = formatEditorialDate(charge.startDate, is24Hour),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                val durationLabel = charge.durationStr?.takeIf { it.isNotBlank() }
                    ?: charge.durationMin?.let { "${it}m" }
                if (durationLabel != null) {
                    Text(
                        text = "·",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = durationLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = if (resolvedDc) stringResource(R.string.charging_dc) else stringResource(R.string.charging_ac),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = accent
                )
                if (avgPowerKw != null && avgPowerKw > 0.1) {
                    Text(
                        text = "·",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "%.1f kW".format(avgPowerKw),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (resolvedUnitPrice != null) {
                    Text(
                        text = "·",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "$currencySymbol%.2f/kWh".format(resolvedUnitPrice),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(modifier = Modifier.width(10.dp))

        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            displayedEnergy?.let {
                val prefix = if (isPartialEnergy || isReferenceEnergy) "≈" else "+"
                Text(
                    text = "$prefix%.1f kWh".format(it),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.onSurface
                )
            }

            val costText = when {
                displayCost != null && !isFree ->
                    (if (isReferenceCost || (pending == null && effectiveCost.source == ChargeCostSource.ESTIMATE)) "≈" else "") + "$currencySymbol%.2f".format(displayCost)
                isFree -> freeLabel
                else -> null
            }
            if (costText != null) {
                Surface(
                    color = (if (isFree) palette.acColor else if (pending != null) Color(0xFFF59E0B) else Color(0xFF22C55E)).copy(alpha = 0.12f),
                    shape = RoundedCornerShape(4.dp),
                    modifier = Modifier.clickable(enabled = onEditCost != null) { onEditCost?.invoke() }
                ) {
                    Text(
                        text = costText,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isFree) palette.acColor else if (pending != null) Color(0xFFF59E0B) else Color(0xFF22C55E)
                    )
                }
            }

            val socRange = UnitFormatter.formatSocRange(start, end, "")
            if (socRange.isNotBlank()) {
                Text(
                    text = socRange,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.width(4.dp))

        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
            modifier = Modifier.size(16.dp)
        )
    }
}

@Composable
private fun ChargeTypeBadge(isDcCharge: Boolean?, palette: CarColorPalette) {
    val isDc = isDcCharge == true
    val isAc = isDcCharge == false
    val backgroundColor = when {
        isDc -> palette.dcColor
        isAc -> palette.acColor
        else -> palette.acColor
    }
    val text = when {
        isDc -> stringResource(R.string.charging_dc)
        isAc -> stringResource(R.string.charging_ac)
        else -> stringResource(R.string.charging_ac)
    }

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(backgroundColor)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
    }
}

/**
 * Chart type enum for the swipeable pager
 */
private enum class ChargesChartType {
    ENERGY, COST, COUNT
}

/**
 * Swipeable pager containing Energy, Cost, and Count charts with page indicator dots
 */
@Composable
private fun ChargesChartsPager(
    chartData: List<ChargeChartData>,
    granularity: ChartGranularity,
    currencySymbol: String,
    palette: CarColorPalette
) {
    val pagerState = rememberPagerState(pageCount = { ChargesChartType.entries.size })

    Column {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = palette.surface
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxWidth()
                ) { page ->
                    val chartType = ChargesChartType.entries[page]
                    ChargesChartPage(
                        chartData = chartData,
                        granularity = granularity,
                        chartType = chartType,
                        currencySymbol = currencySymbol,
                        palette = palette
                    )
                }
                //ChartLegend(palette = palette)
            }
        }

        // Page indicator dots
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center
        ) {
            repeat(ChargesChartType.entries.size) { index ->
                val isSelected = pagerState.currentPage == index
                Box(
                    modifier = Modifier
                        .padding(horizontal = 4.dp)
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(
                            if (isSelected) palette.accent
                            else palette.onSurfaceVariant.copy(alpha = 0.3f)
                        )
                )
            }
        }
    }
}

/**
 * Individual chart page showing Energy, Cost, or Count data
 */
@Composable
private fun ChargesChartPage(
    chartData: List<ChargeChartData>,
    granularity: ChartGranularity,
    chartType: ChargesChartType,
    currencySymbol: String,
    palette: CarColorPalette
) {
    val title = when (chartType) {
        ChargesChartType.ENERGY -> when (granularity) {
            ChartGranularity.DAILY -> stringResource(R.string.chart_energy_per_day)
            ChartGranularity.WEEKLY -> stringResource(R.string.chart_energy_per_week)
            ChartGranularity.MONTHLY -> stringResource(R.string.chart_energy_per_month)
        }
        ChargesChartType.COST -> when (granularity) {
            ChartGranularity.DAILY -> stringResource(R.string.chart_cost_per_day)
            ChartGranularity.WEEKLY -> stringResource(R.string.chart_cost_per_week)
            ChartGranularity.MONTHLY -> stringResource(R.string.chart_cost_per_month)
        }
        ChargesChartType.COUNT -> when (granularity) {
            ChartGranularity.DAILY -> stringResource(R.string.chart_charges_per_day)
            ChartGranularity.WEEKLY -> stringResource(R.string.chart_charges_per_week)
            ChartGranularity.MONTHLY -> stringResource(R.string.chart_charges_per_month)
        }
    }
    val icon = when (chartType) {
        ChargesChartType.ENERGY -> Icons.Default.BatteryChargingFull
        ChargesChartType.COST -> Icons.Default.Paid
        ChargesChartType.COUNT -> Icons.Default.ElectricBolt
    }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = palette.accent
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = palette.onSurface
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        val barData = when (chartType) {
            ChargesChartType.ENERGY -> chartData.map { data ->
                BarChartData(
                    label = data.label,
                    value = data.totalEnergy,
                    displayValue = if (data.energyCoverage > 0) {
                        (if (data.partialEnergyCount > 0) "≈" else "") + "%.1f kWh".format(data.totalEnergy)
                    } else stringResource(R.string.not_available),
                    segments = listOf(
                        BarSegment(data.energyAc, palette.acColor, "AC"),
                        BarSegment(data.energyDc, palette.dcColor, "DC"),
                        BarSegment(data.energyUnknown, palette.onSurfaceVariant, stringResource(R.string.not_available))
                    )
                )
            }
            ChargesChartType.COST -> chartData.map { data ->
                BarChartData(
                    label = data.label,
                    value = data.totalCost,
                    displayValue = if (data.costCoverage > 0) {
                        val value = "$currencySymbol%.2f".format(data.totalCost)
                        if (data.estimatedCostCount > 0) "≈$value" else value
                    } else stringResource(R.string.not_available),
                    segments = listOf(
                        BarSegment(data.costAc, palette.acColor, "AC"),
                        BarSegment(data.costDc, palette.dcColor, "DC"),
                        BarSegment(data.costUnknown, palette.onSurfaceVariant, stringResource(R.string.not_available))
                    )
                )
            }
            ChargesChartType.COUNT -> chartData.map { data ->
                BarChartData(
                    label = data.label,
                    value = data.count.toDouble(),
                    displayValue = data.count.toString(),
                    segments = listOf(
                        BarSegment(data.countAc.toDouble(), palette.acColor, "AC"),
                        BarSegment(data.countDc.toDouble(), palette.dcColor, "DC"),
                        BarSegment(data.countUnknown.toDouble(), palette.onSurfaceVariant, stringResource(R.string.not_available))
                    )
                )
            }
        }

        val valueFormatter: (Double) -> String = when (chartType) {
            ChargesChartType.ENERGY -> { v -> "%.1f kWh".format(v) }
            ChargesChartType.COST -> { v -> "$currencySymbol%.2f".format(v) }
            ChargesChartType.COUNT -> { v -> v.toInt().toString() }
        }

        // Set number of labels to display
        val labelInterval = when {
            barData.size <= 7 -> 1  // Show all for Today and last 7 days
            barData.size <= 30 -> 3 // Show 1 label every 3 bars for last 30 days
            else -> ((barData.size + 5) / 6).coerceAtLeast(1)
        }

        InteractiveBarChart(
            data = barData,
            modifier = Modifier.fillMaxWidth(),
            barColor = palette.accent,
            labelColor = palette.onSurfaceVariant,
            showEveryNthLabel = labelInterval,
            valueFormatter = valueFormatter
        )
    }
}

@Composable
private fun LocationFilterDropdown(
    availableLocations: List<String>,
    locationLabels: Map<String, String>,
    selectedLocations: Set<String>,
    palette: CarColorPalette,
    onLocationToggled: (String) -> Unit,
    onClearAll: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var searchText by remember { mutableStateOf("") }
    val hasSelection = selectedLocations.isNotEmpty()
    val unknownLocation = stringResource(R.string.unknown_location)
    fun label(key: String): String = (locationLabels[key] ?: locationKeyAddress(key))
        .toChineseDisplayAddress() ?: unknownLocation

    Box {
        TextButton(onClick = { expanded = true }, contentPadding = PaddingValues(horizontal = 8.dp)) {
            Text(
                if (hasSelection && selectedLocations.size == 1)
                    label(selectedLocations.first())
                else if (hasSelection) stringResource(R.string.selected_locations_count, selectedLocations.size)
                else stringResource(R.string.filter_location),
                modifier = Modifier.widthIn(max = 120.dp), maxLines = 1,
                overflow = TextOverflow.Ellipsis, color = palette.onSurfaceVariant
            )
            Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = palette.onSurfaceVariant)
        }
        DropdownMenu(
            expanded = expanded, modifier = Modifier.widthIn(min = 220.dp, max = 320.dp).heightIn(max = 360.dp),
            onDismissRequest = { expanded = false; searchText = "" }
        ) {
            OutlinedTextField(
                value = searchText,
                onValueChange = { searchText = it },
                placeholder = { Text(stringResource(R.string.search)) },
                modifier = Modifier.padding(8.dp).fillMaxWidth(),
                singleLine = true
            )
            val filtered = availableLocations.filter {
                locationKeyAddress(it)?.contains(searchText, ignoreCase = true) == true ||
                    label(it).contains(searchText, ignoreCase = true)
            }
            val allFilteredSelected = filtered.isNotEmpty() && filtered.all { it in selectedLocations }

            if (hasSelection) {
                DropdownMenuItem(text = { Text(stringResource(R.string.history_clear_location)) },
                    leadingIcon = { Icon(Icons.Default.Clear, contentDescription = null) },
                    onClick = { onClearAll(); expanded = false; searchText = "" })
            }
            DropdownMenuItem(
                text = {
                    Text(
                        if (allFilteredSelected)
                            stringResource(R.string.deselect_all)
                        else
                            stringResource(R.string.select_all),
                        fontWeight = FontWeight.Bold
                    )
                },
                leadingIcon = {
                    Icon(
                        if (allFilteredSelected) Icons.Default.Clear
                        else Icons.Default.Check,
                        contentDescription = null,
                        tint = palette.accent
                    )
                },
                onClick = {
                    if (allFilteredSelected) {
                        filtered.forEach { onLocationToggled(it) }
                    } else {
                        filtered.filter { it !in selectedLocations }.forEach { onLocationToggled(it) }
                    }
                }
            )
            HorizontalDivider()
            filtered.forEach { location ->
                val isSelected = location in selectedLocations
                DropdownMenuItem(
                    text = {
                        val displayed = label(location)
                        val original = locationKeyAddress(location)
                        val duplicate = availableLocations.count { label(it) == displayed } > 1
                        Column {
                            Text(displayed)
                            if (duplicate && original != null && original != displayed) {
                                Text(original, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    },
                    leadingIcon = {
                        if (isSelected)
                            Icon(Icons.Default.Check, null, tint = palette.accent)
                        else
                            Spacer(Modifier.size(24.dp))
                    },
                    onClick = { onLocationToggled(location) }
                )
            }
        }
    }
}
