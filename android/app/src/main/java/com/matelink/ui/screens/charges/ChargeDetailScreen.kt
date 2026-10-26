package com.matelink.ui.screens.charges


import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Paid
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.DeviceThermostat
import androidx.compose.material.icons.filled.ElectricalServices
import androidx.compose.material.icons.filled.EnergySavingsLeaf
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Power
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import com.matelink.ui.components.HistoryForegroundRefreshEffect
import com.matelink.ui.components.HistoryReadNotices
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import kotlin.math.roundToInt

import com.matelink.R
import com.matelink.data.api.models.ChargeDetail
import com.matelink.data.api.models.Units
import com.matelink.domain.model.UnitFormatter
import com.matelink.ui.components.FullscreenLineChart
import com.matelink.ui.components.MateLinkLoadingPlaceholder
import com.matelink.ui.components.TelemetryPanel
import com.matelink.ui.screens.trips.displayName
import android.content.Intent
import android.net.Uri
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.IconButtonDefaults
import com.matelink.domain.map.AmapConfiguration
import com.matelink.ui.components.launchExternalIntentSafely
import com.matelink.ui.components.AmapPointView
import com.matelink.util.formatDurationCompact
import com.matelink.util.formatMonthDayTime
import com.matelink.util.formatTime
import com.matelink.util.toChineseDisplayAddress
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChargeDetailScreen(
    carId: Int,
    chargeId: Int,
    exteriorColor: String? = null,
    onNavigateBack: () -> Unit,
    onNavigateToTripDetail: (tripStartDate: String) -> Unit = {},
    viewModel: ChargeDetailViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    HistoryForegroundRefreshEffect { viewModel.loadChargeDetail(carId, chargeId) }
    LaunchedEffect(carId, chargeId) {
        viewModel.loadChargeDetail(carId, chargeId)
    }

    LaunchedEffect(uiState.error) {
        uiState.error?.let { error ->
            snackbarHostState.showSnackbar(error)
            viewModel.clearError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.charge_details_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        if (uiState.isLoading) {
            MateLinkLoadingPlaceholder(modifier = Modifier.padding(padding))
        } else {
            uiState.chargeDetail?.let { detail ->
                ChargeDetailContent(
                    localArchiveLinkPending = uiState.localArchiveLinkPending,
                    historySyncWarning = uiState.historySyncWarning,
                    detail = detail,
                    stats = uiState.stats,
                    costPresentation = uiState.costPresentation,
                    units = uiState.units,
                    currencySymbol = uiState.currencySymbol,
                    isDcCharge = uiState.isDcCharge,
                    manualTotalAmount = uiState.manualTotalAmount,
                    pendingLegacyAmount = uiState.pendingLegacyAmount,
                    pendingBoundAmount = uiState.pendingBoundAmount,
                    otherCurrencyAmount = uiState.otherCurrencyAmount,
                    sourceUnconfirmedAmount = uiState.sourceUnconfirmedAmount,
                    containingTrip = uiState.containingTrip,
                    onNavigateToTripDetail = onNavigateToTripDetail,
                    onRemoveFromTrip = viewModel::removeFromTrip,
                    onSaveManualTotal = viewModel::saveManualTotalAmount,
                    modifier = Modifier.padding(padding)
                )
            }
        }
    }
}

@Composable
internal fun ChargeDetailContent(
    localArchiveLinkPending: Boolean,
    historySyncWarning: String?,
    detail: ChargeDetail,
    stats: ChargeDetailStats?,
    costPresentation: ChargeDetailCostPresentation,
    units: Units?,
    currencySymbol: String,
    isDcCharge: Boolean?,
    manualTotalAmount: Double?,
    pendingLegacyAmount: Double? = null,
    pendingBoundAmount: Pair<String?, Double>? = null,
    otherCurrencyAmount: Pair<String, Double>? = null,
    sourceUnconfirmedAmount: Double? = null,
    containingTrip: Pair<Long, com.matelink.domain.model.Trip>?,
    onNavigateToTripDetail: (String) -> Unit,
    onRemoveFromTrip: () -> Unit,
    onSaveManualTotal: (Double?) -> Unit,
    modifier: Modifier = Modifier
) {
    val is24Hour = android.text.format.DateFormat.is24HourFormat(LocalContext.current)
    val unavailableLabel = stringResource(R.string.not_available)
    val freeLabel = stringResource(R.string.charge_free)
    val actualLabel = stringResource(R.string.charge_cost_actual)
    val manualLabel = stringResource(R.string.charge_cost_manual)
    val costText = formatChargeDetailCostText(
        state = costPresentation.state,
        cost = costPresentation.cost,
        currencySymbol = currencySymbol,
        freeLabel = freeLabel,
        unavailableLabel = unavailableLabel
    )
    val costSourceText = when (costPresentation.state) {
        ChargeDetailCostState.ESTIMATE -> stringResource(R.string.charge_cost_estimate)
        ChargeDetailCostState.ACTUAL -> actualLabel
        ChargeDetailCostState.MANUAL -> manualLabel
        ChargeDetailCostState.FREE -> freeLabel
        ChargeDetailCostState.UNAVAILABLE -> unavailableLabel
    }
    val scrollState = rememberScrollState()
    var showPriceDialog by remember { mutableStateOf(false) }
    val series = remember(detail) { ChargeStatsCalculator.project(detail) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        HistoryReadNotices(localArchiveLinkPending, historySyncWarning)
        // Location header card
        LocationHeaderCard(
            detail = detail,
            isDcCharge = isDcCharge,
            costText = costText,
            costSourceText = costSourceText,
            onEditPrice = { showPriceDialog = true }
        )

        // Part-of-trip banner
        if (containingTrip != null) {
            val (_, trip) = containingTrip
            com.matelink.ui.components.PartOfTripCard(
                tripRoute = trip.displayName(),
                onNavigateToTrip = { onNavigateToTripDetail(trip.startDate) },
                onConfirmRemove = onRemoveFromTrip
            )
        }

        // Map showing charge location
        ChargeMapCard(
            latitude = detail.latitude,
            longitude = detail.longitude,
            address = detail.address
        )

        // Stats grid
        stats?.let { s ->
            // Localized labels
            val energyLabel = stringResource(R.string.energy)
            val batteryLabel = stringResource(R.string.battery)
            val powerLabel = stringResource(R.string.power)
            val chargerLabel = stringResource(R.string.charge_electrical_samples)
            val temperatureLabel = stringResource(R.string.temperature)
            val costLabel = stringResource(R.string.cost)
            val addedLabel = stringResource(R.string.energy_added)
            val usedLabel = stringResource(R.string.used)
            val efficiencyLabel = stringResource(R.string.charging_efficiency)
            val firstSocLabel = stringResource(R.string.charge_soc_first_observed)
            val lastSocLabel = stringResource(R.string.charge_soc_last_observed)
            val observedSocChangeLabel = stringResource(R.string.charge_soc_observed_change)
            val durationLabel = stringResource(R.string.duration)
            val maximumLabel = stringResource(R.string.maximum)
            val minimumLabel = stringResource(R.string.minimum)
            val averageLabel = stringResource(R.string.charge_sample_average)
            val voltageMaxLabel = stringResource(R.string.voltage_max)
            val voltageMinLabel = stringResource(R.string.voltage_min)
            val voltageAvgLabel = stringResource(R.string.voltage_avg)
            val currentMaxLabel = stringResource(R.string.current_max)
            val currentMinLabel = stringResource(R.string.current_min)
            val currentAvgLabel = stringResource(R.string.current_avg)
            val totalLabel = stringResource(R.string.total)
            val perKwhLabel = stringResource(R.string.per_kwh)

            // Energy section
            StatsSectionCard(
                title = energyLabel,
                icon = Icons.Default.EnergySavingsLeaf,
                stats = listOf(
                    StatItem(addedLabel, s.energyAdded?.let { "%.2f kWh".format(it) } ?: unavailableLabel),
                    StatItem(usedLabel, s.energyUsed?.let { "%.2f kWh".format(it) } ?: unavailableLabel),
                    StatItem(efficiencyLabel, s.efficiency?.let { "%.1f%%".format(it) } ?: unavailableLabel)
                )
            )
            // Battery section
            StatsSectionCard(
                title = batteryLabel,
                icon = Icons.Default.BatteryChargingFull,
                stats = listOf(
                    StatItem(firstSocLabel, s.batteryStart?.let { "$it%" } ?: unavailableLabel),
                    StatItem(lastSocLabel, s.batteryEnd?.let { "$it%" } ?: unavailableLabel),
                    StatItem(observedSocChangeLabel, s.batteryAdded?.let { "%+d%%".format(it) } ?: unavailableLabel),
                    StatItem(durationLabel, s.durationMin?.let(::formatDurationCompact) ?: unavailableLabel)
                )
            )
            if (series.soc.size >= 2) {
                BatteryChartCard(
                    samples = series.soc,
                    is24Hour = is24Hour,
                    title = stringResource(R.string.battery_level)
                )
            } else if (series.soc.size == 1) {
                val sample = series.soc.single()
                SingleChargeObservation(stringResource(R.string.battery_level), sample.first,
                    "${sample.second}%", MaterialTheme.colorScheme.primary, is24Hour)
            }

            // Power section
            if (s.powerMax != null) {
                StatsSectionCard(
                    title = powerLabel,
                    icon = Icons.Default.Bolt,
                    stats = listOf(
                        StatItem(maximumLabel, s.powerMax?.let { "$it kW" } ?: unavailableLabel),
                        StatItem(minimumLabel, s.powerMin?.let { "$it kW" } ?: unavailableLabel),
                        StatItem(averageLabel, s.powerAvg?.let { "%.1f kW".format(it) } ?: unavailableLabel)
                    )
                )
                if (series.power.size >= 2) {
                    PowerChartCard(
                        samples = series.power,
                        is24Hour = is24Hour,
                        title = stringResource(R.string.power_profile)
                    )
                } else if (series.power.size == 1) {
                    val sample = series.power.single()
                    SingleChargeObservation(stringResource(R.string.power_profile), sample.first,
                        "${sample.second} kW", Color(0xFF4CAF50), is24Hour)
                }
            }

            // Voltage and current are observations, not proof of AC/DC mode.
            val hasElectricalData = s.voltageMax != null || s.voltageMin != null || s.voltageAvg != null ||
                s.currentMax != null || s.currentMin != null || s.currentAvg != null
            if (hasElectricalData) {
                StatsSectionCard(
                    title = chargerLabel,
                    icon = Icons.Default.ElectricalServices,
                    stats = listOf(
                        StatItem(voltageMaxLabel, s.voltageMax?.let { "$it V" } ?: unavailableLabel),
                        StatItem(voltageMinLabel, s.voltageMin?.let { "$it V" } ?: unavailableLabel),
                        StatItem(voltageAvgLabel, s.voltageAvg?.let { "%.0f V".format(it) } ?: unavailableLabel),
                        StatItem(currentMaxLabel, s.currentMax?.let { "$it A" } ?: unavailableLabel),
                        StatItem(currentMinLabel, s.currentMin?.let { "$it A" } ?: unavailableLabel),
                        StatItem(currentAvgLabel, s.currentAvg?.let { "%.1f A".format(it) } ?: unavailableLabel)
                    )
                )
                if (series.voltage.size >= 2) {
                    VoltageChartCard(
                        samples = series.voltage,
                        is24Hour = is24Hour,
                        title = stringResource(R.string.voltage_profile)
                    )
                } else if (series.voltage.size == 1) {
                    val sample = series.voltage.single()
                    SingleChargeObservation(stringResource(R.string.voltage_profile), sample.first,
                        "${sample.second} V", MaterialTheme.colorScheme.tertiary, is24Hour)
                }
                if (series.current.size >= 2) {
                    CurrentChartCard(
                        samples = series.current,
                        is24Hour = is24Hour,
                        title = stringResource(R.string.current_profile)
                    )
                } else if (series.current.size == 1) {
                    val sample = series.current.single()
                    SingleChargeObservation(stringResource(R.string.current_profile), sample.first,
                        "${sample.second} A", MaterialTheme.colorScheme.secondary, is24Hour)
                }
            }

            // Temperature section
            val temperatureStats = buildList {
                s.tempMax?.let { add(StatItem(maximumLabel, UnitFormatter.formatTemperature(it, units))) }
                s.tempMin?.let { add(StatItem(minimumLabel, UnitFormatter.formatTemperature(it, units))) }
                s.tempAvg?.let { add(StatItem(averageLabel, UnitFormatter.formatTemperature(it, units))) }
            }
            if (temperatureStats.isNotEmpty()) {
                StatsSectionCard(
                    title = temperatureLabel,
                    icon = Icons.Default.DeviceThermostat,
                    stats = temperatureStats
                )
                if (series.temperature.size >= 2) {
                    TemperatureChartCard(
                        samples = series.temperature,
                        units = units,
                        is24Hour = is24Hour,
                        title = temperatureLabel
                    )
                } else if (series.temperature.size == 1) {
                    val sample = series.temperature.single()
                    SingleChargeObservation(temperatureLabel, sample.first,
                        UnitFormatter.formatTemperature(sample.second, units),
                        Color(0xFFFF9800), is24Hour)
                }
            }

            // Cost section
            val validEnergyKwh = detail.batteryInputKwh?.takeIf { it.isFinite() && it > 0.0 }
            val costPerKwh = if (costPresentation.cost != null && validEnergyKwh != null) {
                "$currencySymbol%.3f".format(costPresentation.cost / validEnergyKwh)
            } else {
                unavailableLabel
            }
            StatsSectionCard(
                title = costLabel,
                icon = Icons.Default.Paid,
                stats = listOf(
                    StatItem(totalLabel, costText),
                    StatItem(costLabel, costSourceText),
                    StatItem(perKwhLabel, costPerKwh)
                )
            )

        }

        Spacer(modifier = Modifier.height(16.dp))
        if (costPresentation.state == ChargeDetailCostState.MANUAL &&
            costPresentation.qualificationReason == "instance_unverified") {
            Text(stringResource(R.string.charge_cost_instance_unverified),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        pendingLegacyAmount?.let { amount ->
            Text(stringResource(R.string.charge_cost_saved_unconfirmed, amount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        pendingBoundAmount?.let { (code, amount) ->
            Text(stringResource(R.string.charge_cost_saved_unbound, code ?: "?", amount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        otherCurrencyAmount?.let { (code, amount) ->
            Text(stringResource(R.string.charge_cost_other_currency, code, amount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        sourceUnconfirmedAmount?.let { amount ->
            Text(stringResource(R.string.charge_cost_source_unconfirmed, amount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (costPresentation.qualificationReason == "source_unverified") {
            detail.cost?.takeIf { it.isFinite() && it >= 0.0 }?.let { amount ->
                Text(stringResource(R.string.charge_cost_source_identity_unverified, amount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (costPresentation.qualificationReason == "currency_mismatch") {
            detail.cost?.takeIf { it.isFinite() && it > 0.0 }?.let { amount ->
                Text(stringResource(R.string.charge_cost_source_other_currency,
                    detail.currencyCode.orEmpty(), amount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (costPresentation.qualificationReason == "provider_zero_unverified" &&
            sourceUnconfirmedAmount == null) {
            Text(stringResource(R.string.charge_cost_source_zero_unverified),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    if (showPriceDialog) {
        ChargePriceDialog(
            currentTotal = manualTotalAmount,
            pendingLegacyAmount = if (manualTotalAmount == null) pendingLegacyAmount else null,
            otherCurrencyAmount = otherCurrencyAmount,
            pendingBoundAmount = pendingBoundAmount,
            currencySymbol = currencySymbol,
            onDismiss = { showPriceDialog = false },
            onSave = { total ->
                onSaveManualTotal(total)
                showPriceDialog = false
            },
            onClear = {
                onSaveManualTotal(null)
                showPriceDialog = false
            }
        )
    }
}

@Composable
private fun LocationHeaderCard(
    detail: ChargeDetail,
    isDcCharge: Boolean?,
    costText: String,
    costSourceText: String,
    onEditPrice: () -> Unit
) {
    val is24Hour = android.text.format.DateFormat.is24HourFormat(LocalContext.current)
    val locationLabel = stringResource(R.string.location)
    val unknownLocationLabel = stringResource(R.string.unknown_location)
    val startedLabel = stringResource(R.string.started)
    val endedLabel = stringResource(R.string.ended)
    val energyAddedLabel = stringResource(R.string.energy_added_header)
    val unknownLabel = stringResource(R.string.unknown)

    TelemetryPanel(
        modifier = Modifier.fillMaxWidth(),
        accent = MaterialTheme.colorScheme.primary
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Location
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.LocationOn,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = locationLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                    )
                    Text(
                        text = detail.address.toChineseDisplayAddress() ?: unknownLocationLabel,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            HorizontalDivider(
                modifier = Modifier.padding(start = 36.dp),
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.2f)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CompactHeaderValue(
                    startedLabel,
                    formatDateTime(detail.startDate, unknownLabel, is24Hour),
                    Modifier.weight(1f)
                )
                CompactHeaderValue(
                    endedLabel,
                    formatDateTime(detail.endDate, unknownLabel, is24Hour),
                    Modifier.weight(1f)
                )
                CompactHeaderValue(
                    stringResource(R.string.duration),
                    detail.durationMin?.let(::formatDurationCompact)
                        ?: detail.durationStr?.takeIf { it.isNotBlank() }
                        ?: unknownLabel,
                    Modifier.weight(0.8f)
                )
            }

            // Price remains editable even when this session has no qualified energy.
            HorizontalDivider(
                modifier = Modifier.padding(start = 36.dp),
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.2f)
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val energy = detail.batteryInputKwh
                if (energy != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Power,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                            tint = Color(0xFF4CAF50)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = energyAddedLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "%.2f kWh".format(energy),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF4CAF50)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                ChargeTypeBadge(isDcCharge = isDcCharge)
                            }
                        }
                    }
                } else {
                    Spacer(modifier = Modifier.weight(1f))
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                        .clickable(onClickLabel = stringResource(R.string.edit_charge_cost),
                            role = Role.Button, onClick = onEditPrice)
                ) {
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = costSourceText,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                        )
                        Text(
                            text = costText,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

@Composable
private fun CompactHeaderValue(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
internal fun ChargePriceDialog(
    currentTotal: Double?,
    currencySymbol: String,
    onDismiss: () -> Unit,
    onSave: (Double) -> Unit,
    onClear: () -> Unit,
    pendingLegacyAmount: Double? = null,
    pendingBoundAmount: Pair<String?, Double>? = null,
    otherCurrencyAmount: Pair<String, Double>? = null
) {
    var value by remember(currentTotal) {
        mutableStateOf(currentTotal?.let { "%.2f".format(it) } ?: "")
    }
    val parsed = value.trim().replace(',', '.').toDoubleOrNull()
    val isValid = parsed != null && parsed.isFinite() && parsed >= 0.0

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.charge_total_dialog_title)) },
        text = {
            Column {
                pendingLegacyAmount?.let {
                    Text(stringResource(R.string.charge_cost_legacy_pending, it),
                        style = MaterialTheme.typography.bodySmall)
                }
                otherCurrencyAmount?.let { (code, amount) ->
                    Text(stringResource(R.string.charge_cost_other_currency, code, amount),
                        style = MaterialTheme.typography.bodySmall)
                }
                pendingBoundAmount?.let { (code, amount) ->
                    Text(stringResource(R.string.charge_cost_saved_unbound, code ?: "?", amount),
                        style = MaterialTheme.typography.bodySmall)
                }
                OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text(stringResource(R.string.charge_total_amount)) },
                placeholder = { Text(stringResource(R.string.charge_total_amount_hint)) },
                prefix = { Text(currencySymbol) },
                supportingText = if (value.isNotBlank() && !isValid) {
                    { Text(stringResource(R.string.charge_price_invalid)) }
                } else null,
                isError = value.isNotBlank() && !isValid,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = isValid,
                onClick = { parsed?.let(onSave) }
            ) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            Row {
                if (currentTotal != null) {
                    TextButton(onClick = onClear) {
                        Text(stringResource(R.string.charge_price_clear))
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.cancel))
                }
            }
        }
    )
}

@Composable
private fun ChargeMapCard(
    latitude: Double?,
    longitude: Double?,
    address: String?,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val locationTitle = stringResource(R.string.charge_location)
    val openInMapsLabel = stringResource(R.string.where_was_i_open_in_maps)
    val hasValidCoords = AmapConfiguration.isUsableCoordinate(latitude, longitude)
    val displayAddress = address?.toChineseDisplayAddress() ?: address

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = locationTitle,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    if (!displayAddress.isNullOrBlank()) {
                        Text(
                            text = displayAddress,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                if (hasValidCoords && latitude != null && longitude != null) {
                    FilledIconButton(
                        onClick = {
                            val geoUri = Uri.parse("geo:$latitude,$longitude?q=$latitude,$longitude")
                            context.launchExternalIntentSafely(Intent(Intent.ACTION_VIEW, geoUri))
                        },
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            contentColor = MaterialTheme.colorScheme.primary
                        ),
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.OpenInNew,
                            contentDescription = openInMapsLabel,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (!hasValidCoords || latitude == null || longitude == null) {
                Row(
                    modifier = Modifier.padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        Icons.Default.LocationOn,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = stringResource(R.string.amap_no_position),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(250.dp)
                        .clip(RoundedCornerShape(8.dp))
                ) {
                    AmapPointView(
                        modifier = Modifier.fillMaxSize(),
                        latitude = latitude,
                        longitude = longitude,
                        title = displayAddress ?: locationTitle,
                        zoom = 16f
                    )
                }
            }
        }
    }
}

data class StatItem(val label: String, val value: String)

@Composable
private fun StatsSectionCard(
    title: String,
    icon: ImageVector,
    stats: List<StatItem>
) {
    // Get the current screen settings
    val configuration = LocalConfiguration.current
    val screenWidth = configuration.screenWidthDp

    // Define how many columns we want according to the available screen width
    val columnCount = when {
        screenWidth > 600 -> 4 // Big screen or landscape orientation
        screenWidth > 340 -> 3 // Standard screen
        else -> 2              // Small screen
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 8.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            // Divide the list of statistics according to the calculated number of columns
            val chunked = stats.chunked(columnCount)
            chunked.forEachIndexed { index, row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    row.forEach { stat ->
                        StatItemView(
                            label = stat.label,
                            value = stat.value,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    // Fill the leftover space if the last row is not complete.
                    // This prevents a single item from stretching too much
                    val emptySlots = columnCount - row.size
                    if (emptySlots > 0) {
                        repeat(emptySlots) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
                if (index < chunked.size - 1) {
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }
    }
}


@Composable
private fun StatItemView(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold
        )
    }
}

/** One real point is useful evidence, but it is not a line or a trend. */
@Composable
internal fun SingleChargeObservation(
    title: String, at: Instant, value: String, color: Color, is24Hour: Boolean
) {
    Card(modifier = Modifier.fillMaxWidth().testTag("charge-single-observation"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(color))
            Column(Modifier.weight(1f)) {
                Text("$title · ${stringResource(R.string.charge_single_observation)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(formatObservationTime(at, is24Hour), style = MaterialTheme.typography.bodySmall)
            }
            Text(value, style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold, maxLines = 2)
        }
    }
}

@Composable
private fun PowerChartCard(
    samples: List<Pair<Instant, Double>>,
    is24Hour: Boolean,
    title: String
) {
    val powers = samples.map { it.second.toFloat() }
    if (powers.size < 2) return

    ChartCard(
        title = title,
        icon = Icons.Default.Bolt,
        data = powers,
        color = Color(0xFF4CAF50),
        unit = "kW",
        sampleTimes = samples.map { it.first },
        is24Hour = is24Hour
    )
}

@Composable
private fun VoltageChartCard(
    samples: List<Pair<Instant, Double>>,
    is24Hour: Boolean,
    title: String
) {
    val voltages = samples.map { it.second.toFloat() }
    if (voltages.size < 2) return

    ChartCard(
        title = title,
        icon = Icons.Default.ElectricalServices,
        data = voltages,
        color = MaterialTheme.colorScheme.tertiary,
        unit = "V",
        sampleTimes = samples.map { it.first },
        is24Hour = is24Hour
    )
}

@Composable
private fun CurrentChartCard(
    samples: List<Pair<Instant, Double>>,
    is24Hour: Boolean,
    title: String
) {
    val currents = samples.map { it.second.toFloat() }
    if (currents.size < 2) return

    ChartCard(
        title = title,
        icon = Icons.Default.Power,
        data = currents,
        color = MaterialTheme.colorScheme.secondary,
        unit = "A",
        sampleTimes = samples.map { it.first },
        is24Hour = is24Hour
    )
}

@Composable
private fun TemperatureChartCard(
    samples: List<Pair<Instant, Double>>,
    units: Units?,
    is24Hour: Boolean,
    title: String
) {
    val temps = samples.map { it.second.toFloat() }
    if (temps.size < 2) return
    var yMin = kotlin.math.floor(temps.min())
    var yMax = kotlin.math.ceil(temps.max())
    if (yMin == yMax) {
        yMin -= 1
        yMax += 1
    }
    ChartCard(
        title = title,
        icon = Icons.Default.DeviceThermostat,
        data = temps,
        color = Color(0xFFFF9800),
        unit = UnitFormatter.getTemperatureUnit(units),
        fixedMinMax = Pair(yMin, yMax),
        sampleTimes = samples.map { it.first },
        is24Hour = is24Hour
    )
}

@Composable
private fun BatteryChartCard(
    samples: List<Pair<Instant, Int>>,
    is24Hour: Boolean,
    title: String
) {
    val batteryLevels = samples.map { it.second.toFloat() }
    if (batteryLevels.size < 2) return
    var yMin = (kotlin.math.floor(batteryLevels.min() / 10.0) * 10).toFloat()
    var yMax = (kotlin.math.ceil(batteryLevels.max() / 10.0) * 10).toFloat()
    if (yMin == yMax) {
        yMin -= 1
        yMax += 1
    }

    ChartCard(
        title = title,
        icon = Icons.Default.BatteryChargingFull,
        data = batteryLevels,
        color = MaterialTheme.colorScheme.primary,
        unit = "%",
        fixedMinMax = Pair(yMin, yMax),
        sampleTimes = samples.map { it.first },
        is24Hour = is24Hour
    )
}

@Composable
private fun ChartCard(
    title: String,
    icon: ImageVector,
    data: List<Float>,
    color: Color,
    unit: String,
    showZeroLine: Boolean = false,
    fixedMinMax: Pair<Float, Float>? = null,
    sampleTimes: List<Instant> = emptyList(),
    is24Hour: Boolean = true,
    convertValue: (Float) -> Float = { it }
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 8.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = color
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            FullscreenLineChart(
                data = data,
                color = color,
                unit = unit,
                showZeroLine = showZeroLine,
                fixedMinMax = fixedMinMax,
                timeLabels = extractTimeLabels(sampleTimes, is24Hour),
                convertValue = convertValue,
                fractionToTimeLabel = { fraction -> timeAtFraction(sampleTimes, fraction, is24Hour) },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun ChargeTypeBadge(isDcCharge: Boolean?) {
    val backgroundColor = when (isDcCharge) {
        true -> Color(0xFFFF9800)
        false -> Color(0xFF4CAF50)
        null -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val text = when (isDcCharge) {
        true -> stringResource(R.string.charging_dc)
        false -> stringResource(R.string.charging_ac)
        null -> stringResource(R.string.status_unknown)
    }

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(backgroundColor)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
    }
}

/**
 * Extract 5 labels from this chart's qualified, ordered sample times.
 * Returns list of 5 time strings at 0%, 25%, 50%, 75%, and 100% positions.
 * Following the chart guidelines: start, 1st quarter, half, 3rd quarter, end.
 */
private fun extractTimeLabels(times: List<Instant>, is24Hour: Boolean): List<String> {
    if (times.isEmpty()) return listOf("", "", "", "", "")

    // 5 positions: start (0%), 1st quarter (25%), half (50%), 3rd quarter (75%), end (100%)
    val indices = listOf(0, times.size / 4, times.size / 2, times.size * 3 / 4, times.size - 1)
    return indices.map { idx ->
        times.getOrNull(idx.coerceIn(0, times.size - 1))
            ?.let { formatObservationTime(it, is24Hour) } ?: ""
    }
}

private fun timeAtFraction(times: List<Instant>, fraction: Float, is24Hour: Boolean): String {
    if (times.isEmpty()) return ""
    val index = (fraction.coerceIn(0f, 1f) * times.lastIndex).roundToInt()
    return formatObservationTime(times[index], is24Hour)
}

private fun formatObservationTime(at: Instant, is24Hour: Boolean): String =
    at.atZone(ZoneId.systemDefault()).toLocalDateTime()
        .formatTime(Locale.getDefault(), is24Hour)

private fun formatDateTime(dateStr: String?, unknownLabel: String = "Unknown", is24Hour: Boolean? = null): String {
    return formatMonthDayTime(dateStr, is24Hour = is24Hour) ?: unknownLabel
}

/**
 * Amount shown on the detail card. Estimated amounts keep the `≈` marker used by the
 * list, summary and chart so a default-rate figure is never mistaken for a billed one.
 */
internal fun formatChargeDetailCostText(
    state: ChargeDetailCostState,
    cost: Double?,
    currencySymbol: String,
    freeLabel: String,
    unavailableLabel: String
): String = when {
    state == ChargeDetailCostState.FREE -> freeLabel
    cost != null ->
        (if (state == ChargeDetailCostState.ESTIMATE) "≈" else "") +
            "$currencySymbol%.2f".format(cost)
    else -> unavailableLabel
}
