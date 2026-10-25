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
    val duration = if (startDt != null && endDt != null) {
        Duration.between(startDt, endDt).takeIf { !it.isNegative }
    } else null
    val durationMin = duration?.toMinutes()

    val durationText = when {
        duration == null -> unavailable
        duration.isZero -> stringResource(R.string.parked_duration_zero)
        duration.seconds < 60 -> stringResource(R.string.parked_duration_under_minute)
        else -> formatDuration(context.resources, durationMin!!.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
    }

    // Derive the delta from the displayed boundaries first so the hero badge and
    // the battery card can never disagree; fall back to the server scalar only
    // when either boundary is unknown.
    val startSoc = data.startBatteryLevel?.takeIf { it in 0..100 }
    val endSoc = data.endBatteryLevel?.takeIf { it in 0..100 }
    val batteryDeltaVal = if (startSoc != null && endSoc != null) {
        endSoc - startSoc
    } else data.batteryDelta?.takeIf { it in -100..100 }
    val qualifiedEnergy = data.qualifiedParkedEnergyKwh?.takeIf(Double::isFinite)
    val energyPerHour = parkedEnergyKwhPerHour(
        qualifiedEnergy, data.qualifiedAveragePowerW, duration
    )
    val energyRateFromTotal = qualifiedEnergy != null && duration != null && !duration.isZero
    val socPointsPerHour = parkedSocPointsPerHour(
        if (startSoc != null && endSoc != null) endSoc - startSoc else null,
        duration
    )
    val insideTemp = data.insideTempAverage?.takeIf(Double::isFinite)
    val outsideTemp = data.outsideTempAverage?.takeIf(Double::isFinite)
    val hasCloudDetail = qualifiedEnergy != null || energyPerHour != null ||
        insideTemp != null || outsideTemp != null

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
                        Text(
                            text = stringResource(
                                when {
                                    delta > 0 && data.linkedCharge != null -> R.string.parked_charge_badge
                                    delta > 0 -> R.string.parked_gain_badge
                                    delta < 0 -> R.string.parked_drop_badge
                                    else -> R.string.parked_no_change_badge
                                },
                                kotlin.math.abs(delta)
                            ),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = if (delta > 0) Color(0xFF10B981) else palette.onSurface,
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(
                                    if (delta > 0) Color(0xFF10B981).copy(alpha = 0.12f)
                                    else palette.onSurface.copy(alpha = 0.08f)
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
                                value = startSoc?.toString() ?: unavailable,
                                tint = Color(0xFF0284C7),
                                unit = if (startSoc != null) "%" else null,
                                valueFontSize = 17.sp
                            ),
                            TelemetryMetricSpec(
                                icon = Icons.Default.BatteryChargingFull,
                                label = stringResource(R.string.parked_end_battery),
                                value = endSoc?.toString() ?: unavailable,
                                tint = Color(0xFF0284C7),
                                unit = if (endSoc != null) "%" else null,
                                valueFontSize = 17.sp
                            ),
                            TelemetryMetricSpec(
                                icon = Icons.Default.BatteryAlert,
                                label = stringResource(R.string.parked_battery_delta),
                                value = batteryDeltaVal?.let { if (it > 0) "+$it" else "$it" } ?: unavailable,
                                tint = if ((batteryDeltaVal ?: 0) > 0) Color(0xFF10B981) else palette.onSurface,
                                unit = if (batteryDeltaVal != null) stringResource(R.string.parked_soc_point_unit) else null,
                                valueFontSize = 17.sp
                            )
                        ),
                        modifier = Modifier.padding(vertical = 4.dp),
                        adaptive = false,
                        maxColumns = 3
                    )
                }
                socPointsPerHour?.let { rate ->
                    val label = when {
                        rate > 0.0 && data.linkedCharge != null -> R.string.parked_soc_rate_charge
                        rate > 0.0 -> R.string.parked_soc_rate_gain
                        rate < 0.0 -> R.string.parked_soc_rate_drain
                        else -> R.string.parked_soc_rate_same
                    }
                    Text(
                        text = "${stringResource(label)} ${"%.1f".format(kotlin.math.abs(rate))} " +
                            stringResource(R.string.parked_soc_rate_unit),
                        style = MaterialTheme.typography.titleSmall,
                        color = if (rate > 0.0) Color(0xFF10B981) else palette.onSurface
                    )
                }
                if (socPointsPerHour != null && qualifiedEnergy == null) {
                    Text(stringResource(
                        if (socPointsPerHour > 0.0 && data.linkedCharge == null)
                            R.string.parked_soc_gain_unlinked_note
                        else R.string.parked_soc_only_note
                    ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
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

        // 4. Show only qualified telemetry; missing cloud history is one compact explanation.
        if (hasCloudDetail) {
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
                        Icon(Icons.Default.Thermostat, contentDescription = null,
                            modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.tertiary)
                        Text(stringResource(R.string.parked_cloud_section_title),
                            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                    TelemetryMetricStrip(
                        metrics = listOfNotNull(
                            qualifiedEnergy?.let { energy -> TelemetryMetricSpec(
                                icon = Icons.Default.Eco,
                                label = stringResource(R.string.parked_estimated_energy),
                                value = "%.2f".format(energy),
                                tint = if (energy < 0.0) Color(0xFF10B981) else palette.accent,
                                unit = "kWh", valueFontSize = 18.sp
                            ) },
                            energyPerHour?.let { rate -> TelemetryMetricSpec(
                                icon = Icons.Default.Power,
                                label = stringResource(when {
                                    !energyRateFromTotal -> R.string.parked_power_sample_average
                                    rate > 0.0 && data.linkedCharge == null -> R.string.parked_drain_energy_per_hour
                                    else -> R.string.parked_energy_per_hour
                                }),
                                value = "%.2f".format(rate),
                                tint = if (rate < 0.0) Color(0xFF10B981) else palette.accent,
                                unit = if (energyRateFromTotal) stringResource(R.string.parked_energy_per_hour_unit)
                                    else "kW",
                                valueFontSize = 18.sp
                            ) },
                            insideTemp?.let { temperature -> TelemetryMetricSpec(
                                icon = Icons.Default.Thermostat,
                                label = stringResource(R.string.parked_inside_temp),
                                value = "%.1f".format(temperature), tint = Color(0xFF0D9488),
                                unit = "°C", valueFontSize = 18.sp
                            ) },
                            outsideTemp?.let { temperature -> TelemetryMetricSpec(
                                icon = Icons.Default.Thermostat,
                                label = stringResource(R.string.parked_outside_temp),
                                value = "%.1f".format(temperature), tint = Color(0xFF0284C7),
                                unit = "°C", valueFontSize = 18.sp
                            ) }
                        ),
                        adaptive = true,
                        maxColumns = 2
                    )
                    if (data.sampleCount > 0 && data.coverageRatio.isFinite() &&
                        data.coverageRatio in 0.0..1.0) {
                        Text(stringResource(R.string.parked_power_coverage,
                            data.sampleCount, data.coverageRatio * 100),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        } else {
            Row(
                modifier = Modifier.padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Thermostat, contentDescription = null,
                    modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.parked_cloud_detail_pending),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Text(
            text = when (data.source) {
                "local_room_cache" -> stringResource(R.string.parked_detail_local_source_note)
                "database_latest", "teslamate_archive" -> stringResource(R.string.parked_detail_archive_source_note)
                else -> stringResource(R.string.parked_detail_other_source_note)
            },
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

private fun parkedHours(duration: Duration?): Double? = duration
    ?.takeIf { !it.isNegative && !it.isZero }
    ?.let { (it.seconds + it.nano / 1_000_000_000.0) / 3600.0 }
    ?.takeIf { it.isFinite() && it > 0.0 }

internal fun parkedSocPointsPerHour(deltaPoints: Int?, duration: Duration?): Double? {
    val hours = parkedHours(duration) ?: return null
    return deltaPoints?.takeIf { it in -100..100 }?.div(hours)?.takeIf(Double::isFinite)
}

internal fun parkedEnergyKwhPerHour(
    qualifiedEnergyKwh: Double?, qualifiedAveragePowerW: Double?, duration: Duration?
): Double? {
    val hours = parkedHours(duration) ?: return null
    val energy = qualifiedEnergyKwh?.takeIf(Double::isFinite)
    val fromEnergy = energy?.div(hours)
    return (fromEnergy ?: qualifiedAveragePowerW?.takeIf(Double::isFinite)?.div(1000.0))
        ?.takeIf(Double::isFinite)
}

internal fun parkedErrorLabel(error: String?, unavailable: String): String = when (error) {
    "history_not_collected", "history_not_connected" -> "暂无可验证的驻车详情；行程历史仍可查看"
    else -> unavailable
}

internal fun parkedBoundaryTimeLabel(value: String?, unavailable: String, includeYear: Boolean = true): String =
    com.matelink.util.parseIsoInstant(value)?.atZone(java.time.ZoneId.systemDefault())
        ?.format(java.time.format.DateTimeFormatter.ofPattern(if (includeYear) "yyyy-MM-dd HH:mm" else "MM-dd HH:mm")) ?: unavailable
