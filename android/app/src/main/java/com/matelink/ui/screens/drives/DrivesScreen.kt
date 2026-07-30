package com.matelink.ui.screens.drives

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryStd
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Eco
import androidx.compose.material.icons.filled.LocalParking
import com.matelink.ui.icons.CustomIcons
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import com.matelink.R
import com.matelink.data.api.models.DriveData
import com.matelink.data.api.models.Units
import com.matelink.domain.model.UnitFormatter
import com.matelink.ui.components.BarChartData
import com.matelink.ui.components.DateRangePickerDialog
import com.matelink.ui.components.InteractiveBarChart
import com.matelink.ui.components.MateLinkLoadingPlaceholder
import com.matelink.ui.components.MateLinkPulseSpinner
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
import com.matelink.util.formatDurationCompact
import com.matelink.util.toChineseDisplayAddress
import com.matelink.ui.theme.CarColorPalette
import com.matelink.ui.theme.CarColorPalettes
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
    val isDarkTheme = isSystemInDarkTheme()
    val palette = CarColorPalettes.forExteriorColor(exteriorColor, isDarkTheme)

    // Remember scroll state and restore from ViewModel
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = uiState.scrollPosition,
        initialFirstVisibleItemScrollOffset = uiState.scrollOffset
    )

    // Initialize ViewModel with carId (only loads data on first call)
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
    LaunchedEffect(uiState.error) {
        uiState.error?.let { error ->
            snackbarHostState.showSnackbar(
                if (error == "Server not configured") serverNotConfiguredMessage else error
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
            if (uiState.isLoading && !uiState.isRefreshing) {
                MateLinkLoadingPlaceholder(color = palette.accent)
            } else {
                DrivesContent(
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
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DrivesContent(
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
    val headerCount = 4 + (if (chartData.isNotEmpty()) 1 else 0)

    Box(modifier = Modifier.fillMaxSize()) {
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
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
            DistanceFilterChips(
                selectedFilter = selectedDistanceFilter,
                units = units,
                palette = palette,
                onFilterSelected = onDistanceFilterSelected
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
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
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
    val showSpinner = rememberDebouncedLoading(isFilterLoading)
    if (showSpinner) {
        // Full-bleed dim scrim that sits above everything (chart cards inside the
        // LazyColumn have shadow elevation, which puts them in their own graphics
        // layer; without the explicit zIndex the spinner could end up rendered
        // behind them). The list shows through at ~55% alpha.
        Box(
            modifier = Modifier
                .matchParentSize()
                .zIndex(10f)
                .background(Color.Black.copy(alpha = 0.45f)),
            contentAlignment = Alignment.Center,
        ) {
            MateLinkPulseSpinner(color = palette.accent)
        }
    }
    }
}

private sealed interface DriveHistoryItem {
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

private fun buildDriveHistoryItems(drives: List<DriveData>): List<DriveHistoryItem> {
    val routeDrives = drives.filter { (it.distance ?: 0.0) >= 0.5 }
    if (routeDrives.isEmpty()) return emptyList()
    val items = mutableListOf<DriveHistoryItem>()
    routeDrives.forEachIndexed { index, drive ->
        items += DriveHistoryItem.Drive(drive)
        val olderDrive = routeDrives.getOrNull(index + 1) ?: return@forEachIndexed
        val parked = createParkedSegment(olderDrive, drive)
        if (parked != null) items += parked
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
        location = olderDrive.endAddress ?: newerDrive.startAddress
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
        items(DriveDateFilter.entries.filter { it != DriveDateFilter.CUSTOM }) { filter ->
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
                            "%,d".format(summary.totalDrives),
                            palette.accent
                        ),
                        TelemetryMetricSpec(
                            CustomIcons.SteeringWheel,
                            stringResource(R.string.total_distance),
                            UnitFormatter.formatDistance(summary.totalDistanceKm, units),
                            palette.accent
                        ),
                        TelemetryMetricSpec(
                            Icons.Default.Timer,
                            stringResource(R.string.total_time),
                            formatDuration(LocalContext.current.resources, summary.totalDurationMin),
                            Color(0xFFF59E0B)
                        ),
                        TelemetryMetricSpec(
                            Icons.Default.Speed,
                            stringResource(R.string.max_speed),
                            UnitFormatter.formatSpeed(summary.maxSpeedKmh.toDouble(), units),
                            Color(0xFF22C55E)
                        )
                    ),
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun DriveItem(
    drive: DriveData,
    metrics: DriveHistoryMetrics?,
    units: Units?,
    palette: CarColorPalette,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val unknown = stringResource(R.string.unknown)
    val startCity = drive.startAddress.toChineseDisplayAddress() ?: unknown
    val endCity = drive.endAddress.toChineseDisplayAddress() ?: unknown

    val efficiency = metrics?.efficiencyWhKm ?: drive.efficiencyWhKm
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = formatEditorialDate(drive.startDate, true),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                metrics?.source?.takeIf { it.isNotBlank() }?.let { source ->
                    val sourceLabel = if (source == "power_samples") {
                        val coverage = if (metrics.coverageRatio > 0.0) {
                            " ${"%.0f".format(metrics.coverageRatio * 100)}%"
                        } else ""
                        "${stringResource(R.string.range_estimated)}$coverage"
                    } else {
                        "API"
                    }
                    Surface(
                        color = palette.accent.copy(alpha = 0.13f),
                        shape = MaterialTheme.shapes.extraLarge
                    ) {
                        Text(
                            text = sourceLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = palette.accent,
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            RouteIndicator(
                start = startCity,
                end = endCity,
                accent = palette.accent
            )

            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = MaterialTheme.shapes.medium
            ) {
                TelemetryMetricStrip(
                    metrics = listOf(
                        TelemetryMetricSpec(
                            icon = Icons.Default.Timer,
                            label = stringResource(R.string.duration),
                            value = drive.durationMin?.let(::formatDurationCompact)
                                ?: stringResource(R.string.not_available),
                            tint = Color(0xFFF59E0B)
                        ),
                        TelemetryMetricSpec(
                            icon = CustomIcons.SteeringWheel,
                            label = stringResource(R.string.distance),
                            value = drive.distance
                                ?.takeIf { it.isFinite() && it >= 0.0 }
                                ?.let { UnitFormatter.formatDistance(it, units) }
                                ?: stringResource(R.string.not_available),
                            tint = palette.accent
                        ),
                        TelemetryMetricSpec(
                            icon = Icons.Default.Eco,
                            label = stringResource(R.string.efficiency),
                            value = efficiency
                                ?.takeIf { it.isFinite() && it >= 0.0 }
                                ?.let { UnitFormatter.formatEfficiency(it, units) }
                                ?: stringResource(R.string.not_available),
                            tint = Color(0xFF22C55E)
                        ),
                        TelemetryMetricSpec(
                            icon = Icons.Default.BatteryStd,
                            label = stringResource(R.string.battery),
                            value = if (start != null && start in 0..100 && end != null && end in 0..100) {
                                "$start→$end%"
                            } else {
                                stringResource(R.string.not_available)
                            },
                            tint = Color(0xFFF97316)
                        )
                    ),
                    modifier = Modifier.padding(vertical = 8.dp)
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
                    text = stringResource(R.string.drive_history_parked_at, item.location ?: unknown),
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
                        text = "$batteryStart→$batteryEnd%",
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
                    displayValue = data.count.toString()
                )
            }
            DrivesChartType.TIME -> chartData.map { data ->
                BarChartData(
                    label = data.label,
                    value = data.totalDurationMin.toDouble(),
                    displayValue = formatDurationCompact(data.totalDurationMin)
                )
            }
            DrivesChartType.DISTANCE -> chartData.map { data ->
                val distance = data.totalDistance
                BarChartData(
                    label = data.label,
                    value = distance,
                    displayValue = "%.1f $distanceUnit".format(distance)
                )
            }
            DrivesChartType.TOP_SPEED -> chartData.map { data ->
                val speed = data.maxSpeed
                BarChartData(
                    label = data.label,
                    value = speed.toDouble(),
                    displayValue = "$speed $speedUnit"
                )
            }
        }

        val valueFormatter: (Double) -> String = when (chartType) {
            DrivesChartType.COUNT -> { v -> v.toInt().toString() }
            DrivesChartType.TIME -> { v -> formatDurationCompact(v.toInt()) }
            DrivesChartType.DISTANCE -> { v -> "%.1f $distanceUnit".format(v) }
            DrivesChartType.TOP_SPEED -> { v -> "${v.toInt()} $speedUnit" }
        }

        // Set number of labels to display
        val labelInterval = when {
            barData.size <= 7 -> 1  // Show all for Today and last 7 days
            barData.size <= 30 -> 3 // Show 1 label every 3 bars for lsat 30 days
            else -> ((barData.size + 5) / 6).coerceAtLeast(1)
        }
        val yAxisFormatter: (Double) -> String = when (chartType) {
            DrivesChartType.TIME -> { v -> formatDurationCompact(v.toInt()) }
            else -> { v -> if (v >= 1000) "%.0fk".format(v / 1000) else "%.0f".format(v) }
        }

        InteractiveBarChart(
            data = barData,
            modifier = Modifier.fillMaxWidth(),
            barColor = palette.accent,
            labelColor = palette.onSurfaceVariant,
            showEveryNthLabel = labelInterval,
            valueFormatter = valueFormatter,
            yAxisFormatter = yAxisFormatter
        )
    }
}
