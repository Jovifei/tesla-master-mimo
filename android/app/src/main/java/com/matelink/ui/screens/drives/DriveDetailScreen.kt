package com.matelink.ui.screens.drives

import com.matelink.ui.components.RouteInteractionVisual
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.ShowChart
import com.matelink.ui.icons.CustomIcons
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.TextButton
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
import com.matelink.ui.components.HistoryArchiveNotice
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color

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
import com.matelink.domain.model.UnitFormatter
import com.matelink.data.api.models.drivePresentationUnits
import com.matelink.domain.map.AmapConfiguration
import com.matelink.ui.components.AmapRouteView
import com.matelink.ui.components.FullscreenLineChart
import com.matelink.ui.components.MateLinkLoadingPlaceholder
import com.matelink.ui.components.showHistoryReadFailure
import com.matelink.ui.components.RouteIndicator
import com.matelink.ui.components.TelemetryPanel
import com.matelink.ui.components.TelemetrySectionHeader
import com.matelink.ui.screens.trips.displayName
import com.matelink.ui.theme.telemetryPalette
import com.matelink.util.formatDuration
import com.matelink.ui.components.recordedTripEndpoint
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
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val feedbackContext = LocalContext.current
    val palette = telemetryPalette()

    HistoryForegroundRefreshEffect { viewModel.loadDriveDetail(carId, driveId) }
    LaunchedEffect(carId, driveId) {
        viewModel.loadDriveDetail(carId, driveId)
    }

    LaunchedEffect(uiState.error) {
        uiState.error?.let { error ->
            val hasContent = uiState.driveDetail != null
            val retried = snackbarHostState.showHistoryReadFailure(feedbackContext, error, hasContent) {
                viewModel.clearError()
                viewModel.loadDriveDetail(carId, driveId)
            }
            if (!retried && hasContent) viewModel.clearError()
        }
    }

    HistoryArchiveNotice(uiState.localArchiveLinkPending, uiState.historyNoticeLocalCarId)

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
        if (uiState.driveDetail != null) {
            val detail = uiState.driveDetail!!
            DriveDetailContent(
                localArchiveLinkPending = uiState.localArchiveLinkPending &&
                    uiState.historyNoticeLocalCarId?.let { it < 0 } != true,
                historySyncWarning = uiState.historySyncWarning,
                detail = detail,
                stats = uiState.stats,
                units = drivePresentationUnits(detail.lengthUnit, uiState.units),
                routeColor = palette.accent,
                containingTrip = uiState.containingTrip,
                onNavigateToTripDetail = onNavigateToTripDetail,
                onRemoveFromTrip = viewModel::removeFromTrip,
                modifier = Modifier.padding(padding)
            )
        } else if (uiState.isLoading) {
            MateLinkLoadingPlaceholder(
                color = palette.accent,
                modifier = Modifier.padding(padding)
            )
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
    containingTrip: Pair<Long, com.matelink.domain.model.Trip>?,
    onNavigateToTripDetail: (String) -> Unit,
    onRemoveFromTrip: () -> Unit,
    modifier: Modifier = Modifier
) {
    val is24Hour = true
    val scrollState = rememberScrollState()

    val positions = detail.positions.orEmpty()
    val projection = remember(detail) { projectDriveCurves(detail) }
    val timeLabels = remember(projection, is24Hour) { timedDriveAxisLabels(projection.speed, is24Hour) }
    val fractionToTimeLabel: (Float) -> String = remember(projection, is24Hour) {
        { fraction -> timedDriveCurveLabel(projection.speed, fraction, is24Hour) }
    }
    var speedChartFraction by remember { mutableStateOf<Float?>(null) }
    var showEnergyDialog by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        HistoryReadNotices(localArchiveLinkPending, historySyncWarning)

        // Map showing the route
        DriveMapCard(positions = detail.positions.orEmpty(), routeColor = routeColor, startDate = detail.startDate, endDate = detail.endDate)

        // Trip route, times and metrics
        UnifiedTripCard(
            detail = detail,
            stats = stats,
            units = units,
            onShowEnergyDialog = { showEnergyDialog = true }
        )

        val receiptUnits = drivePresentationUnits(detail.lengthUnit, units)
        run {
            SpeedCurveCard(
                positions = positions,
                projected = projection.speed,
                units = receiptUnits,
                timeLabels = timeLabels,
                externalSelectedFraction = speedChartFraction,
                onXSelected = { speedChartFraction = it },
                fractionToTimeLabel = fractionToTimeLabel,
                header = {
                    stats?.let { s ->
                        val unavailable = stringResource(R.string.not_available)
                        val speedUnit = UnitFormatter.getSpeedUnit(receiptUnits)
                        val sampleCount = if (detail.speedMax?.takeIf { it >= 0 } != null) {
                            detail.speedSampleCount?.takeIf { it > 0 }
                        } else {
                            projection.speed.validCount.takeIf { it > 0 }
                        }
                        val maximumLabel = driveDetailSpeedMaximumLabel(detail)
                        val maximum = stringResource(maximumLabel) +
                            if (maximumLabel == R.string.drive_detail_sample_maximum && sampleCount != null) {
                                " ($sampleCount)"
                            } else ""
                        CurveStatsRow(listOf(
                            StatItem(maximum, s.speedMax?.let { "%,d".format(it) } ?: unavailable, speedUnit),
                            StatItem(stringResource(driveDetailSpeedAverageLabel(detail)),
                                s.speedAvg?.let { visualTripNumber(it, 0) } ?: unavailable, speedUnit),
                            StatItem(stringResource(R.string.avg_distance),
                                s.avgSpeedFromDistance?.let { visualTripNumber(it, 0) } ?: unavailable, speedUnit)
                        ))
                    }
                }
            )
        }
        run {
            val powerTimeLabels = remember(projection, is24Hour) { timedDriveAxisLabels(projection.power, is24Hour) }
            PowerCurveCard(
                positions = positions,
                projected = projection.power,
                timeLabels = powerTimeLabels,
                externalSelectedFraction = speedChartFraction,
                onXSelected = { speedChartFraction = it },
                fractionToTimeLabel = { fraction -> timedDriveCurveLabel(projection.power, fraction, is24Hour) },
                footer = {
                    stats?.let { s ->
                        val unavailable = stringResource(R.string.not_available)
                        CurveStatsRow(listOf(
                            StatItem(stringResource(if (detail.powerMax == null)
                                R.string.drive_detail_sample_maximum else R.string.max_accel),
                                s.powerMax?.toString() ?: unavailable, "kW"),
                            StatItem(stringResource(if (detail.powerMin == null)
                                R.string.drive_detail_sample_minimum else R.string.min_regen),
                                s.powerMin?.toString() ?: unavailable, "kW"),
                            StatItem(stringResource(driveDetailPowerAverageLabel(detail)),
                                s.powerAvg?.let { visualTripNumber(it) } ?: unavailable, "kW")
                        ))
                    }
                }
            )
        }

        // Part-of-trip banner: link to the containing saved trip + detach action
        if (containingTrip != null) {
            val (_, trip) = containingTrip
            com.matelink.ui.components.PartOfTripCard(
                tripRoute = trip.displayName(),
                onNavigateToTrip = { onNavigateToTripDetail(trip.startDate) },
                onConfirmRemove = onRemoveFromTrip
            )
        }

        if (showEnergyDialog) {
            stats?.let { s ->
                val notAvailableLabel = stringResource(R.string.not_available)
                AlertDialog(
                    onDismissRequest = { showEnergyDialog = false },
                    title = { Text("电量与能耗计算依据", fontWeight = FontWeight.Bold) },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            val energySourceText = when {
                                s.energy.energyKwh == null -> notAvailableLabel
                                s.energy.source == DriveDetailEnergySource.API -> stringResource(R.string.energy_source_api_reported)
                                s.energy.source == DriveDetailEnergySource.POWER_SAMPLES -> {
                                    val coverage = if ((s.energy.coverageRatio ?: 0.0) > 0.0) " (${"%.0f".format(s.energy.coverageRatio!! * 100)}%)" else ""
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
                            val coverageText = if (s.energy.evidence?.coverageKind == "endpoints") stringResource(R.string.energy_endpoint_coverage)
                            else s.energy.coverageRatio?.let { ratio ->
                                val seconds = s.energy.coverageSeconds?.let { "$it s" } ?: notAvailableLabel
                                "%.0f%% · %s".format(ratio * 100, seconds)
                            } ?: notAvailableLabel

                            Text("• 能量来源: $energySourceText", style = MaterialTheme.typography.bodyMedium)
                            Text("• 采样覆盖: $coverageText", style = MaterialTheme.typography.bodyMedium)
                            s.batteryUsed?.let {
                                Text("• 电池差值: 已用 $it%", style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { showEnergyDialog = false }) {
                            Text(stringResource(R.string.close))
                        }
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun UnifiedTripCard(
    detail: DriveDetail,
    stats: DriveDetailStats?,
    units: Units?,
    onShowEnergyDialog: () -> Unit
) {
    val is24Hour = true
    val notAvailableLabel = stringResource(R.string.not_available)

    TelemetryPanel(
        modifier = Modifier.fillMaxWidth(),
        accent = MaterialTheme.colorScheme.primary
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
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = CustomIcons.SteeringWheel,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = stringResource(R.string.trip),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            RouteIndicator(
                start = recordedTripEndpoint(detail.startAddress),
                end = recordedTripEndpoint(detail.endAddress),
                accent = MaterialTheme.colorScheme.primary
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
                    value = detail.durationMin?.takeIf { it >= 0 }?.let { formatDuration(LocalContext.current.resources, it) }
                        ?: detail.durationStr?.takeIf { it.isNotBlank() }
                        ?: notAvailableLabel,
                    modifier = Modifier.weight(0.8f)
                )
            }

            if (stats != null) {
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    StatItemView(
                        label = stringResource(R.string.distance),
                        value = stats.distance?.let { visualTripNumber(it) } ?: notAvailableLabel,
                        unit = UnitFormatter.getDistanceUnit(units),
                        valueColor = Color(0xFF0284C7),
                        modifier = Modifier.weight(1f)
                    )
                    StatItemView(
                        label = stringResource(R.string.duration),
                        value = stats.durationMin?.let { "%,d".format(it) } ?: notAvailableLabel,
                        unit = stringResource(R.string.visual_trip_duration_unit),
                        valueColor = Color(0xFFD97706),
                        modifier = Modifier.weight(1f)
                    )
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.efficiency),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = "能耗说明",
                                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                                modifier = Modifier
                                    .size(13.dp)
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null
                                    ) { onShowEnergyDialog() }
                            )
                        }
                        Text(
                            text = stats.energy.efficiencyWhKm?.let { visualTripNumber(it / 10.0) } ?: notAvailableLabel,
                            style = MaterialTheme.typography.bodyLarge,
                            fontSize = 20.sp,
                            color = Color(0xFF10B981),
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "kWh/100km",
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
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
private fun DriveMapCard(positions: List<DrivePosition>, routeColor: Color, startDate: String? = null, endDate: String? = null) {
    val startLabel = stringResource(R.string.start)
    val endLabel = stringResource(R.string.end)
    val segments = remember(positions, startDate, endDate) {
        val observed = com.matelink.domain.map.observedDriveRouteSegments(positions, startDate, endDate)
        if (observed.any { it.size >= 2 }) observed else {
            val usable = usableDriveRoutePoints(positions)
            if (usable.size >= 2) listOf(usable) else observed
        }
    }
    val routePoints = segments.flatten()

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

            if (routePoints.distinct().size < 2) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.LocationOn, contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.drive_route_points_insufficient, routePoints.distinct().size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                Box(modifier = Modifier.fillMaxWidth().height(250.dp)
                    .clip(RoundedCornerShape(8.dp))) {
                    AmapRouteView(modifier = Modifier.fillMaxSize(), routePoints = routePoints, routeSegments = segments,
                        startTitle = startLabel, endTitle = endLabel, routeColor = routeColor, drawRoutePath = true)
                }
            }
        }
    }
}

/** A single or repeated GPS point is not a travelled A-to-B route. */
internal fun usableDriveRoutePoints(positions: List<DrivePosition>): List<Pair<Double, Double>> =
    buildList {
        positions.forEach { position ->
        val lat = position.latitude
        val lon = position.longitude
        if (lat != null && lon != null && AmapConfiguration.isUsableCoordinate(lat, lon)) {
            val point = lat to lon
            if (lastOrNull() != point) add(point)
        }
        }
    }

data class StatItem(
    val label: String,
    val value: String,
    val unit: String? = null,
    val recovered: Boolean = false,
    val valueColor: Color? = null
)

@Composable
private fun CurveStatsRow(stats: List<StatItem>) {
    val columns = if (LocalConfiguration.current.fontScale >= 1.5f) 1 else 3
    Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        stats.chunked(columns).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { stat ->
                    StatItemView(stat.label, stat.value, stat.unit, modifier = Modifier.weight(1f))
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
    valueColor: Color? = null,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            fontSize = if (unit != null) 20.sp else 16.sp,
            color = when {
                valueColor != null -> valueColor
                recovered -> Color(0xFF10B981)
                else -> MaterialTheme.colorScheme.onSurface
            },
            fontWeight = FontWeight.SemiBold
        )
        unit?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                softWrap = true
            )
        }
    }
}

private fun formatDateTime(dateStr: String?, is24Hour: Boolean? = true): String {
    return formatMonthDayTime(dateStr, is24Hour = is24Hour ?: true) ?: "—"
}
