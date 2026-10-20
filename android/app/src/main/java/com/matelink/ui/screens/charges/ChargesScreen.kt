package com.matelink.ui.screens.charges

import com.matelink.domain.model.UnitFormatter

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.BatteryStd
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ElectricBolt
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Paid
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import com.matelink.ui.components.HistorySourceNotice
import com.matelink.ui.components.HistoryForegroundRefreshEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import com.matelink.R
import com.matelink.data.api.models.ChargeData
import com.matelink.data.repository.HISTORY_IDENTITY_UNAVAILABLE
import com.matelink.ui.components.BarChartData
import com.matelink.ui.components.BarSegment
import com.matelink.ui.components.DateRangePickerDialog
import com.matelink.ui.components.InteractiveBarChart
import com.matelink.ui.components.MateLinkLoadingPlaceholder
import com.matelink.ui.components.MateLinkLoadingMark
import com.matelink.ui.components.MonthScrollIndicator
import com.matelink.ui.components.TelemetryMetricSpec
import com.matelink.ui.components.CompactHistoryMetricStrip
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
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val isDarkTheme = isSystemInDarkTheme()
    val palette = telemetryPalette()
    var priceEditTarget by remember { mutableStateOf<ChargeData?>(null) }

    HistoryForegroundRefreshEffect { viewModel.refresh() }
    LaunchedEffect(carId) {
        viewModel.setCarId(carId)
    }

    val serverNotConfiguredMessage = stringResource(R.string.server_not_configured_message)
    val historyIdentityUnavailableMessage = stringResource(R.string.history_identity_unavailable_message)
    LaunchedEffect(uiState.error) {
        uiState.error?.let { error ->
            snackbarHostState.showSnackbar(
                when (error) {
                    "Server not configured" -> serverNotConfiguredMessage
                    HISTORY_IDENTITY_UNAVAILABLE -> historyIdentityUnavailableMessage
                    else -> error
                }
            )
            viewModel.clearError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.charges_title),
                        fontWeight = FontWeight.SemiBold
                    )
                },
                actions = { if (uiState.localArchiveLinkPending) HistorySourceNotice() },
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
            if (uiState.isLoading && !uiState.isRefreshing) {
                MateLinkLoadingPlaceholder(color = palette.accent)
            } else {
                ChargesContent(
                    historySyncWarning = uiState.historySyncWarning,
                    charges = uiState.charges,
                    dcChargeIds = uiState.dcChargeIds,
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
                    customStartDate = uiState.customStartDate,
                    customEndDate = uiState.customEndDate,
                    initialScrollPosition = uiState.scrollPosition,
                    initialScrollOffset = uiState.scrollOffset,
                    palette = palette,
                    isFilterLoading = uiState.isFilterLoading,
                    onDateFilterSelected = { viewModel.setDateFilter(it) },
                    onCustomRangeSelected = { start, end -> viewModel.setCustomDateRange(start, end) },
                    availableLocations = uiState.availableLocations,
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
    historySyncWarning: String?,
    charges: List<ChargeData>,
    dcChargeIds: Set<Int>,
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
    customStartDate: LocalDate?,
    customEndDate: LocalDate?,
    availableLocations: List<String>,
    selectedLocations: Set<String>,
    onLocationFilterToggled: (String) -> Unit,
    onLocationFilterCleared: () -> Unit,
    initialScrollPosition: Int,
    initialScrollOffset: Int,
    palette: CarColorPalette,
    isFilterLoading: Boolean,
    onDateFilterSelected: (DateFilter) -> Unit,
    onCustomRangeSelected: (LocalDate, LocalDate) -> Unit,
    onChargeTypeFilterSelected: (ChargeTypeFilter) -> Unit,
    onCostFilterSelected: (CostFilter) -> Unit,
    onEditCost: (ChargeData) -> Unit,
    onChargeClick: (chargeId: Int, scrollIndex: Int, scrollOffset: Int) -> Unit
) {
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = initialScrollPosition,
        initialFirstVisibleItemScrollOffset = initialScrollOffset
    )

    // Date, categories, summary and chart precede the history list.
    val showFreeHint = freeSupercharging && selectedCostFilter == CostFilter.NO_COST
    val headerCount = 4 + (if (historySyncWarning != null) 1 else 0) +
        (if (showFreeHint) 1 else 0) + (if (chartData.isNotEmpty()) 1 else 0)
    val chargeRow: @Composable (ChargeData) -> Unit = { charge ->
        ChargeItem(
            charge = charge,
            isDcCharge = when (historyChargeType(charge, dcChargeIds, processedChargeIds)) {
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
    val chartRow: @Composable () -> Unit = {
        ChargesChartsPager(chartData, chartGranularity, currencySymbol, palette)
    }

    Box(modifier = Modifier.fillMaxSize()) {
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(8.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        if (historySyncWarning != null) {
            item(key = "history_sync_warning") {
                Text(
                    text = stringResource(when (historySyncWarning) {
                        "history_partial" -> R.string.history_sync_partial
                        else -> R.string.history_sync_cached
                    }),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.fillMaxWidth().padding(12.dp)
                )
            }
        }

        item {
            DateFilterChips(
                selectedFilter = selectedDateFilter,
                customStartDate = customStartDate,
                customEndDate = customEndDate,
                palette = palette,
                onFilterSelected = onDateFilterSelected,
                onCustomRangeSelected = onCustomRangeSelected
            )
        }

        item {
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                item {
                    ChargeTypeFilterDropdown(
                        selectedFilter = selectedChargeTypeFilter,
                        onFilterSelected = onChargeTypeFilterSelected,
                        palette = palette
                    )
                }
                item {
                    CostFilterDropdown(
                        selectedFilter = selectedCostFilter,
                        onFilterSelected = onCostFilterSelected,
                        palette = palette
                    )
                }
                item {
                    LocationFilterDropdown(
                        availableLocations = availableLocations,
                        selectedLocations = selectedLocations,
                        onLocationToggled = onLocationFilterToggled,
                        onClearAll = onLocationFilterCleared,
                        palette = palette
                    )
                }
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

        item {
            SummaryCard(summary = summary, currencySymbol = currencySymbol, palette = palette)
            Text(
                text = stringResource(R.string.default_charge_price_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }
        if (chartData.isNotEmpty()) item { chartRow() }

        item {
            Text(stringResource(R.string.charge_history),
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }

        if (charges.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.no_charges_found),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            items(charges, key = { it.chargeId }) { chargeRow(it) }
        }
    }

    MonthScrollIndicator(
        state = listState,
        dateAt = { index ->
            if (index < headerCount) null
            else charges.getOrNull(index - headerCount)?.startDate.parseListItemDate()
        },
        accent = palette.accent,
        modifier = Modifier.align(Alignment.CenterEnd),
    )

    // Sub-100ms loads never see a spinner — only sustained ones cross the
    // perceptual threshold worth giving feedback for.
    val showSpinner = rememberDebouncedLoading(isFilterLoading)
    if (showSpinner) {
        // Full-bleed dim scrim that sits above everything (chart cards inside
        // the LazyColumn have shadow elevation, which puts them in their own
        // graphics layer; without the explicit zIndex the spinner could end up
        // rendered behind them). The list shows through at ~55% alpha.
        Box(
            modifier = Modifier
                .matchParentSize()
                .zIndex(10f)
                .background(Color.Black.copy(alpha = 0.45f)),
            contentAlignment = Alignment.Center,
        ) {
            MateLinkLoadingMark()
        }
    }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateFilterChips(
    selectedFilter: DateFilter,
    customStartDate: LocalDate?,
    customEndDate: LocalDate?,
    palette: CarColorPalette,
    onFilterSelected: (DateFilter) -> Unit,
    onCustomRangeSelected: (LocalDate, LocalDate) -> Unit
) {
    var showDatePicker by remember { mutableStateOf(false) }
    var showMore by remember { mutableStateOf(false) }
    val commonFilters = listOf(DateFilter.TODAY, DateFilter.LAST_7_DAYS, DateFilter.LAST_30_DAYS)
    val moreFilters = DateFilter.entries.filter { it !in commonFilters }

    LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        items(commonFilters) { filter ->
            FilterChip(
                modifier = Modifier.height(28.dp),
                selected = filter == selectedFilter,
                onClick = { onFilterSelected(filter) },
                label = { Text(stringResource(filter.labelRes), style = MaterialTheme.typography.labelSmall) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = palette.surface,
                    selectedLabelColor = palette.onSurface
                )
            )
        }
        item {
            val moreSelected = selectedFilter in moreFilters
            val label = when {
                selectedFilter == DateFilter.CUSTOM && customStartDate != null && customEndDate != null ->
                    "${formatShortDate(customStartDate)} – ${formatShortDate(customEndDate)}"
                moreSelected -> stringResource(selectedFilter.labelRes)
                else -> stringResource(R.string.nav_more)
            }
            Box {
                FilterChip(
                    modifier = Modifier.height(28.dp),
                    selected = moreSelected,
                    onClick = { showMore = true },
                    label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                    trailingIcon = {
                        Icon(Icons.Default.ArrowDropDown, contentDescription = null, modifier = Modifier.size(12.dp))
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = palette.surface,
                        selectedLabelColor = palette.onSurface
                    )
                )
                DropdownMenu(expanded = showMore, onDismissRequest = { showMore = false }) {
                    moreFilters.forEach { filter ->
                        DropdownMenuItem(
                            text = { Text(stringResource(filter.labelRes)) },
                            onClick = {
                                showMore = false
                                if (filter == DateFilter.CUSTOM) showDatePicker = true
                                else onFilterSelected(filter)
                            }
                        )
                    }
                }
            }
        }
    }

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
    val isActive = selectedFilter != ChargeTypeFilter.ALL
    val themeColor = when (selectedFilter) {
        ChargeTypeFilter.ALL -> palette.onSurfaceVariant
        ChargeTypeFilter.AC -> palette.acColor
        ChargeTypeFilter.DC -> palette.dcColor
    }
    Box {
        FilterChip(
            modifier = Modifier.height(28.dp),
            selected = isActive,
            onClick = { expanded = true },
            label = { Text(getChargeTypeFilterLabel(selectedFilter), style = MaterialTheme.typography.labelSmall) },
            leadingIcon = { Icon(Icons.Default.ElectricBolt, null, Modifier.size(14.dp)) },
            colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = themeColor,
                selectedLabelColor = Color.White,
                selectedLeadingIconColor = Color.White,
                containerColor = Color.Transparent
            ),
            border = FilterChipDefaults.filterChipBorder(
                enabled = true,
                selected = isActive,
                borderColor = themeColor.copy(alpha = 0.6f),
                borderWidth = 1.dp,
                selectedBorderWidth = 0.dp
            )
        )
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
    val isActive = selectedFilter != CostFilter.ALL
    val themeColor = palette.onSurfaceVariant
    Box {
        FilterChip(
            modifier = Modifier.height(28.dp),
            selected = isActive,
            onClick = { expanded = true },
            label = { Text(stringResource(selectedFilter.labelRes), style = MaterialTheme.typography.labelSmall) },
            leadingIcon = { Icon(Icons.Default.Paid, null, Modifier.size(14.dp)) },
            colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = themeColor,
                selectedLabelColor = Color.White,
                selectedLeadingIconColor = Color.White,
                containerColor = Color.Transparent
            ),
            border = FilterChipDefaults.filterChipBorder(
                enabled = true,
                selected = isActive,
                borderColor = themeColor.copy(alpha = 0.6f),
                borderWidth = 1.dp,
                selectedBorderWidth = 0.dp
            )
        )
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
private fun SummaryCard(summary: ChargesSummary, currencySymbol: String, palette: CarColorPalette) {
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
            Text(
                text = stringResource(R.string.summary),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = palette.onSurface
            )

            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = MaterialTheme.shapes.medium
            ) {
                CompactHistoryMetricStrip(
                    metrics = listOf(
                        TelemetryMetricSpec(
                            Icons.Default.ElectricBolt,
                            stringResource(R.string.total_sessions),
                            summary.totalCharges.toString(),
                            palette.accent,
                            unit = stringResource(R.string.visual_trip_count_unit)
                        ),
                        TelemetryMetricSpec(
                            Icons.Default.BatteryChargingFull,
                            stringResource(R.string.total_energy),
                            summary.totalEnergyAdded?.let { energy ->
                                when {
                                    energy > 999 -> "%.2f".format(energy / 1000)
                                    energy < 10 -> "%.1f".format(energy)
                                    else -> "%.0f".format(energy)
                                }
                            } ?: stringResource(R.string.not_available),
                            palette.acColor,
                            unit = summary.totalEnergyAdded?.let { if (it > 999) "MWh" else "kWh" }
                        ),
                        TelemetryMetricSpec(
                            Icons.Default.Paid,
                            stringResource(R.string.total_cost),
                            summary.totalCost?.let { cost ->
                                when {
                                    cost < 100 -> "%.2f".format(cost)
                                    cost < 1000 -> "%.1f".format(cost)
                                    else -> "%.0f".format(cost)
                                }
                            } ?: stringResource(R.string.not_available),
                            Color(0xFF22C55E),
                            unit = if (summary.totalCost != null) currencySymbol else null
                        ),
                        TelemetryMetricSpec(
                            Icons.Default.Paid,
                            stringResource(R.string.avg_cost_per_session),
                            summary.avgCostPerCharge?.let { cost ->
                                "%.2f".format(cost)
                            } ?: stringResource(R.string.not_available),
                            palette.dcColor,
                            unit = if (summary.avgCostPerCharge != null) currencySymbol else null
                        )
                    ),
                    modifier = Modifier.padding(vertical = 8.dp),
                    numberFontSize = 20.sp
                )
            }
        }
    }
}

@Composable
private fun ChargeItem(
    charge: ChargeData,
    isDcCharge: Boolean?,
    currencySymbol: String,
    effectiveCost: EffectiveChargeCost,
    pendingLegacyAmount: Double?,
    pendingBoundAmount: Pair<String?, Double>?,
    otherCurrencyAmount: Pair<String, Double>?,
    palette: CarColorPalette,
    onEditCost: (() -> Unit)? = null,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val unknownLocation = stringResource(R.string.unknown_location)
    val freeLabel = stringResource(R.string.charge_free)
    val notAvailableLabel = stringResource(R.string.not_available)
    val accent = when (isDcCharge) {
        true -> palette.dcColor
        false -> palette.acColor
        null -> MaterialTheme.colorScheme.primary
    }
    val energy = presentChargeEnergy(charge.batteryInputKwh)

    val isFree = effectiveCost.source == ChargeCostSource.FREE
    val costText = when {
        isFree -> freeLabel
        effectiveCost.cost != null -> (if (effectiveCost.source == ChargeCostSource.ESTIMATE) "≈" else "") + "$currencySymbol%.2f".format(effectiveCost.cost)
        else -> notAvailableLabel
    }
    val is24Hour = android.text.format.DateFormat.is24HourFormat(context)
    val start = charge.startBatteryLevel
    val end = charge.endBatteryLevel

    TelemetryPanel(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        accent = accent
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Surface(
                        color = accent.copy(alpha = 0.14f),
                        shape = CircleShape
                    ) {
                        Icon(
                            imageVector = Icons.Default.LocationOn,
                            contentDescription = null,
                            tint = accent,
                            modifier = Modifier.padding(9.dp).size(20.dp)
                        )
                    }
                    Column {
                        Text(
                            text = charge.address.toChineseDisplayAddress() ?: unknownLocation,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1
                        )
                        Text(
                            text = formatEditorialDate(charge.startDate, is24Hour),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                ChargeTypeBadge(isDcCharge = isDcCharge, palette = palette)
            }

            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = MaterialTheme.shapes.medium
            ) {
                CompactHistoryMetricStrip(
                    metrics = listOf(
                        TelemetryMetricSpec(
                            icon = Icons.Default.Timer,
                            label = stringResource(R.string.duration),
                            value = charge.durationStr?.takeIf { it.isNotBlank() }
                                ?: charge.durationMin?.let { "${it}m" }
                                ?: notAvailableLabel,
                            tint = Color(0xFFF59E0B)
                        ),
                        TelemetryMetricSpec(
                            icon = Icons.Default.ElectricBolt,
                            label = stringResource(R.string.energy),
                            value = energy.energyKwh?.let { "%.1f kWh".format(it) } ?: notAvailableLabel,
                            tint = accent
                        ),
                        TelemetryMetricSpec(
                            icon = Icons.Default.Paid,
                            label = stringResource(R.string.trip_charge_cost),
                            value = if (effectiveCost.cost != null && !isFree) costText.replace(currencySymbol, "") else costText,
                            unit = if (effectiveCost.cost != null && !isFree) currencySymbol else null,
                            tint = if (isFree) palette.acColor else Color(0xFF22C55E)
                        ),
                        TelemetryMetricSpec(
                            icon = Icons.Default.BatteryStd,
                            label = stringResource(R.string.battery),
                            value = UnitFormatter.formatSocRange(start, end, notAvailableLabel).replace("%", "").replace(" ", ""),
                            unit = if (start != null || end != null) "%" else null,
                            tint = Color(0xFFF97316)
                        )
                    ),
                    modifier = Modifier.padding(vertical = 8.dp),
                    numberFontSize = 18.sp,
                    clickableMetricIndex = if (onEditCost != null) 2 else null,
                    onMetricClick = onEditCost,
                    metricClickLabel = stringResource(R.string.edit_charge_cost)
                )
                if (effectiveCost.source == ChargeCostSource.MANUAL &&
                    effectiveCost.qualificationReason == "instance_unverified") {
                    Text(stringResource(R.string.charge_cost_instance_unverified),
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.onSurfaceVariant)
                }
                pendingLegacyAmount?.let { amount ->
                    Text(stringResource(R.string.charge_cost_saved_unconfirmed, amount),
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.onSurfaceVariant)
                }
                pendingBoundAmount?.let { (code, amount) ->
                    Text(stringResource(R.string.charge_cost_saved_unbound, code ?: "?", amount),
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.onSurfaceVariant)
                }
                otherCurrencyAmount?.let { (code, amount) ->
                    Text(stringResource(R.string.charge_cost_other_currency, code, amount),
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.onSurfaceVariant)
                }
                charge.cost?.takeIf { it.isFinite() && it >= 0.0 &&
                    charge.currencyCode.isNullOrBlank() && !charge.source.isNullOrBlank() }
                    ?.let { amount ->
                        Text(stringResource(R.string.charge_cost_source_unconfirmed, amount),
                            style = MaterialTheme.typography.labelSmall,
                            color = palette.onSurfaceVariant)
                    }
                if (effectiveCost.qualificationReason == "source_unverified") {
                    charge.cost?.takeIf { it.isFinite() && it >= 0.0 }?.let { amount ->
                        Text(stringResource(R.string.charge_cost_source_identity_unverified, amount),
                            style = MaterialTheme.typography.labelSmall,
                            color = palette.onSurfaceVariant)
                    }
                }
                if (effectiveCost.qualificationReason == "currency_mismatch") {
                    charge.cost?.takeIf { it.isFinite() && it > 0.0 }?.let { amount ->
                        Text(stringResource(R.string.charge_cost_source_other_currency,
                            charge.currencyCode.orEmpty(), amount),
                            style = MaterialTheme.typography.labelSmall,
                            color = palette.onSurfaceVariant)
                    }
                }
                if (effectiveCost.qualificationReason == "provider_zero_unverified" &&
                    !charge.currencyCode.isNullOrBlank()) {
                    Text(stringResource(R.string.charge_cost_source_zero_unverified),
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun ChargeTypeBadge(isDcCharge: Boolean?, palette: CarColorPalette) {
    val backgroundColor = when (isDcCharge) {
        true -> palette.dcColor
        false -> palette.acColor
        null -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val text = when (isDcCharge) {
        true -> stringResource(R.string.charging_dc)
        false -> stringResource(R.string.charging_ac)
        null -> stringResource(R.string.status_unknown)
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
                        "%.1f kWh".format(data.totalEnergy)
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
                        "$currencySymbol%.2f".format(data.totalCost)
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
    selectedLocations: Set<String>,
    palette: CarColorPalette,
    onLocationToggled: (String) -> Unit,
    onClearAll: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var searchText by remember { mutableStateOf("") }
    val hasSelection = selectedLocations.isNotEmpty()

    Box {
        FilterChip(
            modifier = Modifier.height(28.dp),
            selected = hasSelection,
            onClick = { expanded = true },
            label = {
                Text(
                    if (hasSelection && selectedLocations.size == 1)
                        selectedLocations.first().toChineseDisplayAddress()
                            ?: stringResource(R.string.unknown_location)
                    else if (hasSelection)
                        stringResource(R.string.selected_locations_count, selectedLocations.size)
                    else
                        stringResource(R.string.filter_location),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 112.dp)
                )
            },
            leadingIcon = { Icon(Icons.Default.LocationOn, null, Modifier.size(14.dp)) },
            trailingIcon = if (hasSelection) {
                {
                    Icon(
                        Icons.Default.Clear,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp).clickable { onClearAll() }
                    )
                }
            } else null
        )
        DropdownMenu(
            expanded = expanded,
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
                it.contains(searchText, ignoreCase = true)
            }
            val allFilteredSelected = filtered.isNotEmpty() && filtered.all { it in selectedLocations }

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
                    text = { Text(location) },
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
