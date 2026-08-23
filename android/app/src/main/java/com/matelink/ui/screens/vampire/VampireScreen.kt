package com.matelink.ui.screens.vampire

import androidx.compose.foundation.Canvas
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
import com.matelink.domain.analytics.HistoryFreshness
import com.matelink.ui.components.AnalysisWindowSelector
import com.matelink.ui.components.CachedHistoryBanner
import com.matelink.ui.components.MetricPanelKind
import com.matelink.ui.components.MetricStatusPanel
import com.matelink.ui.components.HistoryStatusPanel
import com.matelink.ui.theme.SwissOutline
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VampireScreen(
    onNavigateBack: () -> Unit = {},
    viewModel: VampireViewModel = hiltViewModel()
) {
    LaunchedEffect(Unit) {
        viewModel.load()
    }

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.vampire_title)) },
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
                    body = uiState.error ?: stringResource(R.string.no_data)
                )
            }
            return@Scaffold
        }

        if (uiState.idlePeriods.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                HistoryStatusPanel(
                    reason = uiState.noDataReason,
                    emptyBody = stringResource(R.string.vampire_no_data)
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
            AnalysisWindowSelector(
                selected = uiState.selectedWindow,
                onSelected = viewModel::selectWindow,
                onCustomSelected = viewModel::selectCustomRange,
                modifier = Modifier.fillMaxWidth()
            )

            if (uiState.historyFreshness == HistoryFreshness.STALE) {
                CachedHistoryBanner(
                    title = stringResource(R.string.metric_state_cached_title),
                    body = stringResource(R.string.metric_state_cached_body)
                )
            }

            if (uiState.idlePeriods.any { it.energyKwh == null }) {
                MetricStatusPanel(
                    kind = MetricPanelKind.UNAVAILABLE,
                    title = stringResource(R.string.vampire_soc_only_title),
                    body = stringResource(R.string.vampire_soc_only_body)
                )
            }

            // Summary card - Total drain
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, SwissOutline),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.vampire_total_drain_label),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = if (uiState.idlePeriods.isNotEmpty()) "${uiState.totalDrainPercent}%"
                        else stringResource(R.string.analysis_no_records),
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.vampire_total_drain_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Stats row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                VampireStatCard(
                    modifier = Modifier.weight(1f),
                    label = stringResource(R.string.vampire_avg_power),
                    value = uiState.avgPowerW?.let { String.format(Locale.getDefault(), "%.1f W", it) }
                        ?: stringResource(R.string.unknown)
                )
                VampireStatCard(
                    modifier = Modifier.weight(1f),
                    label = stringResource(R.string.vampire_idle_periods),
                    value = if (uiState.idlePeriods.isNotEmpty()) uiState.idlePeriods.size.toString()
                    else stringResource(R.string.analysis_no_records)
                )
                VampireStatCard(
                    modifier = Modifier.weight(1f),
                    label = stringResource(R.string.vampire_total_kwh),
                    value = uiState.totalDrainKwh?.let { String.format(Locale.getDefault(), "%.2f kWh", it) }
                        ?: stringResource(R.string.unknown)
                )
            }

            // Daily drain chart
            if (uiState.dailyDrains.any { it.totalDrainKwh != null }) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, SwissOutline),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = stringResource(R.string.vampire_daily_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        DailyDrainBarChart(
                            data = uiState.dailyDrains,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(200.dp)
                        )

                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.vampire_daily_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Recent idle periods list
            if (uiState.idlePeriods.isNotEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, SwissOutline),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = stringResource(R.string.vampire_recent_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        uiState.idlePeriods.take(10).forEach { period ->
                            IdlePeriodItem(period)
                            if (period != uiState.idlePeriods.lastOrNull()) {
                                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                            }
                        }
                    }
                }
            }

            // Optimization tips
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, SwissOutline),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.vampire_general_info_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    VampireTip(
                        title = stringResource(R.string.vampire_tip_sentry_title),
                        description = stringResource(R.string.vampire_tip_sentry_desc)
                    )
                    VampireTip(
                        title = stringResource(R.string.vampire_tip_cabin_overheat_title),
                        description = stringResource(R.string.vampire_tip_cabin_overheat_desc)
                    )
                    VampireTip(
                        title = stringResource(R.string.vampire_tip_climate_title),
                        description = stringResource(R.string.vampire_tip_climate_desc)
                    )
                    VampireTip(
                        title = stringResource(R.string.vampire_tip_updates_title),
                        description = stringResource(R.string.vampire_tip_updates_desc)
                    )
                }
            }

            // Explanation
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, SwissOutline),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.vampire_how_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.vampire_how_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun VampireStatCard(
    modifier: Modifier = Modifier,
    label: String,
    value: String
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, SwissOutline),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
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
private fun IdlePeriodItem(period: IdleDrainPeriod) {
    val displayFormatter = DateTimeFormatter.ofPattern("MM/dd HH:mm")

    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "-${period.drainPercent}%",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.error
            )
            Text(
                text = period.avgPowerW?.let { String.format(Locale.getDefault(), "%.1f W", it) }
                    ?: stringResource(R.string.unknown),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = formatDateTime(period.startDate, displayFormatter),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = String.format(Locale.getDefault(), "%.1fh", period.hoursIdle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            text = period.location ?: stringResource(R.string.vampire_location_unknown),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = stringResource(R.string.vampire_confidence) + ": " +
                "%.0f%%".format(period.confidence * 100) + " \u00B7 " +
                stringResource(R.string.vampire_detected_unknown),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun VampireTip(
    title: String,
    description: String
) {
    Column(modifier = Modifier.padding(vertical = 6.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun DailyDrainBarChart(
    data: List<DailyDrain>,
    modifier: Modifier = Modifier
) {
    val maxDrain = data.mapNotNull { it.totalDrainKwh }.maxOrNull() ?: return
    val barColor = MaterialTheme.colorScheme.error
    val gridColor = MaterialTheme.colorScheme.outlineVariant

    Canvas(modifier = modifier) {
        val barCount = data.size
        if (barCount == 0) return@Canvas

        val barWidth = (size.width / barCount) * 0.6f
        val barSpacing = (size.width / barCount) * 0.4f
        val chartHeight = size.height - 24.dp.toPx()
        val maxValue = (maxDrain * 1.1).coerceAtLeast(1.0)

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
        data.reversed().forEachIndexed { index, dailyDrain ->
            val drainKwh = dailyDrain.totalDrainKwh ?: return@forEachIndexed
            val barHeight = (drainKwh / maxValue * chartHeight).toFloat()
            val x = index * (barWidth + barSpacing) + barSpacing / 2
            val y = chartHeight - barHeight

            drawRect(
                color = barColor,
                topLeft = Offset(x, y),
                size = Size(barWidth, barHeight)
            )
        }

        // Draw date labels below bars (show every Nth label to avoid crowding)
        // Note: Native canvas text drawing omitted for compatibility
    }
}

private fun formatDateTime(isoDate: String, formatter: DateTimeFormatter): String {
    return try {
        OffsetDateTime.parse(isoDate).format(formatter)
    } catch (e: Exception) {
        isoDate.take(16)
    }
}
