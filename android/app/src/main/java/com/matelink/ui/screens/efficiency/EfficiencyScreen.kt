package com.matelink.ui.screens.efficiency

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.matelink.R
import com.matelink.data.repository.HISTORY_IDENTITY_UNAVAILABLE
import com.matelink.domain.analytics.HistoryFreshness
import com.matelink.domain.analytics.AnalysisWindow
import com.matelink.ui.components.AnalysisWindowSelector
import com.matelink.ui.components.CachedHistoryBanner
import com.matelink.ui.components.MetricPanelKind
import com.matelink.ui.components.MetricStatusPanel
import com.matelink.ui.components.HistoryStatusPanel
import com.matelink.ui.theme.SwissOutline
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EfficiencyScreen(
    carId: Int,
    onNavigateBack: () -> Unit = {},
    viewModel: EfficiencyViewModel = hiltViewModel()
) {
    LaunchedEffect(carId) {
        viewModel.load(carId)
    }

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.efficiency_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        if (uiState.isLoading) {
            Box(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                MetricStatusPanel(
                    kind = MetricPanelKind.LOADING,
                    title = stringResource(R.string.metric_state_loading_title),
                    body = stringResource(R.string.metric_state_loading_body)
                )
            }
            return@Scaffold
        }

        if (uiState.error != null) {
            Box(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                MetricStatusPanel(
                    kind = MetricPanelKind.ERROR,
                    title = stringResource(R.string.metric_state_error_title),
                    body = if (uiState.error == HISTORY_IDENTITY_UNAVAILABLE) {
                        stringResource(R.string.history_identity_unavailable_message)
                    } else {
                        uiState.error ?: stringResource(R.string.no_data)
                    }
                )
            }
            return@Scaffold
        }

        if (uiState.driveCount == 0) {
            Box(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                HistoryStatusPanel(
                    reason = uiState.noDataReason,
                    emptyBody = stringResource(R.string.metric_state_empty_body)
                )
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (uiState.historyFreshness == HistoryFreshness.STALE) {
                CachedHistoryBanner(
                    title = stringResource(R.string.metric_state_cached_title),
                    body = stringResource(R.string.metric_state_cached_body)
                )
            }

            // Summary card - Average Efficiency
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.efficiency_avg_label),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = uiState.avgEfficiencyWhKm?.let { String.format(Locale.getDefault(), "%.1f Wh/km", it) }
                            ?: stringResource(R.string.analysis_no_records),
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            // Stats row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                StatCard(
                    modifier = Modifier.weight(1f),
                    label = stringResource(R.string.efficiency_drive_count),
                    value = if (uiState.driveCount > 0) uiState.driveCount.toString() else stringResource(R.string.analysis_no_records)
                )
                StatCard(
                    modifier = Modifier.weight(1f),
                    label = stringResource(R.string.efficiency_total_distance),
                    value = if (uiState.driveCount > 0) String.format(Locale.getDefault(), "%.1f km", uiState.totalDistanceKm)
                    else stringResource(R.string.analysis_no_records)
                )
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.efficiency_trend_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    if (uiState.efficiencyTrend.isEmpty()) {
                        Text(
                            text = stringResource(R.string.efficiency_trend_empty),
                            modifier = Modifier.padding(top = 8.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        EfficiencyTrendChart(uiState.efficiencyTrend)
                    }
                }
            }

            AnalysisWindowSelector(
                selected = uiState.selectedWindow,
                onSelected = viewModel::selectWindow,
                onCustomSelected = viewModel::selectCustomRange,
                modifier = Modifier.fillMaxWidth()
            )

            Text(
                text = stringResource(R.string.efficiency_windows_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                EfficiencyWindowCard(
                    modifier = Modifier.weight(1f),
                    label = stringResource(R.string.efficiency_last_90_days),
                    value = uiState.last90DaysEfficiencyWhKm
                )
                EfficiencyWindowCard(
                    modifier = Modifier.weight(1f),
                    label = stringResource(R.string.efficiency_summer),
                    value = uiState.summerEfficiencyWhKm
                )
                EfficiencyWindowCard(
                    modifier = Modifier.weight(1f),
                    label = stringResource(R.string.efficiency_winter),
                    value = uiState.winterEfficiencyWhKm
                )
            }

            uiState.personalPercentile?.let { position ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = stringResource(R.string.efficiency_personal_position),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = stringResource(R.string.efficiency_percentile_summary, position.percentile, position.sampleCount),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = stringResource(R.string.efficiency_percentile_bounds, position.min, position.median, position.max),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        PercentileRangeBar(position = position)
                        val bucketCounts = uiState.tripPositions.groupingBy {
                            when {
                                it.percentile < 25 -> 0
                                it.percentile < 50 -> 1
                                it.percentile < 75 -> 2
                                else -> 3
                            }
                        }.eachCount()
                        Text(
                            text = stringResource(
                                R.string.efficiency_percentile_buckets,
                                bucketCounts[0] ?: 0,
                                bucketCounts[1] ?: 0,
                                bucketCounts[2] ?: 0,
                                bucketCounts[3] ?: 0
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.efficiency_public_benchmark_unavailable),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (uiState.tripPositions.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.efficiency_personal_history_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                uiState.tripPositions.take(20).forEachIndexed { index, trip ->
                    EfficiencyTripRow(index + 1, trip)
                }
            }

            // Efficiency by Speed Chart
            if (uiState.efficiencyBySpeed.isNotEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = stringResource(R.string.efficiency_by_speed_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        // Bar chart
                        EfficiencyBarChart(
                            data = uiState.efficiencyBySpeed,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(200.dp)
                        )

                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.efficiency_by_speed_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatCard(
    modifier: Modifier = Modifier,
    label: String,
    value: String
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun EfficiencyWindowCard(
    modifier: Modifier,
    label: String,
    value: Double?
) {
    StatCard(
        modifier = modifier,
        label = label,
        value = value?.let { String.format(Locale.getDefault(), "%.0f Wh/km", it) } ?: stringResource(R.string.analysis_no_records)
    )
}

@Composable
private fun PercentileRangeBar(position: com.matelink.domain.analytics.PercentilePosition) {
    val trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
    val markerColor = MaterialTheme.colorScheme.primary
    Column(modifier = Modifier.padding(top = 8.dp)) {
        Canvas(modifier = Modifier.fillMaxWidth().height(22.dp)) {
            val y = size.height / 2f
            drawLine(
                color = trackColor,
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = 10.dp.toPx()
            )
            val x = size.width * (position.percentile / 100f)
            drawCircle(markerColor, radius = 8.dp.toPx(), center = Offset(x, y))
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("0%", style = MaterialTheme.typography.labelSmall)
            Text("P${position.percentile}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            Text("100%", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun EfficiencyTripRow(index: Int, trip: EfficiencyTripPosition) {
    var expanded by remember(trip.driveId) { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = stringResource(R.string.efficiency_trip_position, index, trip.percentile, trip.efficiencyWhKm),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
            if (expanded) {
                val start = trip.startAddress ?: stringResource(R.string.unknown_location)
                val end = trip.endAddress ?: stringResource(R.string.unknown_location)
                Text(
                    text = stringResource(R.string.efficiency_trip_details, start, end),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = trip.date?.toString() ?: stringResource(R.string.analysis_coverage_insufficient),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun EfficiencyTrendChart(points: List<EfficiencyTrendPoint>) {
    val lineColor = MaterialTheme.colorScheme.primary
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val maxValue = points.maxOfOrNull { it.averageWhKm } ?: return
    val minValue = points.minOfOrNull { it.averageWhKm } ?: return
    val range = (maxValue - minValue).takeIf { it > 0.0 } ?: 1.0
    Canvas(modifier = Modifier.fillMaxWidth().height(120.dp).padding(top = 8.dp)) {
        val step = size.width / (points.size - 1).coerceAtLeast(1)
        val path = androidx.compose.ui.graphics.Path()
        points.forEachIndexed { index, point ->
            val x = index * step
            val y = size.height - ((point.averageWhKm - minValue) / range).toFloat() * size.height
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            drawCircle(lineColor, 5.dp.toPx(), Offset(x, y))
        }
        drawPath(path, lineColor, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3.dp.toPx()))
    }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        points.forEach { point ->
            Text(
                text = point.month.takeLast(5),
                style = MaterialTheme.typography.labelSmall,
                color = labelColor
            )
        }
    }
}

@Composable
private fun EfficiencyBarChart(
    data: List<Pair<String, Double>>,
    modifier: Modifier = Modifier
) {
    val maxEfficiency = data.maxOfOrNull { it.second } ?: 1.0
    val barColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.outlineVariant

    Canvas(modifier = modifier) {
        val barCount = data.size
        if (barCount == 0) return@Canvas

        val barWidth = (size.width / barCount) * 0.6f
        val barSpacing = (size.width / barCount) * 0.4f
        val chartHeight = size.height - 24.dp.toPx() // Leave room for labels
        val maxValue = maxEfficiency * 1.1 // 10% headroom

        // Draw grid lines
        for (i in 0..4) {
            val y = chartHeight * (1f - i / 4f)
            drawLine(
                color = gridColor,
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = 0.5.dp.toPx()
            )
        }

        // Draw bars
        data.forEachIndexed { index, (speedLabel, efficiency) ->
            val barHeight = (efficiency / maxValue * chartHeight).toFloat()
            val x = index * (barWidth + barSpacing) + barSpacing / 2
            val y = chartHeight - barHeight

            drawRect(
                color = barColor,
                topLeft = Offset(x, y),
                size = Size(barWidth, barHeight)
            )
        }

        // Draw speed labels below bars (native canvas text drawing omitted for compatibility)
    }
}
