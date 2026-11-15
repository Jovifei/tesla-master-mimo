package com.matelink.ui.screens.cost

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.matelink.R
import com.matelink.ui.components.MateLinkLoadingPlaceholder
import com.matelink.data.repository.HISTORY_IDENTITY_UNAVAILABLE
import com.matelink.domain.analytics.HistoryFreshness
import com.matelink.ui.components.AnalysisWindowSelector
import com.matelink.ui.components.CachedHistoryBanner
import com.matelink.ui.components.MetricPanelKind
import com.matelink.ui.components.MetricStatusPanel
import com.matelink.ui.components.HistoryStatusPanel
import com.matelink.ui.components.HistoryForegroundRefreshEffect
import com.matelink.ui.theme.SwissOutline
import kotlin.math.max
import java.util.Locale

private val AcColor = Color(0xFF2196F3)   // Blue
private val DcColor = Color(0xFFFF9800)   // Orange
private val UnknownCostColor = Color(0xFF94A3B8)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CostScreen(
    carId: Int,
    onBack: () -> Unit = {},
    viewModel: CostViewModel = hiltViewModel()
) {
    LaunchedEffect(carId) {
        viewModel.load(carId)
    }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    HistoryForegroundRefreshEffect { viewModel.refresh() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.cost_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                }
            )
        }
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = uiState.isRefreshing,
            onRefresh = viewModel::refresh,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
        if (uiState.loading && uiState.totalCost == null && uiState.totalCharges == 0) {
            MateLinkLoadingPlaceholder()
            return@PullToRefreshBox
        }

        if (uiState.error != null) {
                Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                MetricStatusPanel(
                    kind = MetricPanelKind.ERROR,
                    title = stringResource(R.string.metric_state_error_title),
                    body = if (uiState.error == HISTORY_IDENTITY_UNAVAILABLE) {
                        stringResource(R.string.history_identity_unavailable_message)
                    } else {
                        uiState.error ?: stringResource(R.string.no_data)
                    },
                    actionLabel = stringResource(R.string.metric_state_retry),
                    onAction = viewModel::refresh
                )
            }
            return@PullToRefreshBox
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
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

            var showCachedNoticeDialog by remember { mutableStateOf(false) }
            if (showCachedNoticeDialog) {
                AlertDialog(
                    onDismissRequest = { showCachedNoticeDialog = false },
                    title = { Text(stringResource(R.string.metric_state_cached_title)) },
                    text = { Text(stringResource(R.string.metric_state_cached_body)) },
                    confirmButton = {
                        TextButton(onClick = { showCachedNoticeDialog = false }) {
                            Text(stringResource(android.R.string.ok))
                        }
                    }
                )
            }
            if (uiState.historyFreshness == HistoryFreshness.STALE) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showCachedNoticeDialog = true },
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    border = BorderStroke(
                        0.5.dp,
                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(15.dp)
                        )
                        Text(
                            text = "历史缓存 · 仅供离线查看 ⓘ",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            if (uiState.totalCharges == 0) {
                HistoryStatusPanel(
                    reason = uiState.noDataReason,
                    emptyBody = stringResource(R.string.metric_state_empty_body)
                )
                return@Column
            }

            // Summary cards
            SummarySection(uiState, uiState.currencySymbol)

            // Monthly AC/DC cost chart
            if (uiState.monthlyCosts.isNotEmpty() && uiState.costCoverage > 0) {
                Text(
                    text = stringResource(R.string.cost_monthly_breakdown),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                MonthlyCostChart(monthlyCosts = uiState.monthlyCosts)

                // Legend
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (uiState.monthlyCosts.any { it.acCost > 0.0 }) {
                        LegendDot(color = AcColor, label = stringResource(R.string.cost_ac))
                        Spacer(modifier = Modifier.width(12.dp))
                    }
                    if (uiState.monthlyCosts.any { it.dcCost > 0.0 }) {
                        LegendDot(color = DcColor, label = stringResource(R.string.cost_dc))
                        Spacer(modifier = Modifier.width(12.dp))
                    }
                    if (uiState.monthlyCosts.any { it.unknownCost > 0.0 }) {
                        LegendDot(color = UnknownCostColor, label = stringResource(R.string.cost_mode_unknown))
                    }
                }
            }

            // Top locations
            if (uiState.topLocations.isNotEmpty() && uiState.costCoverage > 0) {
                Text(
                    text = stringResource(R.string.cost_top_locations),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                uiState.topLocations.forEach { loc ->
                    LocationCard(locationCost = loc, currencySymbol = uiState.currencySymbol)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
        }
    }
}

@Composable
internal fun SummarySection(uiState: CostUiState, currencySymbol: String) {
    val hasCost = uiState.costCoverage > 0 && uiState.totalCost != null
    val hasEstimate = uiState.estimatedCoverage > 0 && uiState.estimatedCost != null
    val hasPartial = uiState.partialEstimatedCoverage > 0 && uiState.partialEstimatedCost != null
    val hasEnergy = uiState.energyCoverage > 0 && uiState.totalEnergy != null
    val hasPair = uiState.pairedCoverage > 0 && uiState.pairedUnitCost != null
    if (hasCost || hasEstimate) Row(
        modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        uiState.totalCost?.takeIf { hasCost }?.let { total ->
            SummaryCard(Modifier.weight(1f), stringResource(R.string.cost_recorded_manual_total),
                formatMoney(currencySymbol, total, 2),
                uiState.averageSessionCost?.let { avg ->
                    "${stringResource(R.string.cost_average_session)} ${formatMoney(currencySymbol, avg, 2)}"
                }.orEmpty())
        }
        uiState.estimatedCost?.takeIf { hasEstimate }?.let { estimate ->
            SummaryCard(Modifier.weight(1f), stringResource(R.string.cost_default_rate_estimate),
                "≈" + formatMoney(currencySymbol, estimate, 2),
                stringResource(R.string.cost_estimate_coverage, uiState.estimatedCoverage))
        }
    }
    if (hasPartial) {
        SummaryCard(Modifier.fillMaxWidth(), stringResource(R.string.cost_partial_observation_estimate),
            "≈" + formatMoney(currencySymbol, uiState.partialEstimatedCost!!, 2),
            stringResource(R.string.cost_partial_observation_coverage, uiState.partialEstimatedCoverage))
    }
    if (hasEnergy || hasPair) Row(
        modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        uiState.totalEnergy?.takeIf { hasEnergy }?.let { energy ->
            SummaryCard(Modifier.weight(1f), stringResource(R.string.cost_energy_added),
                String.format(Locale.getDefault(), "%.1f kWh", energy), "")
        }
        uiState.pairedUnitCost?.takeIf { hasPair }?.let { paired ->
            SummaryCard(Modifier.weight(1f), stringResource(R.string.cost_paired_battery_unit),
                formatMoney(currencySymbol, paired, 2) + "/kWh", "")
        }
    }
    if (!hasCost && !hasEstimate && !hasPartial && !hasEnergy) {
        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))) {
            Text(stringResource(R.string.cost_no_paired_values), Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyMedium)
        }
    } else if (!hasPair && hasCost && hasEnergy) {
        Text(stringResource(R.string.cost_no_paired_values),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Text(stringResource(R.string.cost_coverage_counts, uiState.costCoverage,
        uiState.totalCharges, uiState.energyCoverage, uiState.pairedCoverage),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (hasCost || hasEstimate || hasPartial) Text(stringResource(R.string.cost_current_currency_assumption),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun SummaryCard(
    modifier: Modifier = Modifier,
    title: String,
    value: String,
    subtitle: String
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            if (subtitle.isNotEmpty()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun MonthlyCostChart(monthlyCosts: List<MonthlyCost>) {
    val maxValue = max(
        monthlyCosts.maxOfOrNull { it.acCost + it.dcCost + it.unknownCost } ?: 1.0,
        1.0
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
            ) {
                val barWidth = (size.width - (monthlyCosts.size * 8).dp.toPx()) / max(monthlyCosts.size, 1)
                val chartHeight = size.height - 20.dp.toPx()

                monthlyCosts.forEachIndexed { index, monthly ->
                    val x = index * (barWidth + 8.dp.toPx())
                    var top = chartHeight
                    listOf(monthly.dcCost to DcColor, monthly.acCost to AcColor,
                        monthly.unknownCost to UnknownCostColor).forEach { (cost, color) ->
                        if (cost > 0) {
                            val height = (cost / maxValue * chartHeight).toFloat()
                            top -= height
                            drawRect(color, topLeft = Offset(x, top), size = Size(barWidth, height))
                        }
                    }
                }

                // Draw month labels below chart
                drawContext.canvas.nativeCanvas.apply {
                    val textPaint = android.graphics.Paint().apply {
                        color = android.graphics.Color.GRAY
                        textSize = 10.sp.toPx()
                        textAlign = android.graphics.Paint.Align.CENTER
                    }
                    monthlyCosts.forEachIndexed { index, monthly ->
                        val x = index * (barWidth + 8.dp.toPx()) + barWidth / 2
                        val label = monthly.month.takeLast(2) // Show MM only
                        drawText(label, x, size.height, textPaint)
                    }
                }
            }
        }
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(modifier = Modifier.size(12.dp)) {
            drawCircle(color = color)
        }
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun LocationCard(locationCost: LocationCost, currencySymbol: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = locationCost.address,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = stringResource(R.string.cost_location_count, locationCost.count),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = formatMoney(currencySymbol, locationCost.totalCost, 2),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 12.dp)
            )
        }
    }
}

private fun formatMoney(symbol: String, amount: Double, decimals: Int): String {
    val pattern = "%s%." + decimals + "f"
    return String.format(Locale.getDefault(), pattern, symbol, amount)
}
