package com.matelink.ui.screens.cost

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
import com.matelink.data.repository.HISTORY_IDENTITY_UNAVAILABLE
import com.matelink.domain.analytics.HistoryFreshness
import com.matelink.ui.components.AnalysisWindowSelector
import com.matelink.ui.components.CachedHistoryBanner
import com.matelink.ui.components.MetricPanelKind
import com.matelink.ui.components.MetricStatusPanel
import com.matelink.ui.components.HistoryStatusPanel
import com.matelink.ui.theme.SwissOutline
import kotlin.math.max
import java.util.Locale

private val AcColor = Color(0xFF2196F3)   // Blue
private val DcColor = Color(0xFFFF9800)   // Orange

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
        if (uiState.loading) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
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
                modifier = Modifier.fillMaxSize().padding(padding),
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

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
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
                    LegendDot(color = AcColor, label = stringResource(R.string.cost_ac))
                    Spacer(modifier = Modifier.width(16.dp))
                    LegendDot(color = DcColor, label = stringResource(R.string.cost_dc))
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

@Composable
private fun SummarySection(uiState: CostUiState, currencySymbol: String) {
    val hasCost = uiState.costCoverage > 0
    val hasEnergy = uiState.energyCoverage > 0
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SummaryCard(
            modifier = Modifier.weight(1f),
            title = stringResource(R.string.cost_total),
            value = if (hasCost) formatMoney(currencySymbol, uiState.totalCost, 2) else stringResource(R.string.analysis_no_records),
            subtitle = stringResource(R.string.cost_charges_count, uiState.totalCharges)
        )
        SummaryCard(
            modifier = Modifier.weight(1f),
            title = stringResource(R.string.cost_energy_added),
            value = if (hasEnergy) String.format(Locale.getDefault(), "%.1f kWh", uiState.totalEnergy) else stringResource(R.string.analysis_no_records),
            subtitle = if (hasCost && hasEnergy && uiState.totalEnergy > 0) {
                formatMoney(currencySymbol, uiState.totalCost / uiState.totalEnergy, 2) + "/kWh"
            } else {
                ""
            }
        )
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SummaryCard(
            modifier = Modifier.weight(1f),
            title = stringResource(R.string.cost_average_session),
            value = uiState.averageSessionCost?.let { formatMoney(currencySymbol, it, 2) }
                ?: stringResource(R.string.analysis_no_records),
            subtitle = if (uiState.manualCostCount > 0) {
                stringResource(R.string.cost_manual_count, uiState.manualCostCount)
            } else stringResource(R.string.cost_average_session_hint)
        )
        SummaryCard(
            modifier = Modifier.weight(1f),
            title = stringResource(R.string.cost_average_kwh),
            value = if (hasCost && hasEnergy && uiState.totalEnergy > 0) {
                formatMoney(currencySymbol, uiState.totalCost / uiState.totalEnergy, 2) + "/kWh"
            } else stringResource(R.string.analysis_no_records),
            subtitle = stringResource(R.string.cost_average_kwh_hint)
        )
    }
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
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, SwissOutline),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
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
        monthlyCosts.maxOfOrNull { it.acCost + it.dcCost } ?: 1.0,
        1.0
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, SwissOutline),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
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
                    val totalHeight = ((monthly.acCost + monthly.dcCost) / maxValue * chartHeight).toFloat()
                    val acHeight = (monthly.acCost / maxValue * chartHeight).toFloat()

                    // DC bar (bottom)
                    if (monthly.dcCost > 0) {
                        drawRect(
                            color = DcColor,
                            topLeft = Offset(x, chartHeight - totalHeight),
                            size = Size(barWidth, totalHeight)
                        )
                    }

                    // AC bar (top of stacked)
                    if (monthly.acCost > 0) {
                        drawRect(
                            color = AcColor,
                            topLeft = Offset(x, chartHeight - acHeight),
                            size = Size(barWidth, acHeight)
                        )
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
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, SwissOutline),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
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
