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
import com.matelink.ui.components.MateLinkLoadingPlaceholder
import com.matelink.ui.theme.StatusWarning
import com.matelink.data.api.models.ParkedDetailData
import com.matelink.util.formatCompactDateTimeRange

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
            state.isLoading -> MateLinkLoadingPlaceholder(modifier = Modifier.padding(padding))
            state.data != null -> ParkedDetailContent(
                data = state.data!!,
                modifier = Modifier.padding(padding),
                onNavigateToChargeDetail = onNavigateToChargeDetail
            )
            else -> Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) { Text(parkedErrorLabel(state.error, stringResource(R.string.not_available))) }
        }
    }
}

@Composable
private fun ParkedDetailContent(
    data: ParkedDetailData,
    modifier: Modifier = Modifier,
    onNavigateToChargeDetail: (Int) -> Unit
) {
    val socChange = data.batteryDelta?.takeIf { it in -100..100 }?.let { delta ->
        when {
            delta > 0 -> stringResource(R.string.parked_soc_drop, delta)
            delta < 0 -> stringResource(R.string.parked_soc_gain, -delta)
            else -> stringResource(R.string.parked_soc_same)
        }
    }
    val powerCoverage = data.coverageRatio.takeIf { it.isFinite() && it in 0.0..1.0 }
    val coverageLabel = if (data.sampleCount >= 0 && powerCoverage != null) {
        stringResource(R.string.parked_power_coverage, data.sampleCount, powerCoverage * 100)
    } else stringResource(R.string.parked_power_coverage_unknown)
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = parkedAddressLabel(data.address) ?: stringResource(R.string.unknown_location),
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
            title = stringResource(R.string.parked_battery_title),
            icon = Icons.Default.Bolt,
            accent = MaterialTheme.colorScheme.primary,
            emptyMessage = stringResource(R.string.parked_no_battery_data),
            entries = listOf(
                stringResource(R.string.parked_start_soc) to data.startBatteryLevel?.takeIf { it in 0..100 }?.let { "$it%" },
                stringResource(R.string.parked_end_soc) to data.endBatteryLevel?.takeIf { it in 0..100 }?.let { "$it%" },
                stringResource(R.string.parked_soc_change) to socChange
            )
        )
        MetricBand(
            title = stringResource(R.string.parked_energy_title),
            icon = Icons.Default.Bolt,
            accent = MaterialTheme.colorScheme.primary,
            emptyMessage = stringResource(R.string.parked_no_energy_data),
            entries = listOf(
                stringResource(R.string.parked_energy_change) to data.qualifiedParkedEnergyKwh?.takeIf(Double::isFinite)?.let { "%.2f kWh".format(it) },
                stringResource(R.string.parked_average_power) to data.qualifiedAveragePowerW?.takeIf(Double::isFinite)?.let { "%.0f W".format(it) },
                stringResource(R.string.parked_peak_power) to data.peakPowerKw?.takeIf(Double::isFinite)?.let { "%.1f kW".format(it) }
            )
        )
        MetricBand(
            title = stringResource(R.string.parked_environment_title),
            icon = Icons.Default.Thermostat,
            accent = MaterialTheme.colorScheme.tertiary,
            emptyMessage = stringResource(R.string.parked_no_temperature_data),
            entries = listOf(
                stringResource(R.string.parked_inside_temperature) to data.insideTempAverage?.takeIf(Double::isFinite)?.let { "%.1f °C".format(it) },
                stringResource(R.string.parked_outside_temperature) to data.outsideTempAverage?.takeIf(Double::isFinite)?.let { "%.1f °C".format(it) }
            )
        )
        Text(
            text = coverageLabel,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
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
    entries: List<Pair<String, String?>>,
    emptyMessage: String
) {
    val visible = entries.mapNotNull { (label, value) -> value?.let { label to it } }
    TelemetryPanel(
        modifier = Modifier.fillMaxWidth(),
        accent = accent
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(imageVector = icon, contentDescription = null, tint = accent)
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            if (visible.isEmpty()) Text(
                emptyMessage,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            visible.chunked(2).forEach { row ->
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

internal fun parkedErrorLabel(error: String?, unavailable: String): String = when (error) {
    "history_not_collected", "history_not_connected" -> "暂无可验证的驻车详情；行程历史仍可查看"
    else -> unavailable
}

internal fun parkedBoundaryTimeLabel(value: String?, unavailable: String): String =
    com.matelink.util.parseIsoInstant(value)?.atZone(java.time.ZoneId.systemDefault())
        ?.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) ?: unavailable
