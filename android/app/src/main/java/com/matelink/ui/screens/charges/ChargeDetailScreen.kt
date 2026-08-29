package com.matelink.ui.screens.charges


import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Paid
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import kotlin.math.roundToInt

import com.matelink.R
import com.matelink.data.api.models.ChargeDetail
import com.matelink.data.api.models.ChargePoint
import com.matelink.data.api.models.Units
import com.matelink.domain.model.UnitFormatter
import com.matelink.ui.components.FullscreenLineChart
import com.matelink.ui.components.MateLinkLoadingPlaceholder
import com.matelink.ui.components.TelemetryPanel
import com.matelink.ui.screens.trips.displayName
import com.matelink.ui.components.AmapPointView
import com.matelink.util.formatDurationCompact
import com.matelink.util.formatMonthDayTime
import com.matelink.util.formatTime
import com.matelink.util.parseIsoDateTime
import com.matelink.util.toChineseDisplayAddress

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
                    detail = detail,
                    stats = uiState.stats,
                    costPresentation = uiState.costPresentation,
                    units = uiState.units,
                    currencySymbol = uiState.currencySymbol,
                    isDcCharge = uiState.isDcCharge,
                    manualTotalAmount = uiState.manualTotalAmount,
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
private fun ChargeDetailContent(
    detail: ChargeDetail,
    stats: ChargeDetailStats?,
    costPresentation: ChargeDetailCostPresentation,
    units: Units?,
    currencySymbol: String,
    isDcCharge: Boolean,
    manualTotalAmount: Double?,
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
    val estimatedLabel = stringResource(R.string.charge_cost_estimated)
    val costText = when {
        costPresentation.state == ChargeDetailCostState.FREE -> freeLabel
        costPresentation.cost != null -> "$currencySymbol%.2f".format(costPresentation.cost)
        else -> unavailableLabel
    }
    val costSourceText = when (costPresentation.state) {
        ChargeDetailCostState.ACTUAL -> actualLabel
        ChargeDetailCostState.MANUAL -> manualLabel
        ChargeDetailCostState.FREE -> freeLabel
        ChargeDetailCostState.ESTIMATED -> estimatedLabel
        ChargeDetailCostState.UNAVAILABLE -> unavailableLabel
    }
    val scrollState = rememberScrollState()
    var sharedXFraction by remember { mutableStateOf<Float?>(null) }
    var showPriceDialog by remember { mutableStateOf(false) }
    val chargePoints = detail.chargePoints.orEmpty()
    val hasChartData = chargePoints.size > 2
    val timeLabels = remember(chargePoints, is24Hour) {
        if (hasChartData) extractTimeLabels(chargePoints, is24Hour) else emptyList()
    }
    val fractionToTimeLabel: (Float) -> String = remember(chargePoints, is24Hour) {
        { fraction: Float ->
            if (chargePoints.isEmpty()) {
                ""
            } else {
                val index = (fraction * chargePoints.lastIndex).roundToInt()
                    .coerceIn(0, chargePoints.lastIndex)
                chargePoints[index].date?.let { dateStr ->
                    parseIsoDateTime(dateStr)
                        ?.formatTime(java.util.Locale.getDefault(), is24Hour)
                        ?: ""
                } ?: ""
            }
        }
    }

    LaunchedEffect(scrollState) {
        snapshotFlow { scrollState.isScrollInProgress }
            .collect { isScrolling -> if (isScrolling) sharedXFraction = null }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .pointerInput(Unit) { detectTapGestures { sharedXFraction = null } }
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Location header card
        LocationHeaderCard(
            detail = detail,
            isDcCharge = isDcCharge,
            costText = costText,
            costSourceText = costSourceText,
            manualTotalAmount = manualTotalAmount,
            currencySymbol = currencySymbol,
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
        if (detail.latitude != null && detail.longitude != null) {
            ChargeMapCard(latitude = detail.latitude, longitude = detail.longitude)
        }

        // Stats grid
        stats?.let { s ->
            // Localized labels
            val energyLabel = stringResource(R.string.energy)
            val batteryLabel = stringResource(R.string.battery)
            val powerLabel = stringResource(R.string.power)
            val chargerLabel = stringResource(R.string.charger)
            val temperatureLabel = stringResource(R.string.temperature)
            val costLabel = stringResource(R.string.cost)
            val addedLabel = stringResource(R.string.energy_added)
            val usedLabel = stringResource(R.string.used)
            val efficiencyLabel = stringResource(R.string.charging_efficiency)
            val startLabel = stringResource(R.string.start)
            val endLabel = stringResource(R.string.end)
            val durationLabel = stringResource(R.string.duration)
            val maximumLabel = stringResource(R.string.maximum)
            val minimumLabel = stringResource(R.string.minimum)
            val averageLabel = stringResource(R.string.average)
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
                    StatItem(startLabel, s.batteryStart?.let { "$it%" } ?: unavailableLabel),
                    StatItem(endLabel, s.batteryEnd?.let { "$it%" } ?: unavailableLabel),
                    StatItem(addedLabel, s.batteryAdded?.let { "%+d%%".format(it) } ?: unavailableLabel),
                    StatItem(durationLabel, s.durationMin?.let(::formatDurationCompact) ?: unavailableLabel)
                )
            )
            if (hasChartData && chargePoints.any { it.batteryLevel != null }) {
                BatteryChartCard(
                    chargePoints = chargePoints,
                    timeLabels = timeLabels,
                    title = stringResource(R.string.battery_level),
                    externalSelectedFraction = sharedXFraction,
                    onXSelected = { sharedXFraction = it },
                    fractionToTimeLabel = fractionToTimeLabel
                )
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
                if (hasChartData && chargePoints.any { (it.chargerPower ?: 0) > 0 }) {
                    PowerChartCard(
                        chargePoints = chargePoints,
                        timeLabels = timeLabels,
                        title = stringResource(R.string.power_profile),
                        externalSelectedFraction = sharedXFraction,
                        onXSelected = { sharedXFraction = it },
                        fractionToTimeLabel = fractionToTimeLabel
                    )
                }
            }

            // Voltage & Current section
            // Shown only for AC charges
            val hasElectricalData = s.voltageMax != null || s.voltageMin != null || s.voltageAvg != null ||
                s.currentMax != null || s.currentMin != null || s.currentAvg != null
            if (!isDcCharge && hasElectricalData) {
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
                if (hasChartData && chargePoints.any { (it.chargerVoltage ?: 0) > 0 }) {
                    VoltageChartCard(
                        chargePoints = chargePoints,
                        timeLabels = timeLabels,
                        title = stringResource(R.string.voltage_profile),
                        externalSelectedFraction = sharedXFraction,
                        onXSelected = { sharedXFraction = it },
                        fractionToTimeLabel = fractionToTimeLabel
                    )
                }
                if (hasChartData && chargePoints.any { (it.chargerCurrent ?: 0) > 0 }) {
                    CurrentChartCard(
                        chargePoints = chargePoints,
                        timeLabels = timeLabels,
                        title = stringResource(R.string.current_profile),
                        externalSelectedFraction = sharedXFraction,
                        onXSelected = { sharedXFraction = it },
                        fractionToTimeLabel = fractionToTimeLabel
                    )
                }
            }

            // Temperature section
            if (s.tempMax != null) {
                StatsSectionCard(
                    title = temperatureLabel,
                    icon = Icons.Default.DeviceThermostat,
                    stats = listOf(
                        StatItem(maximumLabel, s.tempMax?.let { UnitFormatter.formatTemperature(it, units) } ?: unavailableLabel),
                        StatItem(minimumLabel, s.tempMin?.let { UnitFormatter.formatTemperature(it, units) } ?: unavailableLabel),
                        StatItem(averageLabel, s.tempAvg?.let { UnitFormatter.formatTemperature(it, units) } ?: unavailableLabel)
                    )
                )
                if (hasChartData && chargePoints.any { it.outsideTemp != null }) {
                    TemperatureChartCard(
                        chargePoints = chargePoints,
                        units = units,
                        timeLabels = timeLabels,
                        title = temperatureLabel,
                        externalSelectedFraction = sharedXFraction,
                        onXSelected = { sharedXFraction = it },
                        fractionToTimeLabel = fractionToTimeLabel
                    )
                }
            }

            // Cost section
            val validEnergyKwh = detail.chargeEnergyAdded?.takeIf { it.isFinite() && it > 0.0 }
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
    }

    if (showPriceDialog) {
        ChargePriceDialog(
            currentTotal = manualTotalAmount,
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
    isDcCharge: Boolean,
    costText: String,
    costSourceText: String,
    manualTotalAmount: Double?,
    currencySymbol: String,
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

            // Energy added and cost summary
            detail.chargeEnergyAdded?.let { energy ->
                HorizontalDivider(
                    modifier = Modifier.padding(start = 36.dp),
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.2f)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Energy Added (left side) with AC/DC badge
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

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable(onClick = onEditPrice)
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
                            imageVector = Icons.Default.Paid,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
                Text(
                    text = manualTotalAmount?.let {
                        "${stringResource(R.string.charge_total_amount)} $currencySymbol${"%.2f".format(it)}"
                    } ?: stringResource(R.string.edit_charge_cost),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .align(Alignment.End)
                        .clickable(onClick = onEditPrice)
                        .padding(vertical = 4.dp)
                )
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
    onClear: () -> Unit
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
private fun ChargeMapCard(latitude: Double, longitude: Double) {
    val locationTitle = stringResource(R.string.location)
    val chargeLocationMarker = stringResource(R.string.charge_location)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            Text(
                text = locationTitle,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .clip(RoundedCornerShape(8.dp))
            ) {
                // Charge location map using AmapPointView (高德)
                AmapPointView(
                    modifier = Modifier.fillMaxSize(),
                    latitude = latitude,
                    longitude = longitude,
                    title = chargeLocationMarker,
                    zoom = 16f
                )
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

@Composable
private fun PowerChartCard(
    chargePoints: List<ChargePoint>,
    timeLabels: List<String>,
    title: String,
    externalSelectedFraction: Float? = null,
    onXSelected: ((Float?) -> Unit)? = null,
    fractionToTimeLabel: ((Float) -> String)? = null
) {
    val powers = chargePoints.mapNotNull { it.chargerPower?.toFloat() }
    if (powers.size < 2) return

    ChartCard(
        title = title,
        icon = Icons.Default.Bolt,
        data = powers,
        color = Color(0xFF4CAF50),
        unit = "kW",
        timeLabels = timeLabels,
        externalSelectedFraction = externalSelectedFraction,
        onXSelected = onXSelected,
        fractionToTimeLabel = fractionToTimeLabel
    )
}

@Composable
private fun VoltageChartCard(
    chargePoints: List<ChargePoint>,
    timeLabels: List<String>,
    title: String,
    externalSelectedFraction: Float? = null,
    onXSelected: ((Float?) -> Unit)? = null,
    fractionToTimeLabel: ((Float) -> String)? = null
) {
    val voltages = chargePoints.mapNotNull { it.chargerVoltage?.toFloat() }
    if (voltages.size < 2) return

    ChartCard(
        title = title,
        icon = Icons.Default.ElectricalServices,
        data = voltages,
        color = MaterialTheme.colorScheme.tertiary,
        unit = "V",
        timeLabels = timeLabels,
        externalSelectedFraction = externalSelectedFraction,
        onXSelected = onXSelected,
        fractionToTimeLabel = fractionToTimeLabel
    )
}

@Composable
private fun CurrentChartCard(
    chargePoints: List<ChargePoint>,
    timeLabels: List<String>,
    title: String,
    externalSelectedFraction: Float? = null,
    onXSelected: ((Float?) -> Unit)? = null,
    fractionToTimeLabel: ((Float) -> String)? = null
) {
    val currents = chargePoints.mapNotNull { it.chargerCurrent?.toFloat() }
    if (currents.size < 2) return

    ChartCard(
        title = title,
        icon = Icons.Default.Power,
        data = currents,
        color = MaterialTheme.colorScheme.secondary,
        unit = "A",
        timeLabels = timeLabels,
        externalSelectedFraction = externalSelectedFraction,
        onXSelected = onXSelected,
        fractionToTimeLabel = fractionToTimeLabel
    )
}

@Composable
private fun TemperatureChartCard(
    chargePoints: List<ChargePoint>,
    units: Units?,
    timeLabels: List<String>,
    title: String,
    externalSelectedFraction: Float? = null,
    onXSelected: ((Float?) -> Unit)? = null,
    fractionToTimeLabel: ((Float) -> String)? = null
) {
    val temps = chargePoints.mapNotNull { it.outsideTemp?.toFloat() }
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
        timeLabels = timeLabels,
        fixedMinMax = Pair(yMin, yMax),
        externalSelectedFraction = externalSelectedFraction,
        onXSelected = onXSelected,
        fractionToTimeLabel = fractionToTimeLabel
    )
}

@Composable
private fun BatteryChartCard(
    chargePoints: List<ChargePoint>,
    timeLabels: List<String>,
    title: String,
    externalSelectedFraction: Float? = null,
    onXSelected: ((Float?) -> Unit)? = null,
    fractionToTimeLabel: ((Float) -> String)? = null
) {
    val batteryLevels = chargePoints.mapNotNull { it.batteryLevel?.toFloat() }
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
        timeLabels = timeLabels,
        externalSelectedFraction = externalSelectedFraction,
        onXSelected = onXSelected,
        fractionToTimeLabel = fractionToTimeLabel
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
    timeLabels: List<String> = emptyList(),
    convertValue: (Float) -> Float = { it },
    externalSelectedFraction: Float? = null,
    onXSelected: ((Float?) -> Unit)? = null,
    fractionToTimeLabel: ((Float) -> String)? = null
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
                timeLabels = timeLabels,
                convertValue = convertValue,
                externalSelectedFraction = externalSelectedFraction,
                onXSelected = onXSelected,
                fractionToTimeLabel = fractionToTimeLabel,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun ChargeTypeBadge(isDcCharge: Boolean) {
    val backgroundColor = if (isDcCharge) Color(0xFFFF9800) else Color(0xFF4CAF50)
    val text = if (isDcCharge) stringResource(R.string.charging_dc) else stringResource(R.string.charging_ac)

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
 * Extract 5 time labels from charge points for X axis display.
 * Returns list of 5 time strings at 0%, 25%, 50%, 75%, and 100% positions.
 * Following the chart guidelines: start, 1st quarter, half, 3rd quarter, end.
 */
private fun extractTimeLabels(chargePoints: List<ChargePoint>, is24Hour: Boolean? = null): List<String> {
    if (chargePoints.isEmpty()) return listOf("", "", "", "", "")

    val locale = java.util.Locale.getDefault()
    val times = chargePoints.mapNotNull { point ->
        point.date?.let { parseIsoDateTime(it) }
    }

    if (times.isEmpty()) return listOf("", "", "", "", "")

    // 5 positions: start (0%), 1st quarter (25%), half (50%), 3rd quarter (75%), end (100%)
    val indices = listOf(0, times.size / 4, times.size / 2, times.size * 3 / 4, times.size - 1)
    return indices.map { idx ->
        times.getOrNull(idx.coerceIn(0, times.size - 1))?.formatTime(locale, is24Hour) ?: ""
    }
}

private fun formatDateTime(dateStr: String?, unknownLabel: String = "Unknown", is24Hour: Boolean? = null): String {
    return formatMonthDayTime(dateStr, is24Hour = is24Hour) ?: unknownLabel
}
