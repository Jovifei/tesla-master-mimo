package com.matelink.ui.screens.drives

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.matelink.R
import com.matelink.data.api.models.ParkedDetailData
import com.matelink.ui.components.MateLinkLoadingPlaceholder
import com.matelink.ui.components.TelemetryMetricSpec
import com.matelink.ui.components.TelemetryMetricStrip
import com.matelink.ui.components.TelemetryPanel
import com.matelink.ui.theme.StatusWarning
import com.matelink.ui.theme.telemetryPalette
import com.matelink.util.formatDuration
import com.matelink.util.parseIsoInstant
import java.time.Duration
import java.time.ZoneId
import java.time.format.DateTimeFormatter

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
                    chargeBreakdown = state.chargeBreakdown,
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
    chargeBreakdown: ParkedChargeBreakdown?,
    modifier: Modifier = Modifier,
    onNavigateToChargeDetail: (Int) -> Unit
) {
    val unavailable = stringResource(R.string.not_available)
    val context = androidx.compose.ui.platform.LocalContext.current
    val palette = telemetryPalette()

    val startDt = parseIsoInstant(data.startDate)
    val endDt = parseIsoInstant(data.endDate)
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
    val hasRecordedCharge = chargeBreakdown?.sessions?.isNotEmpty() == true
    val hasChargeEvidence = hasRecordedCharge || data.linkedCharge != null ||
        !data.linkedCharges.isNullOrEmpty() || chargeBreakdown?.hasPartialOrInvalidSessions == true
    val telemetry = parkedTelemetryPresentation(data, duration, hasChargeEvidence)
    val qualifiedEnergy = telemetry.energyKwh
    val socPointsPerHour = parkedSocPointsPerHour(
        if (startSoc != null && endSoc != null) endSoc - startSoc else null,
        duration
    )

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
                    text = formatParkedDateRange(data.startDate, data.endDate, unavailable),
                    style = MaterialTheme.typography.bodyMedium,
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
                                    delta > 0 && hasRecordedCharge -> R.string.parked_charge_badge
                                    delta > 0 -> R.string.parked_gain_badge
                                    delta < 0 -> R.string.parked_drop_badge
                                    else -> R.string.parked_no_change_badge
                                },
                                kotlin.math.abs(delta)
                            ),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = when {
                                delta > 0 -> Color(0xFF10B981)
                                delta < 0 -> Color(0xFFE11D48)
                                else -> palette.onSurface
                            },
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(
                                    when {
                                        delta > 0 -> Color(0xFF10B981).copy(alpha = 0.12f)
                                        delta < 0 -> Color(0xFFE11D48).copy(alpha = 0.12f)
                                        else -> palette.onSurface.copy(alpha = 0.08f)
                                    }
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
                                value = startSoc?.toString() ?: "—",
                                tint = Color(0xFF0284C7),
                                unit = if (startSoc != null) "%" else null,
                                valueFontSize = 17.sp
                            ),
                            TelemetryMetricSpec(
                                icon = Icons.Default.BatteryChargingFull,
                                label = stringResource(R.string.parked_end_battery),
                                value = endSoc?.toString() ?: "—",
                                tint = Color(0xFF0284C7),
                                unit = if (endSoc != null) "%" else null,
                                valueFontSize = 17.sp
                            ),
                            TelemetryMetricSpec(
                                icon = Icons.Default.BatteryAlert,
                                label = stringResource(R.string.parked_battery_delta),
                                value = batteryDeltaVal?.let { if (it > 0) "+$it" else "$it" } ?: "—",
                                tint = when {
                                    (batteryDeltaVal ?: 0) > 0 -> Color(0xFF10B981)
                                    (batteryDeltaVal ?: 0) < 0 -> Color(0xFFE11D48)
                                    else -> palette.onSurface
                                },
                                unit = if (batteryDeltaVal != null) stringResource(R.string.parked_soc_point_unit) else null,
                                valueFontSize = 17.sp
                            )
                        ),
                        modifier = Modifier.padding(vertical = 4.dp),
                        adaptive = false,
                        maxColumns = 3
                    )
                }
                if (startSoc == null || endSoc == null) {
                    Text(
                        stringResource(R.string.parked_boundary_missing_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                socPointsPerHour?.takeUnless { hasChargeEvidence }?.let { rate ->
                    val label = when {
                        rate > 0.0 -> R.string.parked_soc_rate_gain
                        rate < 0.0 -> R.string.parked_soc_rate_drain
                        else -> R.string.parked_soc_rate_same
                    }
                    Text(
                        text = "${stringResource(label)} ${"%.1f".format(kotlin.math.abs(rate))} " +
                            stringResource(R.string.parked_soc_rate_unit),
                        style = MaterialTheme.typography.titleSmall,
                        color = when {
                            rate > 0.0 -> Color(0xFF10B981)
                            rate < 0.0 -> Color(0xFFE11D48)
                            else -> palette.onSurface
                        }
                    )
                }
                if (socPointsPerHour != null && !hasChargeEvidence && qualifiedEnergy == null) {
                    Text(stringResource(
                        if (socPointsPerHour > 0.0)
                            R.string.parked_soc_gain_unlinked_note
                        else R.string.parked_soc_only_note
                    ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        // 3. Separate recorded charging sessions; a whole-parked SOC rate is
        // not a non-charging drain rate when any session overlaps this window.
        if (hasRecordedCharge && chargeBreakdown != null) {
            ParkedChargeSessionsCard(chargeBreakdown, onNavigateToChargeDetail)
        } else if (chargeBreakdown != null && !chargeBreakdown.storedSessionsComplete) {
            Text(stringResource(R.string.parked_no_confirmed_charges),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        // Keep the full set of parked metrics visible without promoting unknown
        // historical values to zero or borrowing the current vehicle status.
        TelemetryPanel(modifier = Modifier.fillMaxWidth(), accent = MaterialTheme.colorScheme.tertiary) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Default.Thermostat, contentDescription = null,
                        modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.tertiary)
                    Text(stringResource(R.string.parked_cloud_section_title),
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
                ParkedTelemetryRow(Icons.Default.Eco, stringResource(R.string.parked_estimated_energy),
                    telemetry.energyKwh?.let { "%.2f".format(it) }, "kWh", Color(0xFF0D9488))
                ParkedTelemetryRow(Icons.Default.Power, stringResource(when {
                    telemetry.rate != null && !telemetry.rateFromTotal -> R.string.parked_power_sample_average
                    telemetry.rate != null && telemetry.rate > 0.0 && !hasRecordedCharge ->
                        R.string.parked_drain_energy_per_hour
                    else -> R.string.parked_energy_per_hour
                }), telemetry.rate?.let { "%.2f".format(it) },
                    if (telemetry.rateFromTotal) stringResource(R.string.parked_energy_per_hour_unit) else "kW",
                    Color(0xFF0284C7))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                ParkedTelemetryRow(Icons.Default.Thermostat, stringResource(R.string.parked_inside_temp),
                    telemetry.insideTemp?.let { "%.1f".format(it) }, "°C", Color(0xFF0D9488))
                ParkedTelemetryRow(Icons.Default.Thermostat, stringResource(R.string.parked_outside_temp),
                    telemetry.outsideTemp?.let { "%.1f".format(it) }, "°C", Color(0xFF0284C7))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                ParkedTelemetryRow(Icons.Default.Schedule, stringResource(R.string.parked_coverage),
                    telemetry.coveragePercent?.let { "%.0f".format(it) }, "%", Color(0xFF64748B))
                ParkedTelemetryRow(Icons.Default.Schedule, stringResource(R.string.parked_sample_count),
                    telemetry.sampleCount?.let { "%,d".format(it) },
                    stringResource(R.string.parked_sample_unit), Color(0xFF64748B))
                if (telemetry.hasMissing) {
                    Text(stringResource(R.string.parked_telemetry_missing_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
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
private fun ParkedTelemetryRow(icon: ImageVector, label: String, value: String?, unit: String?, tint: Color) {
    val available = value != null
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp),
            tint = if (available) tint else MaterialTheme.colorScheme.onSurfaceVariant)
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(buildAnnotatedString {
            withStyle(SpanStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold)) {
                append(value ?: "—")
            }
            if (available && unit != null) withStyle(SpanStyle(fontSize = 12.sp)) {
                append(" $unit")
            }
        }, color = if (available) tint else MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ParkedChargeSessionsCard(
    breakdown: ParkedChargeBreakdown,
    onNavigateToChargeDetail: (Int) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var expanded by remember(breakdown.sessions) { mutableStateOf(false) }
    val sessions = if (expanded) breakdown.sessions else breakdown.sessions.take(3)
    val zone = ZoneId.systemDefault()
    val dayTime = DateTimeFormatter.ofPattern("MM-dd HH:mm")
    val clockTime = DateTimeFormatter.ofPattern("HH:mm")
    fun durationLabel(value: Duration): String = when {
        value.isZero -> context.getString(R.string.parked_duration_zero)
        value.seconds < 60 -> context.getString(R.string.parked_duration_under_minute)
        else -> formatDuration(context.resources,
            value.toMinutes().coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
    }
    TelemetryPanel(accent = StatusWarning) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.BatteryChargingFull, contentDescription = null,
                    tint = StatusWarning, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.parked_recorded_charge_count, breakdown.sessions.size),
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            sessions.forEachIndexed { index, session ->
                if (index > 0) HorizontalDivider()
                val start = session.start.atZone(zone)
                val end = session.end?.atZone(zone)
                val timeRange = start.format(dayTime) + " → " +
                    (end?.format(if (end.toLocalDate() == start.toLocalDate()) clockTime else dayTime)
                        ?: stringResource(R.string.parked_charge_end_unrecorded))
                val duration = session.duration?.let(::durationLabel)
                val energy = session.energyAddedKwh?.let { "%.2f kWh".format(it) }
                    ?: stringResource(R.string.parked_charge_energy_unrecorded)
                val rate = session.energyPerHourKwh?.let {
                    "%.2f %s".format(it, stringResource(R.string.parked_energy_per_hour_unit))
                }
                val detail = listOfNotNull(duration, energy, rate).joinToString(" · ")
                Column(
                    modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp)
                        .clickable { onNavigateToChargeDetail(session.chargeId) }
                        .padding(vertical = 5.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(timeRange, modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null,
                            modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(detail,
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, softWrap = false)
                }
            }
            if (breakdown.sessions.size > 3) {
                TextButton(onClick = { expanded = !expanded }) {
                    Text(stringResource(
                        if (expanded) R.string.parked_charge_show_less
                        else R.string.parked_charge_show_all,
                        breakdown.sessions.size
                    ))
                }
            }
            breakdown.recordedChargingDuration?.let { recorded ->
                Text(stringResource(R.string.parked_recorded_charge_duration,
                    durationLabel(recorded)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            breakdown.outsideRecordedChargingDuration?.let { outside ->
                Text(stringResource(R.string.parked_outside_recorded_charge_duration,
                    durationLabel(outside)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val nonChargingRate = breakdown.nonChargingConsumptionPointsPerHour
                ?.takeIf(Double::isFinite)
            if (nonChargingRate != null) {
                Text(stringResource(
                    if (nonChargingRate < 0.0) R.string.parked_noncharging_soc_gain_rate
                    else R.string.parked_noncharging_soc_rate,
                    kotlin.math.abs(nonChargingRate)
                ),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface)
                Text(stringResource(R.string.parked_noncharging_energy_not_isolated),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text(stringResource(R.string.parked_noncharging_segment_missing),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!breakdown.storedSessionsComplete || breakdown.hasPartialOrInvalidSessions) {
                Text(stringResource(R.string.parked_charge_sessions_incomplete),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
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

internal data class ParkedTelemetryPresentation(
    val energyKwh: Double?,
    val rate: Double?,
    val rateFromTotal: Boolean,
    val insideTemp: Double?,
    val outsideTemp: Double?,
    val coveragePercent: Double?,
    val sampleCount: Int?
) {
    val hasMissing: Boolean get() = energyKwh == null || rate == null || insideTemp == null ||
        outsideTemp == null || coveragePercent == null || sampleCount == null
}

internal fun parkedTelemetryPresentation(
    data: ParkedDetailData, duration: Duration?, hasChargeEvidence: Boolean
): ParkedTelemetryPresentation {
    val energy = if (hasChargeEvidence) null else data.qualifiedParkedEnergyKwh?.takeIf(Double::isFinite)
    val rate = parkedEnergyKwhPerHour(energy,
        if (hasChargeEvidence) null else data.qualifiedAveragePowerW, duration)
    val samples = data.sampleCount.takeIf { it >= 0 && data.source != "local_room_cache" }
    val coverage = samples?.let {
        data.coverageRatio.takeIf { ratio -> ratio.isFinite() && ratio in 0.0..1.0 &&
            (it > 0 || ratio == 0.0) }?.times(100.0)
    }
    return ParkedTelemetryPresentation(
        energy, rate, energy != null && parkedHours(duration) != null,
        data.insideTempAverage?.takeIf(Double::isFinite),
        data.outsideTempAverage?.takeIf(Double::isFinite), coverage, samples
    )
}

internal fun parkedErrorLabel(error: String?, unavailable: String): String = when (error) {
    "history_not_collected", "history_not_connected" -> "暂无可验证的驻车详情；行程历史仍可查看"
    else -> unavailable
}

internal fun formatParkedDateRange(
    startDate: String?,
    endDate: String?,
    unavailable: String,
    locale: java.util.Locale = java.util.Locale.getDefault()
): String {
    val startInstant = com.matelink.util.parseIsoInstant(startDate)
    val endInstant = com.matelink.util.parseIsoInstant(endDate)
    if (startInstant == null && endInstant == null) return unavailable
    val zone = java.time.ZoneId.systemDefault()
    val startZdt = startInstant?.atZone(zone)
    val endZdt = endInstant?.atZone(zone)

    val patternDayTime = java.time.format.DateTimeFormatter.ofPattern("MM月dd日 HH:mm", locale)
    val patternTimeOnly = java.time.format.DateTimeFormatter.ofPattern("HH:mm", locale)

    return when {
        startZdt != null && endZdt != null -> {
            val startText = startZdt.format(patternDayTime)
            if (startZdt.toLocalDate() == endZdt.toLocalDate()) {
                val endText = endZdt.format(patternTimeOnly)
                "$startText → $endText"
            } else {
                val endText = endZdt.format(patternDayTime)
                "$startText → $endText"
            }
        }
        startZdt != null -> "${startZdt.format(patternDayTime)} → $unavailable"
        endZdt != null -> "$unavailable → ${endZdt.format(patternDayTime)}"
        else -> unavailable
    }
}

internal fun parkedBoundaryTimeLabel(value: String?, unavailable: String, includeYear: Boolean = true): String =
    com.matelink.util.parseIsoInstant(value)?.atZone(java.time.ZoneId.systemDefault())
        ?.format(java.time.format.DateTimeFormatter.ofPattern(if (includeYear) "yyyy-MM-dd HH:mm" else "MM-dd HH:mm")) ?: unavailable
