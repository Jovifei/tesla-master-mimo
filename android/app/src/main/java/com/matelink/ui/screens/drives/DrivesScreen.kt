package com.matelink.ui.screens.drives

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Eco
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.LocalParking
import com.matelink.ui.icons.CustomIcons
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import com.matelink.ui.components.HistoryForegroundRefreshEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import com.matelink.R
import com.matelink.data.api.models.DriveData
import com.matelink.data.api.models.Units
import com.matelink.data.repository.HISTORY_IDENTITY_UNAVAILABLE
import com.matelink.domain.model.UnitFormatter
import com.matelink.data.api.models.drivePresentationUnits
import com.matelink.ui.components.BarChartData
import com.matelink.ui.components.DateRangePickerDialog
import com.matelink.ui.components.InteractiveBarChart
import com.matelink.ui.components.MateLinkLoadingPlaceholder
import com.matelink.ui.components.MateLinkLoadingMark
import com.matelink.ui.components.MonthScrollIndicator
import com.matelink.ui.components.RouteIndicator
import com.matelink.ui.components.TelemetryMetricSpec
import com.matelink.ui.components.TelemetryMetricStrip
import com.matelink.ui.components.TelemetryPanel
import com.matelink.ui.components.rememberDebouncedLoading
import com.matelink.ui.components.formatEditorialDate
import com.matelink.ui.components.formatShortDate
import com.matelink.ui.components.parseListItemDate
import com.matelink.util.formatDuration
import com.matelink.util.toChineseDisplayAddress
import com.matelink.ui.theme.CarColorPalette
import com.matelink.ui.theme.telemetryPalette
import java.time.Duration
import java.time.LocalDate
import java.time.OffsetDateTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DrivesScreen(
    carId: Int,
    exteriorColor: String? = null,
    onNavigateToDriveDetail: (driveId: Int) -> Unit,
    onNavigateToParkedDetail: (olderDriveId: Int, newerDriveId: Int) -> Unit = { _, _ -> },
    viewModel: DrivesViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val palette = telemetryPalette()

    // Remember scroll state and restore from ViewModel
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = uiState.scrollPosition,
        initialFirstVisibleItemScrollOffset = uiState.scrollOffset
    )

    // Initialize ViewModel with carId (only loads data on first call)
    HistoryForegroundRefreshEffect { viewModel.refresh() }
    LaunchedEffect(carId) {
        viewModel.setCarId(carId)
    }

    // Save scroll position when it changes
    LaunchedEffect(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset) {
        viewModel.saveScrollPosition(
            listState.firstVisibleItemIndex,
            listState.firstVisibleItemScrollOffset
        )
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
                        text = stringResource(R.string.drives_title),
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
            DrivesContent(
                isLoading = uiState.isLoading,
                isRefreshing = uiState.isRefreshing,
                isLoadingMore = uiState.isLoadingMore,
                canLoadMore = uiState.canLoadMore,
                onLoadMore = { viewModel.loadMore() },
                historySyncWarning = uiState.historySyncWarning,
                localArchiveLinkPending = uiState.localArchiveLinkPending,
                drives = uiState.drives,
                chartData = uiState.chartData,
                chartGranularity = uiState.chartGranularity,
                summary = uiState.summary,
                selectedDateFilter = uiState.dateFilter,
                selectedDistanceFilter = uiState.distanceFilter,
                customStartDate = uiState.customStartDate,
                customEndDate = uiState.customEndDate,
                units = uiState.units,
                palette = palette,
                listState = listState,
                isFilterLoading = uiState.isFilterLoading,
                driveMetrics = uiState.driveMetrics,
                onDateFilterSelected = { viewModel.setDateFilter(it) },
                onCustomRangeSelected = { start, end -> viewModel.setCustomDateRange(start, end) },
                onDistanceFilterSelected = { viewModel.setDistanceFilter(it) },
                onDriveClick = onNavigateToDriveDetail,
                onParkedClick = onNavigateToParkedDetail
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DrivesContent(
    isLoading: Boolean = false,
    isRefreshing: Boolean = false,
    isLoadingMore: Boolean = false,
    canLoadMore: Boolean = false,
    onLoadMore: () -> Unit = {},
    historySyncWarning: String?,
    localArchiveLinkPending: Boolean,
    drives: List<DriveData>,
    chartData: List<DriveChartData>,
    chartGranularity: DriveChartGranularity,
    summary: DrivesSummary,
    selectedDateFilter: DriveDateFilter,
    selectedDistanceFilter: DriveDistanceFilter,
    customStartDate: LocalDate?,
    customEndDate: LocalDate?,
    units: Units?,
    palette: CarColorPalette,
    listState: androidx.compose.foundation.lazy.LazyListState,
    isFilterLoading: Boolean,
    driveMetrics: Map<Int, DriveHistoryMetrics>,
    onDateFilterSelected: (DriveDateFilter) -> Unit,
    onCustomRangeSelected: (LocalDate, LocalDate) -> Unit,
    onDistanceFilterSelected: (DriveDistanceFilter) -> Unit,
    onDriveClick: (driveId: Int) -> Unit,
    onParkedClick: (olderDriveId: Int, newerDriveId: Int) -> Unit
) {
    val historyItems = remember(drives) { buildDriveHistoryItems(drives) }
    // Header items in this LazyColumn, in render order: date chips, distance chips,
    // summary, charts (conditional), history header. Adjust if items are added.
    val headerCount = 3 + (if (chartData.isNotEmpty()) 1 else 0) + (if (historySyncWarning != null) 1 else 0)

    Box(modifier = Modifier.fillMaxSize()) {
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
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
            SummaryCard(summary = summary, units = units, palette = palette)
        }

        // Drives charts (daily/weekly/monthly based on date range) - swipeable
        if (chartData.isNotEmpty()) {
            item {
                DrivesChartsPager(
                    chartData = chartData,
                    granularity = chartGranularity,
                    units = units,
                    palette = palette
                )
            }
        }

        item {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.drive_history),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }

        if (drives.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.DirectionsCar,
                            contentDescription = null,
                            tint = palette.accent,
                            modifier = Modifier.size(36.dp)
                        )
                        Text(
                            text = stringResource(R.string.no_drives_found),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            items(historyItems, key = { it.key }) { item ->
                when (item) {
                    is DriveHistoryItem.Drive -> DriveItem(
                        drive = item.drive,
                        metrics = driveMetrics[item.drive.id],
                        units = units,
                        palette = palette,
                        onClick = { onDriveClick(item.drive.id) }
                    )
                    is DriveHistoryItem.Parked -> ParkedItem(
                        item = item,
                        palette = palette,
                        onClick = { onParkedClick(item.olderDrive.id, item.newerDrive.id) }
                    )
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
    }

    val shouldLoadMore by remember {
        derivedStateOf {
            val totalItems = listState.layoutInfo.totalItemsCount
            val lastVisibleIndex = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            totalItems > headerCount + 1 && lastVisibleIndex >= totalItems - 2
        }
    }
    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore && canLoadMore && !isLoadingMore && !isFilterLoading && !isLoading) {
            onLoadMore()
        }
    }

    MonthScrollIndicator(
        state = listState,
        dateAt = { index ->
            if (index < headerCount) null
            else historyItems.getOrNull(index - headerCount)?.dateForIndicator.parseListItemDate()
        },
        accent = palette.accent,
        modifier = Modifier.align(Alignment.CenterEnd),
    )

    // Sub-100ms loads never see a spinner — only sustained ones cross the
    // perceptual threshold worth giving feedback for.
    val showSpinner = rememberDebouncedLoading(isFilterLoading || (isLoading && !isRefreshing))
    if (showSpinner) {
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

internal sealed interface DriveHistoryItem {
    val key: String
    val dateForIndicator: String?

    data class Drive(val drive: DriveData) : DriveHistoryItem {
        override val key: String = "drive-${drive.id}"
        override val dateForIndicator: String? = drive.startDate
    }

    data class Parked(
        val olderDrive: DriveData,
        val newerDrive: DriveData,
        val startDate: String,
        val endDate: String,
        val durationMin: Long,
        val location: String?
    ) : DriveHistoryItem {
        override val key: String = "parked-${olderDrive.id}-${newerDrive.id}"
        override val dateForIndicator: String = startDate
    }
}

internal fun buildDriveHistoryItems(drives: List<DriveData>): List<DriveHistoryItem> {
    // DrivesViewModel owns card visibility. Parking keeps its older >=0.5 km
    // route boundary so newly visible unknown/short drives do not change parked inference.
    if (drives.isEmpty()) return emptyList()
    val parkingEligibleIndices = drives.indices.filter { index ->
        drives[index].distance?.let { distance -> distance >= 0.5 } == true
    }
    val parkedAfterIndex = parkingEligibleIndices.zipWithNext().mapNotNull { (newerIndex, olderIndex) ->
        createParkedSegment(drives[olderIndex], drives[newerIndex])?.let { newerIndex to it }
    }.toMap()

    val items = mutableListOf<DriveHistoryItem>()
    drives.forEachIndexed { index, drive ->
        items += DriveHistoryItem.Drive(drive)
        parkedAfterIndex[index]?.let { items += it }
    }
    return items
}

private fun createParkedSegment(
    olderDrive: DriveData,
    newerDrive: DriveData
): DriveHistoryItem.Parked? {
    val startDate = olderDrive.endDate ?: return null
    val endDate = newerDrive.startDate ?: return null
    val start = parseOffsetDateTime(startDate) ?: return null
    val end = parseOffsetDateTime(endDate) ?: return null
    val durationMin = Duration.between(start, end).toMinutes()
    if (durationMin <= 0) return null
    return DriveHistoryItem.Parked(
        olderDrive = olderDrive,
        newerDrive = newerDrive,
        startDate = startDate,
        endDate = endDate,
        durationMin = durationMin,
        location = parkedAddressLabel(olderDrive.endAddress, newerDrive.startAddress)
    )
}

private fun parseOffsetDateTime(value: String): OffsetDateTime? =
    runCatching { OffsetDateTime.parse(value) }.getOrNull()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateFilterChips(
    selectedFilter: DriveDateFilter,
    customStartDate: LocalDate?,
    customEndDate: LocalDate?,
    palette: CarColorPalette,
    onFilterSelected: (DriveDateFilter) -> Unit,
    onCustomRangeSelected: (LocalDate, LocalDate) -> Unit
) {
    var showDatePicker by remember { mutableStateOf(false) }

    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        val orderedFilters = listOf(
            DriveDateFilter.LAST_7_DAYS,
            DriveDateFilter.TODAY,
            DriveDateFilter.LAST_30_DAYS,
            DriveDateFilter.ALL_TIME
        )
        items(orderedFilters) { filter ->
            FilterChip(
                selected = filter == selectedFilter,
                onClick = { onFilterSelected(filter) },
                label = { Text(stringResource(filter.labelRes)) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = palette.surface,
                    selectedLabelColor = palette.onSurface
                )
            )
        }
        item {
            val label = if (selectedFilter == DriveDateFilter.CUSTOM && customStartDate != null && customEndDate != null) {
                "${formatShortDate(customStartDate)} – ${formatShortDate(customEndDate)}"
            } else {
                stringResource(R.string.filter_custom)
            }
            FilterChip(
                selected = selectedFilter == DriveDateFilter.CUSTOM,
                onClick = { showDatePicker = true },
                label = { Text(label) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = palette.surface,
                    selectedLabelColor = palette.onSurface
                )
            )
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

@Composable
private fun getDistanceFilterLabel(filter: DriveDistanceFilter, units: Units?): String {
    val isImperial = units?.isImperial == true
    return when (filter) {
        DriveDistanceFilter.ALL -> stringResource(R.string.filter_all)
        DriveDistanceFilter.COMMUTE -> stringResource(if (isImperial) R.string.filter_commute_mi else R.string.filter_commute_km)
        DriveDistanceFilter.DAY_TRIP -> stringResource(if (isImperial) R.string.filter_day_trip_mi else R.string.filter_day_trip_km)
        DriveDistanceFilter.ROAD_TRIP -> stringResource(if (isImperial) R.string.filter_road_trip_mi else R.string.filter_road_trip_km)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DistanceFilterChips(
    selectedFilter: DriveDistanceFilter,
    units: Units?,
    palette: CarColorPalette,
    onFilterSelected: (DriveDistanceFilter) -> Unit
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(DriveDistanceFilter.entries.toList()) { filter ->
            FilterChip(
                selected = filter == selectedFilter,
                onClick = { onFilterSelected(filter) },
                label = { Text(getDistanceFilterLabel(filter, units)) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = palette.surface,
                    selectedLabelColor = palette.onSurface
                )
            )
        }
    }
}

@Composable
private fun SummaryCard(summary: DrivesSummary, units: Units?, palette: CarColorPalette) {
    val hasSummary = summary.totalDrives > 0
    val unavailable = stringResource(R.string.not_available)
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
                TelemetryMetricStrip(
                    metrics = listOf(
                        TelemetryMetricSpec(
                            Icons.Default.DirectionsCar,
                            stringResource(R.string.total_trips),
                            if (hasSummary) "%,d".format(summary.totalDrives) else unavailable,
                            Color(0xFF0284C7),
                            unit = stringResource(R.string.visual_trip_count_unit),
                            valueFontSize = 16.sp
                        ),
                        TelemetryMetricSpec(
                            CustomIcons.SteeringWheel,
                            stringResource(R.string.total_distance),
                            summary.totalDistanceKm?.let { visualTripNumber(UnitFormatter.distanceKmDisplayValue(it, units)) } ?: unavailable,
                            Color(0xFF059669),
                            unit = UnitFormatter.getDistanceUnit(units),
                            valueFontSize = 16.sp
                        ),
                        TelemetryMetricSpec(
                            Icons.Default.Timer,
                            stringResource(R.string.total_time),
                            summary.totalDurationMin?.let { "%,d".format(it) } ?: unavailable,
                            Color(0xFFD97706),
                            unit = stringResource(R.string.visual_trip_duration_unit),
                            valueFontSize = 16.sp
                        ),
                        TelemetryMetricSpec(
                            Icons.Default.Speed,
                            stringResource(R.string.max_speed),
                            summary.maxSpeedKmh?.takeIf { it >= 0 }?.let { visualTripNumber(UnitFormatter.speedKmhDisplayValue(it.toDouble(), units), 0) } ?: unavailable,
                            Color(0xFF6366F1),
                            unit = UnitFormatter.getSpeedUnit(units),
                            valueFontSize = 16.sp
                        )
                    ),
                    modifier = Modifier.padding(vertical = 4.dp),
                    adaptive = false,
                    maxColumns = 4
                )
            }
            if (hasSummary && (summary.distanceSampleCount < summary.totalDrives ||
                    summary.durationSampleCount < summary.totalDrives)) {
                Text(
                    text = "${stringResource(R.string.stats_analysis_data_coverage)} · " +
                        "${stringResource(R.string.total_distance)}: " +
                        stringResource(R.string.stats_analysis_coverage_records, summary.distanceSampleCount, summary.totalDrives) +
                        " · ${stringResource(R.string.total_time)}: " +
                        stringResource(R.string.stats_analysis_coverage_records, summary.durationSampleCount, summary.totalDrives),
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.onSurfaceVariant
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DriveItem(
    drive: DriveData,
    metrics: DriveHistoryMetrics?,
    units: Units?,
    palette: CarColorPalette,
    onClick: () -> Unit
) {
    val receiptUnits = drivePresentationUnits(drive.lengthUnit, units)
    val unknown = stringResource(R.string.unknown)
    val formattedStart = drive.startAddress?.takeIf { it.isNotBlank() }?.toChineseDisplayAddress()
    val formattedEnd = drive.endAddress?.takeIf { it.isNotBlank() }?.toChineseDisplayAddress()
    val startCity = formattedStart ?: unknown
    val endCity = formattedEnd ?: unknown

    val energyPres = drive.historyEnergy(metrics)
    val efficiency = energyPres.efficiencyWhKm
    val energyKwh = energyPres.energyKwh
    val start = drive.startBatteryLevel
    val end = drive.endBatteryLevel

    TelemetryPanel(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        accent = palette.accent
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = formatEditorialDate(drive.startDate, true),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    val socRange = UnitFormatter.formatSocRange(start, end, "")
                    if (socRange.isNotBlank()) {
                        Text(
                            text = socRange,
                            style = MaterialTheme.typography.titleMedium,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = palette.onSurface
                        )
                    }
                    energyPres.source?.takeIf { efficiency != null || energyKwh != null }?.let { source ->
                        val (sourceLabel, badgeBg, badgeTextColor) = when (source) {
                            DriveDetailEnergySource.POWER_SAMPLES -> {
                                val coverage = if ((energyPres.coverageRatio ?: 0.0) > 0.0) {
                                    " ${"%.0f".format(energyPres.coverageRatio!! * 100)}%"
                                } else ""
                                Triple(
                                    "${stringResource(R.string.energy_source_power_samples)}$coverage",
                                    Color(0xFFE0F2FE),
                                    Color(0xFF0369A1)
                                )
                            }
                            DriveDetailEnergySource.SOC_CAPACITY_ESTIMATE -> Triple(
                                stringResource(R.string.energy_source_soc_estimate),
                                Color(0xFFFEF3C7),
                                Color(0xFFB45309)
                            )
                            DriveDetailEnergySource.ENERGY_REMAINING_DELTA -> Triple(
                                stringResource(R.string.energy_nominal_estimate),
                                Color(0xFFF1F5F9),
                                Color(0xFF475569)
                            )
                            DriveDetailEnergySource.API -> Triple(
                                stringResource(R.string.energy_source_api_reported),
                                Color(0xFFECFDF5),
                                Color(0xFF047857)
                            )
                        }
                        Surface(
                            color = badgeBg,
                            shape = MaterialTheme.shapes.extraLarge
                        ) {
                            Text(
                                text = sourceLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = badgeTextColor,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            }

            RouteIndicator(
                start = startCity,
                end = endCity,
                accent = palette.accent
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            ) {
                TelemetryMetricStrip(
                    metrics = listOf(
                        TelemetryMetricSpec(
                            icon = Icons.Default.Bolt,
                            label = stringResource(R.string.energy),
                            value = efficiency?.let { visualTripNumber(it / 10.0) }
                                ?: energyKwh?.let { visualTripNumber(it) }
                                ?: stringResource(R.string.not_available),
                            tint = Color(0xFF10B981),
                            unit = if (efficiency != null) "kWh/100km" else if (energyKwh != null) "kWh" else "",
                            valueFontSize = 17.5.sp
                        ),
                        TelemetryMetricSpec(
                            icon = CustomIcons.SteeringWheel,
                            label = stringResource(R.string.distance),
                            value = drive.distance
                                ?.takeIf { it.isFinite() && it >= 0.0 }
                                ?.let { visualTripNumber(it) }
                                ?: stringResource(R.string.not_available),
                            tint = Color(0xFF0284C7),
                            unit = UnitFormatter.getDistanceUnit(receiptUnits),
                            valueFontSize = 17.5.sp
                        ),
                        TelemetryMetricSpec(
                            icon = Icons.Default.Speed,
                            label = stringResource(R.string.max_speed),
                            value = drive.speedMax?.takeIf { drive.hasKnownLengthUnit && it >= 0 }?.let { "%,d".format(it) }
                                ?: stringResource(R.string.not_available),
                            tint = Color(0xFF6366F1),
                            unit = UnitFormatter.getSpeedUnit(receiptUnits),
                            valueFontSize = 17.5.sp
                        ),
                        TelemetryMetricSpec(
                            icon = Icons.Default.Timer,
                            label = stringResource(R.string.duration),
                            value = drive.durationMin?.takeIf { it >= 0 }?.let { "%,d".format(it) }
                                ?: stringResource(R.string.not_available),
                            tint = Color(0xFFD97706),
                            unit = stringResource(R.string.visual_trip_duration_unit),
                            valueFontSize = 17.5.sp
                        )
                    ),
                    modifier = Modifier.padding(vertical = 4.dp),
                    adaptive = false,
                    maxColumns = 4
                )
            }
        }
    }
}

@Composable
private fun ParkedItem(
    item: DriveHistoryItem.Parked,
    palette: CarColorPalette,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val unknown = stringResource(R.string.unknown)
    val durationText = formatDuration(
        context.resources,
        item.durationMin.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    )
    val batteryStart = item.olderDrive.endBatteryLevel
    val batteryEnd = item.newerDrive.startBatteryLevel

    TelemetryPanel(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        accent = MaterialTheme.colorScheme.outline
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                shape = CircleShape
            ) {
                Icon(
                    imageVector = Icons.Default.LocalParking,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(10.dp).size(22.dp)
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = stringResource(R.string.drive_history_parked_at, item.location.toChineseDisplayAddress() ?: unknown),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = formatEditorialDate(item.startDate, true),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = durationText,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                if (batteryStart != null && batteryEnd != null) {
                    Text(
                        text = UnitFormatter.formatSocRange(batteryStart, batteryEnd, ""),
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.accent
                    )
                }
            }
        }
    }
}

/**
 * Chart type enum for the swipeable pager
 */
private enum class DrivesChartType {
    COUNT, TIME, DISTANCE, TOP_SPEED
}

/**
 * Swipeable pager containing Count, Time, and Distance charts with page indicator dots
 */
@Composable
private fun DrivesChartsPager(
    chartData: List<DriveChartData>,
    granularity: DriveChartGranularity,
    units: Units?,
    palette: CarColorPalette
) {
    val pagerState = rememberPagerState(pageCount = { DrivesChartType.entries.size })

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
                    val chartType = DrivesChartType.entries[page]
                    DrivesChartPage(
                        chartData = chartData,
                        granularity = granularity,
                        chartType = chartType,
                        units = units,
                        palette = palette
                    )
                }
            }
        }

        // Page indicator dots
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center
        ) {
            repeat(DrivesChartType.entries.size) { index ->
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
 * Individual chart page showing Count, Time, or Distance data
 */
@Composable
private fun DrivesChartPage(
    chartData: List<DriveChartData>,
    granularity: DriveChartGranularity,
    chartType: DrivesChartType,
    units: Units?,
    palette: CarColorPalette
) {
    val isImperial = units?.isImperial == true
    val distanceUnit = if (isImperial) "mi" else "km"
    val speedUnit = if (isImperial) "mph" else "km/h"
    val durationUnit = stringResource(R.string.visual_trip_duration_unit)
    val countUnit = stringResource(R.string.visual_trip_count_unit)
    val resources = LocalContext.current.resources

    val (title, icon) = when (chartType) {
        DrivesChartType.COUNT -> when (granularity) {
            DriveChartGranularity.DAILY -> stringResource(R.string.chart_drives_per_day)
            DriveChartGranularity.WEEKLY -> stringResource(R.string.chart_drives_per_week)
            DriveChartGranularity.MONTHLY -> stringResource(R.string.chart_drives_per_month)
        } to Icons.Default.DirectionsCar
        DrivesChartType.TIME -> when (granularity) {
            DriveChartGranularity.DAILY -> stringResource(R.string.chart_time_per_day)
            DriveChartGranularity.WEEKLY -> stringResource(R.string.chart_time_per_week)
            DriveChartGranularity.MONTHLY -> stringResource(R.string.chart_time_per_month)
        } to Icons.Default.Timer
        DrivesChartType.DISTANCE -> when (granularity) {
            DriveChartGranularity.DAILY -> stringResource(R.string.chart_distance_per_day)
            DriveChartGranularity.WEEKLY -> stringResource(R.string.chart_distance_per_week)
            DriveChartGranularity.MONTHLY -> stringResource(R.string.chart_distance_per_month)
        } to CustomIcons.SteeringWheel
        DrivesChartType.TOP_SPEED -> when (granularity) {
            DriveChartGranularity.DAILY -> stringResource(R.string.chart_speed_per_day)
            DriveChartGranularity.WEEKLY -> stringResource(R.string.chart_speed_per_week)
            DriveChartGranularity.MONTHLY -> stringResource(R.string.chart_speed_per_month)
        } to Icons.Default.Speed
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
            DrivesChartType.COUNT -> chartData.map { data ->
                BarChartData(
                    label = data.label,
                    value = data.count.toDouble(),
                    displayValue = "${data.count} $countUnit"
                )
            }
            DrivesChartType.TIME -> chartData.mapNotNull { data ->
                val duration = data.totalDurationMin ?: return@mapNotNull null
                val coverage = if (data.durationSampleCount < data.count)
                    " · ${resources.getString(R.string.stats_analysis_coverage_records, data.durationSampleCount, data.count)} $countUnit"
                    else ""
                BarChartData(
                    label = data.label,
                    value = duration.toDouble(),
                    displayValue = "$duration $durationUnit$coverage"
                )
            }
            DrivesChartType.DISTANCE -> chartData.mapNotNull { data ->
                val distance = data.totalDistance?.let { UnitFormatter.distanceKmDisplayValue(it, units) } ?: return@mapNotNull null
                val coverage = if (data.distanceSampleCount < data.count)
                    " · ${resources.getString(R.string.stats_analysis_coverage_records, data.distanceSampleCount, data.count)} $countUnit"
                    else ""
                BarChartData(
                    label = data.label,
                    value = distance,
                    displayValue = "${visualTripNumber(distance)} $distanceUnit$coverage"
                )
            }
            DrivesChartType.TOP_SPEED -> chartData.mapNotNull { data ->
                val speed = data.maxSpeed?.let { UnitFormatter.speedKmhDisplayValue(it.toDouble(), units) } ?: return@mapNotNull null
                BarChartData(
                    label = data.label,
                    value = speed.toDouble(),
                    displayValue = "${visualTripNumber(speed, 0)} $speedUnit"
                )
            }
        }

        val valueFormatter: (Double) -> String = when (chartType) {
            DrivesChartType.COUNT -> { v -> "${v.toInt()} $countUnit" }
            DrivesChartType.TIME -> { v -> "${v.toInt()} $durationUnit" }
            DrivesChartType.DISTANCE -> { v -> "${visualTripNumber(v)} $distanceUnit" }
            DrivesChartType.TOP_SPEED -> { v -> "${v.toInt()} $speedUnit" }
        }

        // Set number of labels to display
        val labelInterval = when {
            barData.size <= 7 -> 1  // Show all for Today and last 7 days
            barData.size <= 30 -> 3 // Show 1 label every 3 bars for lsat 30 days
            else -> ((barData.size + 5) / 6).coerceAtLeast(1)
        }
        val yAxisFormatter: (Double) -> String = when (chartType) {
            DrivesChartType.TIME -> { v -> "%,d".format(v.toInt()) }
            else -> { v -> if (v >= 1000) "%.0fk".format(v / 1000) else "%.0f".format(v) }
        }

        val axisUnit = when (chartType) {
            DrivesChartType.COUNT -> countUnit
            DrivesChartType.TIME -> durationUnit
            DrivesChartType.DISTANCE -> distanceUnit
            DrivesChartType.TOP_SPEED -> speedUnit
        }
        Text(
            text = stringResource(R.string.visual_trip_chart_unit, axisUnit),
            style = MaterialTheme.typography.labelMedium,
            color = palette.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 6.dp)
        )

        val sampleCount = when (chartType) {
            DrivesChartType.TIME -> chartData.sumOf { it.durationSampleCount }
            DrivesChartType.DISTANCE -> chartData.sumOf { it.distanceSampleCount }
            else -> null
        }
        val recordCount = chartData.sumOf { it.count }
        if (sampleCount != null && sampleCount < recordCount) {
            Text(
                text = "${stringResource(R.string.stats_analysis_data_coverage)} · " +
                    stringResource(R.string.stats_analysis_coverage_records, sampleCount, recordCount) + " $countUnit",
                style = MaterialTheme.typography.bodySmall,
                color = palette.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 6.dp)
            )
        }
        if (barData.isEmpty()) {
            Text(
                text = stringResource(R.string.not_available),
                style = MaterialTheme.typography.bodyMedium,
                color = palette.onSurfaceVariant
            )
        }

        InteractiveBarChart(
            data = barData,
            modifier = Modifier.fillMaxWidth(),
            barColor = Color(0xFF0891B2),
            labelColor = palette.onSurfaceVariant,
            showEveryNthLabel = labelInterval,
            valueFormatter = valueFormatter,
            yAxisFormatter = yAxisFormatter
        )
    }
}

/** Display-only precision: a nonzero observation must not look like a true zero. */
internal fun visualTripNumber(value: Double, decimals: Int = 1): String {
    return com.matelink.ui.components.formatTelemetryNumber(value, decimals)
}
