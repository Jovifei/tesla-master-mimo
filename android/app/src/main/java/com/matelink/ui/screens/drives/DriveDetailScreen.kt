package com.matelink.ui.screens.drives

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.DeviceThermostat
import androidx.compose.material.icons.filled.Landscape
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.ShowChart
import com.matelink.ui.icons.CustomIcons
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import com.matelink.ui.components.HistoryForegroundRefreshEffect
import com.matelink.ui.components.HistoryReadNotices
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

import androidx.hilt.navigation.compose.hiltViewModel
import kotlin.math.roundToInt
import com.matelink.R
import com.matelink.data.api.models.DriveDetail
import com.matelink.data.api.models.DrivePosition
import com.matelink.data.api.models.Units
import com.matelink.data.repository.WeatherPoint
import com.matelink.domain.model.UnitFormatter
import com.matelink.data.api.models.drivePresentationUnits
import com.matelink.ui.components.AmapRouteView
import com.matelink.ui.components.FullscreenLineChart
import com.matelink.ui.components.MateLinkLoadingPlaceholder
import com.matelink.ui.components.RouteIndicator
import com.matelink.ui.components.TelemetryPanel
import com.matelink.ui.components.TelemetrySectionHeader
import com.matelink.ui.screens.trips.displayName
import com.matelink.ui.theme.telemetryPalette
import com.matelink.util.formatDuration
import com.matelink.util.formatMonthDayTime
import com.matelink.util.formatTime
import com.matelink.util.parseIsoDateTime
import com.matelink.util.toChineseDisplayAddress

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DriveDetailScreen(
    carId: Int,
    driveId: Int,
    exteriorColor: String? = null,
    onNavigateBack: () -> Unit,
    onNavigateToTripDetail: (tripStartDate: String) -> Unit = {},
    onNavigateToDriveCurves: () -> Unit = {},
    viewModel: DriveDetailViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val palette = telemetryPalette()

    HistoryForegroundRefreshEffect { viewModel.loadDriveDetail(carId, driveId) }
    LaunchedEffect(carId, driveId) {
        viewModel.loadDriveDetail(carId, driveId)
    }

    LaunchedEffect(uiState.error) {
        uiState.error?.let { error ->
            snackbarHostState.showSnackbar(error)
            viewModel.clearError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.drive_details_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onNavigateToDriveCurves) {
                        Icon(
                            Icons.Default.ShowChart,
                            contentDescription = stringResource(R.string.drive_curves_title)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        if (uiState.isLoading) {
            MateLinkLoadingPlaceholder(
                color = palette.accent,
                modifier = Modifier.padding(padding)
            )
        } else {
            uiState.driveDetail?.let { detail ->
                DriveDetailContent(
                    localArchiveLinkPending = uiState.localArchiveLinkPending,
                    historySyncWarning = uiState.historySyncWarning,
                    detail = detail,
                    stats = uiState.stats,
                    units = drivePresentationUnits(detail.lengthUnit, uiState.units),
                    routeColor = palette.accent,
                    weatherPoints = uiState.weatherPoints,
                    isLoadingWeather = uiState.isLoadingWeather,
                    containingTrip = uiState.containingTrip,
                    onNavigateToTripDetail = onNavigateToTripDetail,
                    onNavigateToDriveCurves = onNavigateToDriveCurves,
                    onRemoveFromTrip = viewModel::removeFromTrip,
                    modifier = Modifier.padding(padding)
                )
            }
        }
    }
}

@Composable
private fun DriveDetailContent(
    localArchiveLinkPending: Boolean,
    historySyncWarning: String?,
    detail: DriveDetail,
    stats: DriveDetailStats?,
    units: Units?,
    routeColor: Color,
    weatherPoints: List<WeatherPoint>,
    isLoadingWeather: Boolean,
    containingTrip: Pair<Long, com.matelink.domain.model.Trip>?,
    onNavigateToTripDetail: (String) -> Unit,
    onNavigateToDriveCurves: () -> Unit,
    onRemoveFromTrip: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val is24Hour = android.text.format.DateFormat.is24HourFormat(context)
    val scrollState = rememberScrollState()

    val positions = detail.positions.orEmpty()
    val hasChartData = positions.size > 2
    val timeLabels = remember(positions, is24Hour) {
        if (hasChartData) extractTimeLabels(positions, is24Hour) else emptyList()
    }
    val fractionToTimeLabel: (Float) -> String = remember(positions, is24Hour) {
        { fraction: Float ->
            if (positions.isEmpty()) {
                ""
            } else {
                val index = (fraction * positions.lastIndex).roundToInt()
                    .coerceIn(0, positions.lastIndex)
                positions[index].date?.let { dateStr ->
                    parseIsoDateTime(dateStr)
                        ?.formatTime(java.util.Locale.getDefault(), is24Hour)
                        ?: ""
                } ?: ""
            }
        }
    }
    var speedChartFraction by remember { mutableStateOf<Float?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        HistoryReadNotices(localArchiveLinkPending, historySyncWarning)

        // Map showing the route
        if (!detail.positions.isNullOrEmpty()) {
            DriveMapCard(positions = detail.positions, routeColor = routeColor)
        }

        // Route header card
        RouteHeaderCard(detail = detail)

        // Part-of-trip banner: link to the containing saved trip + detach action
        if (containingTrip != null) {
            val (_, trip) = containingTrip
            com.matelink.ui.components.PartOfTripCard(
                tripRoute = trip.displayName(),
                onNavigateToTrip = { onNavigateToTripDetail(trip.startDate) },
                onConfirmRemove = onRemoveFromTrip
            )
        }

        // Stats grid
        stats?.let { s ->
            val notAvailableLabel = stringResource(R.string.not_available)
            val energySourceLabel = stringResource(R.string.energy_source)
            val energyCoverageLabel = stringResource(R.string.energy_coverage)
            val curvesActionLabel = stringResource(R.string.drive_curves_action)

            // 1. 行程面板 (Distance & Duration section)
            StatsSectionCard(
                title = stringResource(R.string.trip),
                icon = CustomIcons.SteeringWheel,
                actionText = curvesActionLabel,
                onActionClick = onNavigateToDriveCurves,
                stats = listOf(
                    StatItem(stringResource(R.string.distance), s.distance?.let { visualTripNumber(it) } ?: notAvailableLabel, UnitFormatter.getDistanceUnit(units)),
                    StatItem(stringResource(R.string.duration), s.durationMin?.let { "%,d".format(it) } ?: notAvailableLabel, stringResource(R.string.visual_trip_duration_unit)),
                    StatItem(
                        stringResource(R.string.efficiency),
                        s.energy.efficiencyWhKm?.let { visualTripNumber(it / 10.0) }
                            ?: notAvailableLabel,
                        unit = "kWh/100km",
                        recovered = (s.energy.efficiencyWhKm ?: 0.0) < 0.0
                    )
                )
            )

            // 2. 速度面板 (Speed section)
            StatsSectionCard(
                title = stringResource(R.string.speed),
                icon = Icons.Default.Speed,
                actionText = curvesActionLabel,
                onActionClick = onNavigateToDriveCurves,
                stats = listOf(
                    StatItem(stringResource(R.string.maximum), s.speedMax?.let { "%,d".format(it) } ?: notAvailableLabel, UnitFormatter.getSpeedUnit(units)),
                    StatItem(stringResource(R.string.average), s.speedAvg?.let { visualTripNumber(it, 0) } ?: notAvailableLabel, UnitFormatter.getSpeedUnit(units)),
                    StatItem(stringResource(R.string.avg_distance), s.avgSpeedFromDistance?.let { visualTripNumber(it, 0) } ?: notAvailableLabel, UnitFormatter.getSpeedUnit(units))
                )
            )

            // 3. 速度曲线 (Speed profile curve - inline)
            if (hasChartData) {
                SpeedChartCard(
                    positions = positions,
                    units = units,
                    timeLabels = timeLabels,
                    externalSelectedFraction = speedChartFraction,
                    onXSelected = { speedChartFraction = it },
                    fractionToTimeLabel = fractionToTimeLabel
                )
            }

            // 4. 功率面板 (Power section)
            StatsSectionCard(
                title = stringResource(R.string.power),
                icon = Icons.Default.Bolt,
                actionText = curvesActionLabel,
                onActionClick = onNavigateToDriveCurves,
                stats = listOf(
                    StatItem(stringResource(R.string.max_accel), s.powerMax?.toString() ?: notAvailableLabel, "kW"),
                    StatItem(stringResource(R.string.min_regen), s.powerMin?.toString() ?: notAvailableLabel, "kW"),
                    StatItem(stringResource(R.string.average), s.powerAvg?.let { visualTripNumber(it) } ?: notAvailableLabel, "kW")
                )
            )

            // 5. 电量面板 (Battery section)
            StatsSectionCard(
                title = stringResource(R.string.battery),
                icon = Icons.Default.BatteryChargingFull,
                actionText = curvesActionLabel,
                onActionClick = onNavigateToDriveCurves,
                stats = listOf(
                    StatItem(stringResource(R.string.start), s.batteryStart?.toString() ?: notAvailableLabel, "%"),
                    StatItem(stringResource(R.string.end), s.batteryEnd?.toString() ?: notAvailableLabel, "%"),
                    StatItem(stringResource(R.string.used), s.batteryUsed?.toString() ?: notAvailableLabel, "%"),
                    StatItem(
                        stringResource(R.string.energy),
                        s.energy.energyKwh?.let { visualTripNumber(it, 2) } ?: notAvailableLabel,
                        unit = "kWh",
                        recovered = (s.energy.energyKwh ?: 0.0) < 0.0
                    ),
                    StatItem(
                        energySourceLabel,
                        when {
                            s.energy.energyKwh == null -> notAvailableLabel
                            s.energy.source == DriveDetailEnergySource.API -> stringResource(R.string.energy_source_api_reported)
                            s.energy.source == DriveDetailEnergySource.POWER_SAMPLES -> {
                                val coverage = if ((s.energy.coverageRatio ?: 0.0) > 0.0) {
                                    " (${"%.0f".format(s.energy.coverageRatio!! * 100)}%)"
                                } else ""
                                "${stringResource(R.string.energy_source_power_samples)}$coverage"
                            }
                            s.energy.source == DriveDetailEnergySource.ENERGY_REMAINING_DELTA -> stringResource(R.string.energy_nominal_estimate)
                            s.energy.source == DriveDetailEnergySource.SOC_CAPACITY_ESTIMATE -> {
                                val basis = when (s.energy.evidence?.capacityBasis) {
                                    "model_trim_assumption" -> stringResource(R.string.energy_capacity_model_trim)
                                    "model_assumption" -> stringResource(R.string.energy_capacity_model)
                                    "api_reported_current_capacity" -> stringResource(R.string.energy_capacity_api_report)
                                    "provided_capacity" -> stringResource(R.string.energy_capacity_provided)
                                    else -> stringResource(R.string.energy_capacity_default)
                                }
                                s.energy.evidence?.capacityKwh?.let {
                                    stringResource(R.string.energy_capacity_description, it, basis)
                                } ?: stringResource(R.string.energy_source_soc_estimate)
                            }
                            s.energy.isEstimated -> stringResource(R.string.range_estimated)
                            else -> notAvailableLabel
                        }
                    ),
                    StatItem(
                        energyCoverageLabel,
                        if (s.energy.evidence?.coverageKind == "endpoints") stringResource(R.string.energy_endpoint_coverage)
                        else s.energy.coverageRatio?.let { ratio ->
                            val seconds = s.energy.coverageSeconds?.let { "$it s" } ?: notAvailableLabel
                            "%.0f%% · %s".format(ratio * 100, seconds)
                        } ?: notAvailableLabel
                    )
                )
            )

            // 6. 海拔面板 (Elevation section)
            if (s.elevationMax != null || s.elevationMin != null || s.elevationGain != null || s.elevationLoss != null) {
                StatsSectionCard(
                    title = stringResource(R.string.elevation),
                    icon = Icons.Default.Landscape,
                    actionText = curvesActionLabel,
                    onActionClick = onNavigateToDriveCurves,
                    stats = listOf(
                        StatItem(stringResource(R.string.maximum), UnitFormatter.formatElevation(s.elevationMax, units).removeSuffix(" ${UnitFormatter.getElevationUnit(units)}"), UnitFormatter.getElevationUnit(units)),
                        StatItem(stringResource(R.string.minimum), UnitFormatter.formatElevation(s.elevationMin, units).removeSuffix(" ${UnitFormatter.getElevationUnit(units)}"), UnitFormatter.getElevationUnit(units)),
                        StatItem(stringResource(R.string.gain), s.elevationGain?.let { "+${UnitFormatter.formatElevation(it, units).removeSuffix(" ${UnitFormatter.getElevationUnit(units)}")}" } ?: notAvailableLabel, UnitFormatter.getElevationUnit(units)),
                        StatItem(stringResource(R.string.loss), s.elevationLoss?.let { "-${UnitFormatter.formatElevation(it, units).removeSuffix(" ${UnitFormatter.getElevationUnit(units)}")}" } ?: notAvailableLabel, UnitFormatter.getElevationUnit(units))
                    )
                )
            }

            // 6. 温度面板 (Temperature section)
            if (s.outsideTempAvg != null || s.insideTempAvg != null) {
                StatsSectionCard(
                    title = stringResource(R.string.temperature),
                    icon = Icons.Default.DeviceThermostat,
                    stats = listOfNotNull(
                        s.outsideTempAvg?.let { StatItem(stringResource(R.string.outside), UnitFormatter.formatTemperature(it, units).removeSuffix(UnitFormatter.getTemperatureUnit(units)), UnitFormatter.getTemperatureUnit(units)) },
                        s.insideTempAvg?.let { StatItem(stringResource(R.string.inside), UnitFormatter.formatTemperature(it, units).removeSuffix(UnitFormatter.getTemperatureUnit(units)), UnitFormatter.getTemperatureUnit(units)) }
                    )
                )
            }
        }

        // Weather along the way - shown when loading or has data
        if (isLoadingWeather || weatherPoints.isNotEmpty()) {
            WeatherAlongTheWayCard(
                weatherPoints = weatherPoints,
                units = units,
                isLoading = isLoadingWeather
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun RouteHeaderCard(detail: DriveDetail) {
    val is24Hour = android.text.format.DateFormat.is24HourFormat(LocalContext.current)
    TelemetryPanel(
        modifier = Modifier.fillMaxWidth(),
        accent = MaterialTheme.colorScheme.primary
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            TelemetrySectionHeader(
                icon = CustomIcons.SteeringWheel,
                title = stringResource(R.string.trip)
            )

            val unknownLocation = stringResource(R.string.unknown_location)
            val defaultStart = detail.startAddress?.takeIf { it.isNotBlank() && !it.contains("°N") && it != "30.27°N, 120.15°E" }?.toChineseDisplayAddress()
            val defaultEnd = detail.endAddress?.takeIf { it.isNotBlank() && !it.contains("°N") && it != "30.27°N, 120.15°E" }?.toChineseDisplayAddress()
            RouteIndicator(
                start = defaultStart ?: defaultEnd ?: unknownLocation,
                end = defaultEnd ?: defaultStart ?: unknownLocation
            )

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                DetailTimeValue(
                    label = stringResource(R.string.started),
                    value = formatDateTime(detail.startDate, is24Hour),
                    modifier = Modifier.weight(1f)
                )
                DetailTimeValue(
                    label = stringResource(R.string.ended),
                    value = formatDateTime(detail.endDate, is24Hour),
                    modifier = Modifier.weight(1f)
                )
                DetailTimeValue(
                    label = stringResource(R.string.duration),
                    value = detail.durationMin?.let { formatDuration(LocalContext.current.resources, it) }
                        ?: detail.durationStr?.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.not_available),
                    modifier = Modifier.weight(0.8f)
                )
            }
        }
    }
}

@Composable
private fun DetailTimeValue(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun DriveMapCard(positions: List<DrivePosition>, routeColor: Color) {
    val startLabel = stringResource(R.string.start)
    val endLabel = stringResource(R.string.end)
    val validPositions = positions.filter { it.latitude != null && it.longitude != null }

    if (validPositions.isEmpty()) return

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
                    modifier = Modifier.padding(12.dp)
        ) {
            Text(
                text = stringResource(R.string.route_map),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(250.dp)
                    .clip(RoundedCornerShape(8.dp))
            ) {
                // Route map using AmapRouteView (高德)
                val routePoints = validPositions.mapNotNull { pos ->
                    val lat = pos.latitude
                    val lng = pos.longitude
                    if (lat != null && lng != null) Pair(lat, lng) else null
                }
                AmapRouteView(
                    modifier = Modifier.fillMaxSize(),
                    routePoints = routePoints,
                    startTitle = startLabel,
                    endTitle = endLabel,
                    routeColor = routeColor
                )
            }
        }
    }
}

data class StatItem(
    val label: String,
    val value: String,
    val unit: String? = null,
    val recovered: Boolean = false
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StatsSectionCard(
    title: String,
    icon: ImageVector,
    stats: List<StatItem>,
    actionText: String? = null,
    onActionClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val configuration = LocalConfiguration.current
    val screenWidth = configuration.screenWidthDp

    val columnCount = if (screenWidth >= 600 && configuration.fontScale <= 1.1f) 3 else 2
    val palette = telemetryPalette()

    Card(
        modifier = modifier.fillMaxWidth(),
        onClick = { onActionClick?.invoke() },
        enabled = onActionClick != null,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = palette.surface
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                if (actionText != null && onActionClick != null) {
                    Surface(
                        onClick = onActionClick,
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = actionText,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.width(2.dp))
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }

            // Divide the list of statistics according to the calculated number of columns
            val chunked = stats.chunked(columnCount)
            chunked.forEachIndexed { index, row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    row.forEach { stat ->
                        StatItemView(
                            label = stat.label,
                            value = stat.value,
                            unit = stat.unit,
                            recovered = stat.recovered,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    // Fill the leftover space if the last row is not complete.
                    // This prevents a single item from stretching too much
                    val emptySlots = columnCount - row.size
                    if (emptySlots > 0) {
                        repeat(emptySlots) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
                if (index < chunked.size - 1) {
                    Spacer(modifier = Modifier.height(14.dp))
                }
            }
        }
    }
}

@Composable
private fun StatItemView(
    label: String,
    value: String,
    unit: String? = null,
    recovered: Boolean = false,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            fontSize = if (unit != null) 22.sp else 16.sp,
            color = if (recovered) Color(0xFF10B981) else MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold
        )
        unit?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SpeedChartCard(
    positions: List<DrivePosition>,
    units: Units?,
    timeLabels: List<String>,
    externalSelectedFraction: Float? = null,
    onXSelected: ((Float?) -> Unit)? = null,
    fractionToTimeLabel: ((Float) -> String)? = null
) {
    val speeds = remember(positions) { positions.mapNotNull { it.speed?.toFloat() } }
    if (speeds.size < 2) return

    val isImperial = units?.isImperial == true
    val stableConvertValue: (Float) -> Float = remember(isImperial) {
        { value: Float -> if (isImperial) (value * 0.621371f) else value }
    }

    ChartCard(
        title = stringResource(R.string.speed_profile),
        icon = Icons.Default.Speed,
        data = speeds,
        color = MaterialTheme.colorScheme.primary,
        unit = UnitFormatter.getSpeedUnit(units),
        timeLabels = timeLabels,
        externalSelectedFraction = externalSelectedFraction,
        onXSelected = onXSelected,
        fractionToTimeLabel = fractionToTimeLabel,
        convertValue = stableConvertValue
    )
}

@Composable
private fun PowerChartCard(
    positions: List<DrivePosition>,
    timeLabels: List<String>,
    externalSelectedFraction: Float? = null,
    onXSelected: ((Float?) -> Unit)? = null,
    fractionToTimeLabel: ((Float) -> String)? = null
) {
    val powers = remember(positions) { positions.mapNotNull { it.power?.toFloat() } }
    if (powers.size < 2) return

    ChartCard(
        title = stringResource(R.string.power_profile),
        icon = Icons.Default.Bolt,
        data = powers,
        color = MaterialTheme.colorScheme.tertiary,
        unit = "kW",
        showZeroLine = true,
        timeLabels = timeLabels,
        externalSelectedFraction = externalSelectedFraction,
        onXSelected = onXSelected,
        fractionToTimeLabel = fractionToTimeLabel
    )
}

@Composable
private fun BatteryChartCard(
    positions: List<DrivePosition>,
    timeLabels: List<String>,
    externalSelectedFraction: Float? = null,
    onXSelected: ((Float?) -> Unit)? = null,
    fractionToTimeLabel: ((Float) -> String)? = null
) {
    val batteryLevels = remember(positions) { positions.mapNotNull { it.batteryLevel?.toFloat() } }
    if (batteryLevels.size < 2) return
    val fixedMinMax = remember(batteryLevels) {
        var yMin = (kotlin.math.floor(batteryLevels.min() / 10.0) * 10).toFloat()
        var yMax = (kotlin.math.ceil(batteryLevels.max() / 10.0) * 10).toFloat()
        if (yMin == yMax) { yMin -= 1; yMax += 1 }
        Pair(yMin, yMax)
    }

    ChartCard(
        title = stringResource(R.string.battery_level),
        icon = Icons.Default.BatteryChargingFull,
        data = batteryLevels,
        color = MaterialTheme.colorScheme.secondary,
        unit = "%",
        fixedMinMax = fixedMinMax,
        timeLabels = timeLabels,
        externalSelectedFraction = externalSelectedFraction,
        onXSelected = onXSelected,
        fractionToTimeLabel = fractionToTimeLabel
    )
}

@Composable
private fun ElevationChartCard(
    positions: List<DrivePosition>,
    timeLabels: List<String>,
    externalSelectedFraction: Float? = null,
    onXSelected: ((Float?) -> Unit)? = null,
    fractionToTimeLabel: ((Float) -> String)? = null
) {
    val elevations = remember(positions) { positions.mapNotNull { it.elevation?.toFloat() } }
    if (elevations.size < 2) return

    ChartCard(
        title = stringResource(R.string.elevation_profile),
        icon = Icons.Default.Landscape,
        data = elevations,
        color = MaterialTheme.colorScheme.primary,
        unit = "m",
        timeLabels = timeLabels,
        externalSelectedFraction = externalSelectedFraction,
        onXSelected = onXSelected,
        fractionToTimeLabel = fractionToTimeLabel
    )
}

@Composable
private fun ChartCard(
    title: String,
    icon: ImageVector,
    data: List<Float>,
    color: Color,
    unit: String,
    showZeroLine: Boolean = false,
    fixedMinMax: Pair<Float, Float>? = null,
    timeLabels: List<String> = emptyList(),
    convertValue: (Float) -> Float = { it },
    externalSelectedFraction: Float? = null,
    onXSelected: ((Float?) -> Unit)? = null,
    fractionToTimeLabel: ((Float) -> String)? = null
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 8.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = color
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            FullscreenLineChart(
                data = data,
                color = color,
                unit = unit,
                showZeroLine = showZeroLine,
                fixedMinMax = fixedMinMax,
                timeLabels = timeLabels,
                convertValue = convertValue,
                externalSelectedFraction = externalSelectedFraction,
                onXSelected = onXSelected,
                fractionToTimeLabel = fractionToTimeLabel,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/**
 * Extract 5 time labels from drive positions for X axis display.
 * Returns list of 5 time strings at 0%, 25%, 50%, 75%, and 100% positions.
 * Following the chart guidelines: start, 1st quarter, half, 3rd quarter, end.
 */
private fun extractTimeLabels(positions: List<DrivePosition>, is24Hour: Boolean? = null): List<String> {
    if (positions.isEmpty()) return listOf("", "", "", "", "")

    val locale = java.util.Locale.getDefault()
    val times = positions.mapNotNull { position ->
        position.date?.let { parseIsoDateTime(it) }
    }

    if (times.isEmpty()) return listOf("", "", "", "", "")

    // 5 positions: start (0%), 1st quarter (25%), half (50%), 3rd quarter (75%), end (100%)
    val indices = listOf(0, times.size / 4, times.size / 2, times.size * 3 / 4, times.size - 1)
    return indices.map { idx ->
        times.getOrNull(idx.coerceIn(0, times.size - 1))?.formatTime(locale, is24Hour) ?: ""
    }
}

private fun formatDateTime(dateStr: String?, is24Hour: Boolean? = null): String {
    return formatMonthDayTime(dateStr, is24Hour = is24Hour) ?: "—"
}
