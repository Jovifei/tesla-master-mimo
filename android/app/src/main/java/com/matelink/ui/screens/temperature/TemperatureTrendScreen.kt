package com.matelink.ui.screens.temperature

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.matelink.R
import com.matelink.ui.components.MateLinkLoadingPlaceholder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private val ColorCabin = Color(0xFF00897B)   // Teal for Cabin (Inside)
private val ColorOutside = Color(0xFF1E88E5) // Blue for Outside

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TemperatureTrendScreen(
    carId: Int,
    exteriorColor: String?,
    onNavigateBack: () -> Unit,
    viewModel: TemperatureTrendViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(carId) { viewModel.load(carId) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.temperature_trend_title)) },
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
    ) { paddingValues ->
        if (state.isLoading) {
            MateLinkLoadingPlaceholder(modifier = Modifier.padding(paddingValues))
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(paddingValues)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Window selector chips
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TemperatureTrendWindow.entries.forEach { window ->
                    FilterChip(
                        selected = state.selectedWindow == window,
                        onClick = { viewModel.selectWindow(window) },
                        label = {
                            Text(
                                stringResource(
                                    if (window == TemperatureTrendWindow.SEVEN) {
                                        R.string.temperature_trend_window_7
                                    } else {
                                        R.string.temperature_trend_window_30
                                    }
                                )
                            )
                        }
                    )
                }
            }

            if (state.error != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.temperature_trend_load_failed),
                            style = MaterialTheme.typography.bodyLarge
                        )
                        androidx.compose.material3.TextButton(onClick = viewModel::retry) {
                            Text(stringResource(R.string.metric_state_retry))
                        }
                    }
                }
            } else {
                Text(
                    text = stringResource(if (state.currentStatusAvailable) {
                        R.string.temperature_current_status_note
                    } else {
                        R.string.temperature_current_unavailable
                    }),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (state.currentStatusAvailable) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        TemperatureMetricCard(
                            title = stringResource(R.string.temperature_current_inside),
                            value = state.currentInsideTemp?.let { "${it}°C" } ?: "--",
                            color = ColorCabin,
                            modifier = Modifier.weight(1f)
                        )
                        TemperatureMetricCard(
                            title = stringResource(R.string.temperature_current_outside),
                            value = state.currentOutsideTemp?.let { "${it}°C" } ?: "--",
                            color = ColorOutside,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                Text(
                    text = stringResource(R.string.temperature_history_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = stringResource(R.string.temperature_observed_coverage,
                        state.historyQualifiedCount, state.historyDriveCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                state.latestHistoryObservedAtMs?.let { at ->
                    val localTime = java.time.Instant.ofEpochMilli(at)
                        .atZone(java.time.ZoneId.systemDefault())
                        .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
                    Text(
                        text = stringResource(R.string.temperature_last_qualified_trip, localTime),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (state.isUnavailable) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                        )
                    ) {
                        Text(
                            text = stringResource(if (state.historyUnavailableReason == "no_history_in_window") {
                                R.string.temperature_no_trip_history
                            } else {
                                R.string.temperature_no_qualified_trip_samples
                            }),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(20.dp)
                        )
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        TemperatureMetricCard(
                            title = stringResource(R.string.temperature_max_inside),
                            value = state.maxInsideTemp?.let { "${it}°C" } ?: "--",
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        TemperatureMetricCard(
                            title = stringResource(R.string.temperature_max_outside),
                            value = state.maxOutsideTemp?.let { "${it}°C" } ?: "--",
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    TemperatureTrendChartCard(state.points)
                }
            }
        }
    }
}

@Composable
private fun TemperatureMetricCard(
    title: String,
    value: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = color
            )
        }
    }
}

@Composable
private fun TemperatureTrendChartCard(points: List<TemperaturePoint>) {
    val insideValues = points.mapNotNull { it.insideTemp }
    val outsideValues = points.mapNotNull { it.outsideTemp }
    val allValues = insideValues + outsideValues
    if (allValues.isEmpty()) return

    val minVal = allValues.minOrNull() ?: 15.0
    val maxVal = allValues.maxOrNull() ?: 35.0
    val yMin = kotlin.math.floor(minVal - 3.0).coerceAtLeast(-20.0)
    val yMax = kotlin.math.ceil(maxVal + 3.0)
    val yRange = (yMax - yMin).takeIf { it > 0.0 } ?: 10.0

    val firstT = points.firstOrNull()?.timestampMs ?: 0L
    val lastT = points.lastOrNull()?.timestampMs ?: firstT
    val timeRange = (lastT - firstT).toFloat().takeIf { it > 0f } ?: 1f

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.temperature_trend_chart_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                // Top right legend
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    LegendItem(color = ColorCabin, label = stringResource(R.string.temperature_inside_legend))
                    LegendItem(color = ColorOutside, label = stringResource(R.string.temperature_outside_legend))
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
            ) {
                val left = 32.dp.toPx()
                val right = size.width - 12.dp.toPx()
                val top = 16.dp.toPx()
                val bottom = size.height - 28.dp.toPx()
                val width = (right - left).coerceAtLeast(1f)
                val height = (bottom - top).coerceAtLeast(1f)
                fun xAt(timestampMs: Long): Float = if (firstT == lastT) {
                    left + width / 2f
                } else {
                    left + width * ((timestampMs - firstT) / timeRange)
                }

                // Draw Y-axis grid lines and labels
                val gridSteps = 4
                val textPaint = android.graphics.Paint().apply {
                    color = android.graphics.Color.GRAY
                    textSize = 10.sp.toPx()
                    isAntiAlias = true
                }

                for (i in 0..gridSteps) {
                    val y = top + height * (1f - i.toFloat() / gridSteps.toFloat())
                    val tempVal = (yMin + (yRange * i.toDouble() / gridSteps.toDouble())).roundToInt()

                    drawLine(
                        color = Color.LightGray.copy(alpha = 0.35f),
                        start = Offset(left, y),
                        end = Offset(right, y),
                        strokeWidth = 1.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))
                    )

                    drawContext.canvas.nativeCanvas.drawText(
                        "${tempVal}°",
                        4.dp.toPx(),
                        y + 4.dp.toPx(),
                        textPaint
                    )
                }

                // Each dot is an observed drive average. Do not interpolate unobserved hours.
                val insidePoints = points.filter { it.insideTemp != null }
                insidePoints.forEach { p ->
                    val x = xAt(p.timestampMs)
                    val y = bottom - height * ((p.insideTemp!! - yMin) / yRange).toFloat()
                    drawCircle(color = ColorCabin, radius = 3.5.dp.toPx(), center = Offset(x, y))
                }

                val outsidePoints = points.filter { it.outsideTemp != null }
                outsidePoints.forEach { p ->
                    val x = xAt(p.timestampMs)
                    val y = bottom - height * ((p.outsideTemp!! - yMin) / yRange).toFloat()
                    drawCircle(color = ColorOutside, radius = 3.5.dp.toPx(), center = Offset(x, y))
                }

                // Draw X-axis date labels
                val dateCount = if (firstT == lastT) 1 else 4
                val dateFormat = SimpleDateFormat("MM/dd", Locale.getDefault())
                val dateStepMs = (lastT - firstT) / (dateCount - 1).coerceAtLeast(1)

                for (i in 0 until dateCount) {
                    val t = firstT + i * dateStepMs
                    val x = xAt(t)
                    val label = dateFormat.format(Date(t))
                    drawContext.canvas.nativeCanvas.drawText(
                        label,
                        x - 14.dp.toPx(),
                        size.height - 4.dp.toPx(),
                        textPaint
                    )
                }
            }
        }
    }
}

@Composable
private fun LegendItem(color: Color, label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Canvas(modifier = Modifier.size(8.dp)) {
            drawCircle(color)
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium
        )
    }
}
