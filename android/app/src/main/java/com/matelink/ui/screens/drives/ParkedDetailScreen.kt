package com.matelink.ui.screens.drives

import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Eco
import androidx.compose.material.icons.filled.LocalParking
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Timer
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.matelink.R
import com.matelink.data.api.models.LinkedCharge
import com.matelink.data.api.models.ParkedDetailData
import com.matelink.ui.components.MateLinkLoadingPlaceholder
import com.matelink.ui.components.TelemetryMetricSpec
import com.matelink.ui.components.TelemetryMetricStrip
import com.matelink.ui.components.TelemetryPanel
import com.matelink.ui.theme.StatusWarning
import com.matelink.ui.theme.telemetryPalette
import com.matelink.util.formatDuration
import com.matelink.util.parseIsoDateTime
import java.time.Duration

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
                title = {
                    Text(
                        "驻车详情",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                },
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
            state.data != null -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                state.syncWarning?.let { warning ->
                    Text(
                        text = warning,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, top = 8.dp, end = 16.dp)
                    )
                }
                ParkedDetailContent(
                    data = state.data!!,
                    modifier = Modifier,
                    onNavigateToChargeDetail = onNavigateToChargeDetail
                )
            }
            else -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    parkedErrorLabel(state.error, stringResource(R.string.not_available)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(onClick = { viewModel.load(carId, olderDriveId, newerDriveId) }) {
                    Text(stringResource(R.string.retry))
                }
            }
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
    val context = androidx.compose.ui.platform.LocalContext.current
    val palette = telemetryPalette()

    val startDt = parseIsoDateTime(data.startDate)
    val endDt = parseIsoDateTime(data.endDate)
    val durationMin = if (startDt != null && endDt != null) {
        Duration.between(startDt, endDt).toMinutes().coerceAtLeast(0)
    } else null

    val durationText = durationMin?.let {
        formatDuration(context.resources, it.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
    } ?: unavailable

    val batteryDeltaVal = data.batteryDelta ?: if (data.startBatteryLevel != null && data.endBatteryLevel != null) {
        data.endBatteryLevel - data.startBatteryLevel
    } else null
    // A positive delta means the battery grew while parked — that is charging,
    // not consumption; the flat-rate drain estimate only models a discharge,
    // so it must stay unknown for gains.
    val estEnergy = data.qualifiedParkedEnergyKwh
        ?: batteryDeltaVal?.takeIf { it <= 0 }?.let { kotlin.math.abs(it) * 0.60 }
    val estEnergyStr = estEnergy?.let { "%.2f".format(it) } ?: unavailable

    val avgPowerStr = data.qualifiedAveragePowerW?.let { "%.0f".format(it) }
        ?: data.averagePowerKw?.let { "%.0f".format(it * 1000.0) }
        ?: unavailable

    val hasCloudDetail = data.qualifiedParkedEnergyKwh != null ||
        data.averagePowerKw != null || data.qualifiedAveragePowerW != null ||
        data.insideTempAverage != null || data.outsideTempAverage != null ||
        data.sampleCount > 0 || estEnergy != null

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. Summary hero: address, time window, total duration, charge/drain badge.
        TelemetryPanel(
            modifier = Modifier.fillMaxWidth(),
            accent = palette.accent
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(palette.accent.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.LocalParking,
                            contentDescription = null,
                            tint = palette.accent,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Text(
                        text = parkedAddressLabel(data.address) ?: stringResource(R.string.unknown_location),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                }
                Text(
                    text = "${parkedBoundaryTimeLabel(data.startDate, unavailable, includeYear = true)} → " +
                        parkedBoundaryTimeLabel(data.endDate, unavailable, includeYear = true),
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = com.matelink.ui.theme.MetricMono),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = stringResource(R.string.parked_duration_total, durationText),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    batteryDeltaVal?.let { delta ->
                        val charged = delta > 0
                        Text(
                            text = stringResource(
                                if (charged) R.string.parked_charge_badge else R.string.parked_drain_badge,
                                kotlin.math.abs(delta)
                            ),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = if (charged) Color(0xFF10B981) else Color(0xFFE11D48),
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(
                                    if (charged) Color(0xFF10B981).copy(alpha = 0.12f)
                                    else Color(0xFFE11D48).copy(alpha = 0.12f)
                                )
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }
            }
        }

        // 2. Battery boundaries — always available from adjacent local drives.
        TelemetryPanel(
            modifier = Modifier.fillMaxWidth(),
            accent = MaterialTheme.colorScheme.primary
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Bolt,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = stringResource(R.string.parked_battery_section_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.medium)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                ) {
                    TelemetryMetricStrip(
                        metrics = listOf(
                            TelemetryMetricSpec(
                                icon = Icons.Default.BatteryChargingFull,
                                label = stringResource(R.string.parked_start_battery),
                                value = data.startBatteryLevel?.takeIf { it in 0..100 }?.toString() ?: unavailable,
                                tint = Color(0xFF0284C7),
                                unit = if (data.startBatteryLevel != null) "%" else null,
                                valueFontSize = 17.sp
                            ),
                            TelemetryMetricSpec(
                                icon = Icons.Default.BatteryChargingFull,
                                label = stringResource(R.string.parked_end_battery),
                                value = data.endBatteryLevel?.takeIf { it in 0..100 }?.toString() ?: unavailable,
                                tint = Color(0xFF0284C7),
                                unit = if (data.endBatteryLevel != null) "%" else null,
                                valueFontSize = 17.sp
                            ),
                            TelemetryMetricSpec(
                                icon = Icons.Default.BatteryAlert,
                                label = stringResource(R.string.parked_battery_delta),
                                value = batteryDeltaVal?.let { if (it > 0) "+$it" else "$it" } ?: unavailable,
                                tint = if ((batteryDeltaVal ?: 0) < 0) Color(0xFFE11D48) else Color(0xFF10B981),
                                unit = if (batteryDeltaVal != null) "%" else null,
                                valueFontSize = 17.sp
                            )
                        ),
                        modifier = Modifier.padding(vertical = 4.dp),
                        adaptive = false,
                        maxColumns = 3
                    )
                }
            }
        }

        // 3. Linked charge card if charging occurred while parked.
        data.linkedCharge?.let { linkedCharge ->
            ChargeParkedCard(
                linkedCharge = linkedCharge,
                onNavigateToChargeDetail = onNavigateToChargeDetail
            )
        }

        // 4. Cloud telemetry detail: one honest pending line replaces a wall of
        // per-cell "no data" when nothing cloud-derived arrived yet.
        TelemetryPanel(
            modifier = Modifier.fillMaxWidth(),
            accent = MaterialTheme.colorScheme.tertiary
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Thermostat,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.tertiary
                    )
                    Text(
                        text = stringResource(R.string.parked_cloud_section_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                if (hasCloudDetail) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.medium)
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    ) {
                        TelemetryMetricStrip(
                            metrics = listOf(
                                TelemetryMetricSpec(
                                    icon = Icons.Default.Eco,
                                    label = stringResource(R.string.parked_estimated_energy),
                                    value = estEnergyStr,
                                    tint = Color(0xFF10B981),
                                    unit = if (estEnergy != null) "kWh" else null,
                                    valueFontSize = 17.sp
                                ),
                                TelemetryMetricSpec(
                                    icon = Icons.Default.Power,
                                    label = stringResource(R.string.parked_avg_power),
                                    value = avgPowerStr,
                                    tint = Color(0xFF6366F1),
                                    unit = if (avgPowerStr != unavailable) "W" else null,
                                    valueFontSize = 17.sp
                                ),
                                TelemetryMetricSpec(
                                    icon = Icons.Default.Thermostat,
                                    label = stringResource(R.string.parked_inside_temp),
                                    value = data.insideTempAverage?.let { "%.1f".format(it) } ?: unavailable,
                                    tint = Color(0xFF0D9488),
                                    unit = if (data.insideTempAverage != null) "°C" else null,
                                    valueFontSize = 17.sp
                                ),
                                TelemetryMetricSpec(
                                    icon = Icons.Default.Thermostat,
                                    label = stringResource(R.string.parked_outside_temp),
                                    value = data.outsideTempAverage?.let { "%.1f".format(it) } ?: unavailable,
                                    tint = Color(0xFF0284C7),
                                    unit = if (data.outsideTempAverage != null) "°C" else null,
                                    valueFontSize = 17.sp
                                ),
                                TelemetryMetricSpec(
                                    icon = Icons.Default.Schedule,
                                    label = stringResource(R.string.parked_coverage),
                                    value = "%.0f".format(data.coverageRatio * 100),
                                    tint = Color(0xFF10B981),
                                    unit = "%",
                                    valueFontSize = 17.sp
                                ),
                                TelemetryMetricSpec(
                                    icon = Icons.Default.Schedule,
                                    label = stringResource(R.string.parked_sample_count),
                                    value = "%,d".format(data.sampleCount),
                                    tint = Color(0xFF6366F1),
                                    unit = stringResource(R.string.parked_sample_unit),
                                    valueFontSize = 17.sp
                                )
                            ),
                            modifier = Modifier.padding(vertical = 4.dp),
                            adaptive = false,
                            maxColumns = 4
                        )
                    }
                } else {
                    Text(
                        text = stringResource(R.string.parked_cloud_detail_pending),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Text(
            text = if (data.source == "local_room_cache") stringResource(R.string.parked_detail_local_source_note)
            else "数据来源：${if (data.source == "database_latest") "TeslaMate 历史遥测" else data.source}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp)
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
            modifier = Modifier.padding(14.dp),
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

internal fun parkedErrorLabel(error: String?, unavailable: String): String = when (error) {
    "history_not_collected", "history_not_connected" -> "暂无可验证的驻车详情；行程历史仍可查看"
    else -> unavailable
}

internal fun parkedBoundaryTimeLabel(value: String?, unavailable: String, includeYear: Boolean = true): String =
    com.matelink.util.parseIsoInstant(value)?.atZone(java.time.ZoneId.systemDefault())
        ?.format(java.time.format.DateTimeFormatter.ofPattern(if (includeYear) "yyyy-MM-dd HH:mm" else "MM-dd HH:mm")) ?: unavailable
