package com.matelink.ui.screens.drives

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.matelink.R
import com.matelink.data.api.models.LinkedCharge
import com.matelink.ui.components.TelemetryPanel
import com.matelink.ui.theme.StatusWarning
import com.matelink.data.api.models.ParkedDetailData
import com.matelink.util.formatCompactDateTimeRange
import com.matelink.util.toChineseDisplayAddress

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ParkedDetailScreen(
    carId: Int,
    olderDriveId: Int,
    newerDriveId: Int,
    onNavigateBack: () -> Unit,
    onNavigateToChargeDetail: (Int) -> Unit = {},
    viewModel: ParkedDetailViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    LaunchedEffect(carId, olderDriveId, newerDriveId) {
        viewModel.load(carId, olderDriveId, newerDriveId)
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("驻车详情") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                }
            )
        }
    ) { padding ->
        when {
            state.isLoading -> Column(
                modifier = Modifier.fillMaxSize().padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) { CircularProgressIndicator() }
            state.data != null -> ParkedDetailContent(
                data = state.data!!,
                modifier = Modifier.padding(padding),
                onNavigateToChargeDetail = onNavigateToChargeDetail
            )
            else -> Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) { Text(state.error ?: stringResource(R.string.not_available)) }
        }
    }
}

@Composable
private fun ParkedDetailContent(
    data: ParkedDetailData,
    modifier: Modifier = Modifier,
    onNavigateToChargeDetail: (Int) -> Unit
) {
    val unavailable = stringResource(R.string.not_available)
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = data.address.toChineseDisplayAddress() ?: stringResource(R.string.unknown_location),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = formatCompactDateTimeRange(data.startDate, data.endDate),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        data.linkedCharge?.let { linkedCharge ->
            ChargeParkedCard(
                linkedCharge = linkedCharge,
                onNavigateToChargeDetail = onNavigateToChargeDetail
            )
        }

        MetricBand(
            title = "驻车消耗",
            icon = Icons.Default.Bolt,
            accent = MaterialTheme.colorScheme.primary,
            entries = listOf(
                "电量变化" to data.batteryDelta?.let { "$it%" }.orUnavailable(unavailable),
                "估算能耗" to data.energyKwh?.let { "%.2f kWh".format(it) }.orUnavailable(unavailable),
                "平均功率" to data.averagePowerKw?.let { "%.1f kW".format(it) }.orUnavailable(unavailable),
                "峰值功率" to data.peakPowerKw?.let { "%.1f kW".format(it) }.orUnavailable(unavailable)
            )
        )
        MetricBand(
            title = "环境与数据",
            icon = Icons.Default.Thermostat,
            accent = MaterialTheme.colorScheme.tertiary,
            entries = listOf(
                "车内温度" to data.insideTempAverage?.let { "%.1f °C".format(it) }.orUnavailable(unavailable),
                "车外温度" to data.outsideTempAverage?.let { "%.1f °C".format(it) }.orUnavailable(unavailable),
                "采样数" to data.sampleCount.toString(),
                "覆盖率" to "%.0f%%".format(data.coverageRatio * 100)
            )
        )
        Text(
            text = "数据来源：${if (data.source == "database_latest") "TeslaMate 历史数据库" else data.source}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun ChargeParkedCard(
    linkedCharge: LinkedCharge,
    onNavigateToChargeDetail: (Int) -> Unit
) {
    TelemetryPanel(accent = StatusWarning) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.BatteryChargingFull,
                    contentDescription = null,
                    tint = StatusWarning
                )
                Text(
                    text = stringResource(R.string.charge_parked_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            Text(
                text = stringResource(R.string.charge_parked_summary),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            linkedCharge.energyAddedKwh?.takeIf { it.isFinite() && it >= 0.0 }?.let { energy ->
                Text(
                    text = "%.1f kWh".format(energy),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = StatusWarning
                )
            }
            TextButton(onClick = { onNavigateToChargeDetail(linkedCharge.chargeId) }) {
                Text(stringResource(R.string.charge_parked_view))
            }
        }
    }
}

@Composable
private fun MetricBand(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    accent: androidx.compose.ui.graphics.Color,
    entries: List<Pair<String, String>>
) {
    TelemetryPanel(
        modifier = Modifier.fillMaxWidth(),
        accent = accent
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(imageVector = icon, contentDescription = null, tint = accent)
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            entries.chunked(2).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { (label, value) ->
                        Column(Modifier.weight(1f)) {
                            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

private fun String?.orUnavailable(fallback: String): String = this ?: fallback
