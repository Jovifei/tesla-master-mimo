package com.matelink.ui.screens.tpms

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.matelink.R
import com.matelink.data.local.TirePosition
import com.matelink.domain.analytics.TpmsTrendFactor
import java.util.Locale

private val TpmsWheelColors = mapOf(
    TirePosition.FL to Color(0xFFE05A5A),
    TirePosition.FR to Color(0xFF4F86C6),
    TirePosition.RL to Color(0xFF4E9F6E),
    TirePosition.RR to Color(0xFFE09B45)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TpmsTrendScreen(
    carId: Int,
    exteriorColor: String?,
    onNavigateBack: () -> Unit,
    viewModel: TpmsTrendViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(carId) { viewModel.load(carId) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.tpms_trend_title)) },
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
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                Text(stringResource(R.string.loading))
            }
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
            WindowSelector(
                selected = state.selectedWindow,
                onSelect = viewModel::selectWindow
            )

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
                            text = stringResource(R.string.tpms_trend_load_failed),
                            style = MaterialTheme.typography.bodyLarge
                        )
                        androidx.compose.material3.TextButton(onClick = viewModel::retry) {
                            Text(stringResource(R.string.metric_state_retry))
                        }
                    }
                }
            } else if (state.isUnavailable) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                    )
                ) {
                    Text(
                        text = stringResource(R.string.tpms_trend_unavailable),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(20.dp)
                    )
                }
            } else {
                TpmsTrendChart(state.series)
                TpmsLegend()
                TpmsSummary(state)
            }

            TpmsEvidence(state)
        }
    }
}

@Composable
private fun WindowSelector(
    selected: TpmsTrendWindow,
    onSelect: (TpmsTrendWindow) -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TpmsTrendWindow.entries.forEach { window ->
            FilterChip(
                selected = selected == window,
                onClick = { onSelect(window) },
                label = {
                    Text(
                        stringResource(
                            if (window == TpmsTrendWindow.SEVEN) {
                                R.string.tpms_trend_window_7
                            } else {
                                R.string.tpms_trend_window_30
                            }
                        )
                    )
                }
            )
        }
    }
}

@Composable
private fun TpmsTrendChart(series: Map<TirePosition, List<com.matelink.domain.analytics.TpmsPressurePoint>>) {
    val validPoints = series.values.flatten().mapNotNull { it.pressureBar }
    if (validPoints.isEmpty()) return
    val minValue = validPoints.minOrNull() ?: return
    val maxValue = validPoints.maxOrNull() ?: return
    val range = (maxValue - minValue).takeIf { it > 0.0 } ?: 1.0

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = stringResource(R.string.tpms_trend_chart_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
            ) {
                val left = 8.dp.toPx()
                val right = size.width - 8.dp.toPx()
                val top = 12.dp.toPx()
                val bottom = size.height - 12.dp.toPx()
                val width = (right - left).coerceAtLeast(1f)
                val height = (bottom - top).coerceAtLeast(1f)

                repeat(4) { index ->
                    val y = top + height * index / 3f
                    drawLine(
                        color = Color.LightGray.copy(alpha = 0.45f),
                        start = Offset(left, y),
                        end = Offset(right, y),
                        strokeWidth = 1.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 5.dp.toPx()))
                    )
                }

                series.forEach { (wheel, points) ->
                    val color = TpmsWheelColors.getValue(wheel)
                    nullableSegments(points).forEach { segment ->
                        val firstTime = points.firstOrNull()?.observedAt ?: return@forEach
                        val lastTime = points.lastOrNull()?.observedAt ?: firstTime
                        val timeRange = (lastTime - firstTime).toFloat().takeIf { it > 0f } ?: 1f
                        segment.zipWithNext().forEach { (first, last) ->
                            val x1 = left + width * ((first.observedAt - firstTime) / timeRange)
                            val x2 = left + width * ((last.observedAt - firstTime) / timeRange)
                            val y1 = bottom - height * ((first.pressureBar!! - minValue) / range).toFloat()
                            val y2 = bottom - height * ((last.pressureBar!! - minValue) / range).toFloat()
                            drawLine(
                                color = color,
                                start = Offset(x1, y1),
                                end = Offset(x2, y2),
                                strokeWidth = 2.5.dp.toPx(),
                                cap = StrokeCap.Round
                            )
                        }
                        if (segment.size == 1) {
                            val point = segment.single()
                            val x = left + width * ((point.observedAt - firstTime) / timeRange)
                            val y = bottom - height * ((point.pressureBar!! - minValue) / range).toFloat()
                            drawCircle(color, radius = 3.dp.toPx(), center = Offset(x, y))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TpmsLegend() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        TirePosition.entries.forEach { wheel ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Canvas(modifier = Modifier.width(10.dp).height(10.dp)) {
                    drawCircle(TpmsWheelColors.getValue(wheel))
                }
                Spacer(modifier = Modifier.width(4.dp))
                Text(wheelLabel(wheel), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun TpmsSummary(state: TpmsTrendUiState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = stringResource(R.string.tpms_trend_coverage, state.coverage.sampleCount),
                style = MaterialTheme.typography.bodyMedium
            )
            TirePosition.entries.forEach { wheel ->
                val latest = state.latestPressure(wheel)
                val delta = state.deltas[wheel]
                Text(
                    text = stringResource(
                        R.string.tpms_trend_wheel_summary,
                        wheelLabel(wheel),
                        latest?.let(::formatBar) ?: stringResource(R.string.tpms_trend_unavailable_short),
                        delta?.let(::formatSignedBar) ?: stringResource(R.string.tpms_trend_unavailable_short)
                    ),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun TpmsEvidence(state: TpmsTrendUiState) {
    val renderedConclusion = renderPossibleFactorConclusion(
        conclusion = state.possibleFactorConclusion,
        localized = TpmsTrendLocalizedText(
            ambient = stringResource(R.string.tpms_trend_conclusion_ambient),
            highway = stringResource(R.string.tpms_trend_conclusion_highway),
            parking = stringResource(R.string.tpms_trend_conclusion_parking),
            insufficient = stringResource(R.string.tpms_trend_conclusion_insufficient),
            parkingRecommendation = stringResource(R.string.tpms_trend_recommendation_manual_cold_check)
        )
    )
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(R.string.tpms_trend_possible_factors),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = renderedConclusion.conclusion,
                style = MaterialTheme.typography.bodyMedium
            )
            state.possibleFactors
                .filter { it.factor != state.possibleFactorConclusion.factor }
                .forEach { evidence ->
                Text(
                    text = "• ${factorLabel(evidence.factor)}",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            renderedConclusion.recommendation?.let { recommendation ->
                Text(
                    text = recommendation,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = stringResource(R.string.tpms_trend_evidence_disclaimer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun factorLabel(factor: TpmsTrendFactor): String = stringResource(
    when (factor) {
        TpmsTrendFactor.AMBIENT -> R.string.tpms_trend_factor_ambient
        TpmsTrendFactor.HIGHWAY -> R.string.tpms_trend_factor_highway
        TpmsTrendFactor.PARKING -> R.string.tpms_trend_factor_parking
        TpmsTrendFactor.INSUFFICIENT_EVIDENCE -> R.string.tpms_trend_factor_insufficient
    }
)

@Composable
private fun wheelLabel(wheel: TirePosition): String = stringResource(
    when (wheel) {
        TirePosition.FL -> R.string.tire_fl_full
        TirePosition.FR -> R.string.tire_fr_full
        TirePosition.RL -> R.string.tire_rl_full
        TirePosition.RR -> R.string.tire_rr_full
    }
)

private fun formatBar(value: Double): String =
    String.format(Locale.US, "%.2f bar", value)

private fun formatSignedBar(value: Double): String =
    String.format(Locale.US, "%+.2f bar", value)
