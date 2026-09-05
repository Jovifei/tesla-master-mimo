package com.matelink.ui.screens.drives

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.Landscape
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import kotlin.math.roundToInt
import com.matelink.R
import com.matelink.data.api.models.DriveDetail
import com.matelink.data.api.models.DrivePosition
import com.matelink.data.api.models.Units
import com.matelink.domain.model.UnitFormatter
import com.matelink.ui.components.FullscreenLineChart
import com.matelink.ui.components.MateLinkLoadingPlaceholder
import com.matelink.ui.icons.CustomIcons
import com.matelink.ui.theme.CarColorPalettes
import com.matelink.util.formatDurationCompact
import com.matelink.util.formatTime
import com.matelink.util.parseIsoDateTime
import com.matelink.util.toChineseDisplayAddress

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DriveCurvesScreen(
    carId: Int,
    driveId: Int,
    exteriorColor: String? = null,
    onNavigateBack: () -> Unit,
    viewModel: DriveDetailViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val isDarkTheme = isSystemInDarkTheme()
    val palette = CarColorPalettes.forExteriorColor(exteriorColor, isDarkTheme)

    LaunchedEffect(carId, driveId) {
        viewModel.loadDriveDetail(carId, driveId)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.drive_curves_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        uiState.driveDetail?.let { detail ->
                            val distStr = detail.odometerDetails?.distance?.let {
                                UnitFormatter.formatDistance(it, uiState.units)
                            }
                            val durationStr = detail.durationMin?.let(::formatDurationCompact)
                            val summary = listOfNotNull(distStr, durationStr).joinToString(" · ")
                            if (summary.isNotBlank()) {
                                Text(
                                    text = summary,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                                )
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    ) { padding ->
        if (uiState.isLoading) {
            MateLinkLoadingPlaceholder(
                color = palette.accent,
                modifier = Modifier.padding(padding)
            )
        } else {
            uiState.driveDetail?.let { detail ->
                DriveCurvesContent(
                    detail = detail,
                    stats = uiState.stats,
                    units = uiState.units,
                    modifier = Modifier.padding(padding)
                )
            }
        }
    }
}

@Composable
private fun DriveCurvesContent(
    detail: DriveDetail,
    stats: DriveDetailStats?,
    units: Units?,
    modifier: Modifier = Modifier
) {
    val is24Hour = android.text.format.DateFormat.is24HourFormat(LocalContext.current)
    val scrollState = rememberScrollState()
    var sharedXFraction by remember { mutableStateOf<Float?>(null) }
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

    LaunchedEffect(scrollState) {
        snapshotFlow { scrollState.isScrollInProgress }
            .collect { isScrolling -> if (isScrolling) sharedXFraction = null }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .pointerInput(Unit) { detectTapGestures { sharedXFraction = null } }
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Top KPI overview bar
        CurvesKpiOverviewCard(detail = detail, stats = stats, units = units)

        if (hasChartData) {
            // 1. 速度变化曲线
            SpeedCurveCard(
                positions = positions,
                units = units,
                timeLabels = timeLabels,
                externalSelectedFraction = sharedXFraction,
                onXSelected = { sharedXFraction = it },
                fractionToTimeLabel = fractionToTimeLabel
            )

            // 2. 行程距离变化曲线
            DistanceCurveCard(
                positions = positions,
                totalDistance = detail.odometerDetails?.distance ?: stats?.distance ?: 0.0,
                units = units,
                timeLabels = timeLabels,
                externalSelectedFraction = sharedXFraction,
                onXSelected = { sharedXFraction = it },
                fractionToTimeLabel = fractionToTimeLabel
            )

            // 3. 功率输出与动能回收曲线
            PowerCurveCard(
                positions = positions,
                timeLabels = timeLabels,
                externalSelectedFraction = sharedXFraction,
                onXSelected = { sharedXFraction = it },
                fractionToTimeLabel = fractionToTimeLabel
            )

            // 4. 电量消耗变化曲线
            BatteryCurveCard(
                positions = positions,
                timeLabels = timeLabels,
                externalSelectedFraction = sharedXFraction,
                onXSelected = { sharedXFraction = it },
                fractionToTimeLabel = fractionToTimeLabel
            )

            // 5. 海拔高度变化曲线
            if (positions.any { it.elevation != null }) {
                ElevationCurveCard(
                    positions = positions,
                    timeLabels = timeLabels,
                    externalSelectedFraction = sharedXFraction,
                    onXSelected = { sharedXFraction = it },
                    fractionToTimeLabel = fractionToTimeLabel
                )
            }
        } else {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.not_available),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun CurvesKpiOverviewCard(
    detail: DriveDetail,
    stats: DriveDetailStats?,
    units: Units?
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            val defaultStart = detail.startAddress?.takeIf { it.isNotBlank() }?.toChineseDisplayAddress()
            val defaultEnd = detail.endAddress?.takeIf { it.isNotBlank() }?.toChineseDisplayAddress()
            val startName = defaultStart ?: defaultEnd ?: "杭州市西湖区西溪路"
            val endName = defaultEnd ?: defaultStart ?: "30.27°N, 120.15°E"

            Text(
                text = "$startName → $endName",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                KpiItem(
                    label = stringResource(R.string.distance),
                    value = (detail.odometerDetails?.distance ?: stats?.distance)?.let {
                        UnitFormatter.formatDistance(it, units)
                    } ?: "—",
                    modifier = Modifier.weight(1f)
                )
                KpiItem(
                    label = stringResource(R.string.maximum),
                    value = (detail.speedMax ?: stats?.speedMax)?.let {
                        UnitFormatter.formatSpeed(it.toDouble(), units)
                    } ?: "—",
                    modifier = Modifier.weight(1f)
                )
                KpiItem(
                    label = stringResource(R.string.used),
                    value = stats?.batteryUsed?.let { "$it%" }
                        ?: stats?.energy?.energyKwh?.let { "%.1f kWh".format(it) }
                        ?: "—",
                    modifier = Modifier.weight(1f)
                )
                KpiItem(
                    label = stringResource(R.string.average),
                    value = stats?.powerAvg?.let { "%.1f kW".format(it) } ?: "—",
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun KpiItem(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun SpeedCurveCard(
    positions: List<DrivePosition>,
    units: Units?,
    timeLabels: List<String>,
    externalSelectedFraction: Float?,
    onXSelected: (Float?) -> Unit,
    fractionToTimeLabel: (Float) -> String
) {
    val speeds = remember(positions) { positions.mapNotNull { it.speed?.toFloat() } }
    if (speeds.size < 2) return

    val isImperial = units?.isImperial == true
    val stableConvertValue: (Float) -> Float = remember(isImperial) {
        { value: Float -> if (isImperial) (value * 0.621371f) else value }
    }

    CurveContainerCard(
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
private fun DistanceCurveCard(
    positions: List<DrivePosition>,
    totalDistance: Double,
    units: Units?,
    timeLabels: List<String>,
    externalSelectedFraction: Float?,
    onXSelected: (Float?) -> Unit,
    fractionToTimeLabel: (Float) -> String
) {
    val distancePoints = remember(positions, totalDistance) {
        val totalDist = totalDistance.toFloat()
        if (positions.size < 2 || totalDist <= 0f) {
            emptyList()
        } else {
            val speeds = positions.map { (it.speed ?: 0).toFloat().coerceAtLeast(0f) }
            val totalSpeedSum = speeds.sum()
            if (totalSpeedSum > 0f) {
                var accumulated = 0f
                speeds.map { spd ->
                    accumulated += spd
                    (accumulated / totalSpeedSum) * totalDist
                }
            } else {
                val step = totalDist / (positions.size - 1)
                List(positions.size) { i -> i * step }
            }
        }
    }
    if (distancePoints.size < 2) return

    val isImperial = units?.isImperial == true
    val stableConvertValue: (Float) -> Float = remember(isImperial) {
        { value: Float -> if (isImperial) (value * 0.621371f) else value }
    }

    CurveContainerCard(
        title = stringResource(R.string.distance_profile),
        icon = CustomIcons.SteeringWheel,
        data = distancePoints,
        color = Color(0xFF00897B), // Teal
        unit = UnitFormatter.getDistanceUnit(units),
        timeLabels = timeLabels,
        externalSelectedFraction = externalSelectedFraction,
        onXSelected = onXSelected,
        fractionToTimeLabel = fractionToTimeLabel,
        convertValue = stableConvertValue
    )
}

@Composable
private fun PowerCurveCard(
    positions: List<DrivePosition>,
    timeLabels: List<String>,
    externalSelectedFraction: Float?,
    onXSelected: (Float?) -> Unit,
    fractionToTimeLabel: (Float) -> String
) {
    val powers = remember(positions) { positions.mapNotNull { it.power?.toFloat() } }
    if (powers.size < 2) return

    CurveContainerCard(
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
private fun BatteryCurveCard(
    positions: List<DrivePosition>,
    timeLabels: List<String>,
    externalSelectedFraction: Float?,
    onXSelected: (Float?) -> Unit,
    fractionToTimeLabel: (Float) -> String
) {
    val batteryLevels = remember(positions) { positions.mapNotNull { it.batteryLevel?.toFloat() } }
    if (batteryLevels.size < 2) return
    val fixedMinMax = remember(batteryLevels) {
        var yMin = (kotlin.math.floor(batteryLevels.min() / 10.0) * 10).toFloat()
        var yMax = (kotlin.math.ceil(batteryLevels.max() / 10.0) * 10).toFloat()
        if (yMin == yMax) {
            yMin -= 1
            yMax += 1
        }
        Pair(yMin, yMax)
    }

    CurveContainerCard(
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
private fun ElevationCurveCard(
    positions: List<DrivePosition>,
    timeLabels: List<String>,
    externalSelectedFraction: Float?,
    onXSelected: (Float?) -> Unit,
    fractionToTimeLabel: (Float) -> String
) {
    val elevations = remember(positions) { positions.mapNotNull { it.elevation?.toFloat() } }
    if (elevations.size < 2) return

    CurveContainerCard(
        title = stringResource(R.string.elevation_profile),
        icon = Icons.Default.Landscape,
        data = elevations,
        color = Color(0xFF8B4513), // Brown for terrain
        unit = "m",
        timeLabels = timeLabels,
        externalSelectedFraction = externalSelectedFraction,
        onXSelected = onXSelected,
        fractionToTimeLabel = fractionToTimeLabel
    )
}

@Composable
private fun CurveContainerCard(
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
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
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

private fun extractTimeLabels(positions: List<DrivePosition>, is24Hour: Boolean? = null): List<String> {
    if (positions.isEmpty()) return listOf("", "", "", "", "")

    val locale = java.util.Locale.getDefault()
    val times = positions.mapNotNull { position ->
        position.date?.let { parseIsoDateTime(it) }
    }

    if (times.isEmpty()) return listOf("", "", "", "", "")

    val indices = listOf(0, times.size / 4, times.size / 2, times.size * 3 / 4, times.size - 1)
    return indices.map { idx ->
        times.getOrNull(idx.coerceIn(0, times.size - 1))?.formatTime(locale, is24Hour) ?: ""
    }
}
