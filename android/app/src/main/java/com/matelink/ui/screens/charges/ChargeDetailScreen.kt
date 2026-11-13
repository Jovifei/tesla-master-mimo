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
import androidx.compose.material.icons.filled.EvStation
import androidx.compose.material.icons.filled.Paid
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.EnergySavingsLeaf
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Surface
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
import com.matelink.ui.components.HistoryArchiveNotice
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import kotlin.math.roundToInt

import com.matelink.R
import com.matelink.data.api.models.ChargeDetail
import com.matelink.data.model.Currency
import com.matelink.ui.components.FullscreenLineChart
import com.matelink.ui.components.MateLinkLoadingPlaceholder
import com.matelink.ui.components.showHistoryReadFailure
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
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val feedbackContext = LocalContext.current

    HistoryForegroundRefreshEffect { viewModel.loadChargeDetail(carId, chargeId) }
    LaunchedEffect(carId, chargeId) {
        viewModel.loadChargeDetail(carId, chargeId)
    }

    LaunchedEffect(uiState.error) {
        uiState.error?.let { error ->
            val hasContent = uiState.chargeDetail != null
            val retried = snackbarHostState.showHistoryReadFailure(feedbackContext, error, hasContent) {
                viewModel.clearError()
                viewModel.loadChargeDetail(carId, chargeId)
            }
            if (!retried && hasContent) viewModel.clearError()
        }
    }

    HistoryArchiveNotice(uiState.localArchiveLinkPending, uiState.historyNoticeLocalCarId)

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
                    containerColor = MaterialTheme.colorScheme.surface
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
                    localArchiveLinkPending = uiState.localArchiveLinkPending &&
                        uiState.historyNoticeLocalCarId?.let { it < 0 } != true,
                    historySyncWarning = uiState.historySyncWarning,
                    detail = detail,
                    stats = uiState.stats,
                    costPresentation = uiState.costPresentation,
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
    val pending = pendingChargeAmountForDisplay(
        costPresentation.state == ChargeDetailCostState.MANUAL && costPresentation.cost != null,
        pendingBoundAmount, pendingLegacyAmount)
    val effectiveCostText = formatChargeDetailCostText(costPresentation.state, costPresentation.cost,
        currencySymbol, freeLabel, unavailableLabel)
    val costText = when {
        pending != null -> "${pending.currencySymbol.orEmpty()}%.2f".format(pending.amount)
        costPresentation.cost != null || costPresentation.state == ChargeDetailCostState.FREE -> effectiveCostText.removePrefix("≈")
        sourceUnconfirmedAmount != null -> "%.2f".format(sourceUnconfirmedAmount)
        else -> unavailableLabel
    }
    val secondaryCostText = if (pending != null && costPresentation.cost != null) {
        when (costPresentation.state) {
            ChargeDetailCostState.ESTIMATE -> "${stringResource(R.string.charge_cost_estimate)} $effectiveCostText"
            ChargeDetailCostState.ACTUAL -> "$actualLabel $effectiveCostText"
            ChargeDetailCostState.FREE -> freeLabel
            else -> null
        }
    } else null
    val currencyNoticeText = if (pending != null && pending.currencySymbol == null)
        stringResource(R.string.charge_cost_currency_pending_short) else null
    val estimateNote = if (costPresentation.state == ChargeDetailCostState.ESTIMATE && costPresentation.cost != null)
        stringResource(chargeEstimateBasisResource(costPresentation.qualificationReason)) else null
    val sourceNotice = when (costPresentation.qualificationReason) {
        "instance_unverified" -> stringResource(R.string.charge_cost_instance_unverified)
        "source_unverified" -> detail.cost?.takeIf { it.isFinite() && it >= 0.0 }?.let {
            stringResource(R.string.charge_cost_source_identity_unverified, it)
        }
        "currency_mismatch" -> detail.cost?.takeIf { it.isFinite() && it > 0.0 }?.let {
            stringResource(R.string.charge_cost_source_other_currency, detail.currencyCode.orEmpty(), it)
        }
        "provider_zero_unverified" -> stringResource(R.string.charge_cost_source_zero_unverified)
        else -> null
    }
    val costNotices = listOfNotNull(
        pending?.let { stringResource(R.string.charge_cost_pending_short) },
        currencyNoticeText, secondaryCostText, estimateNote, sourceNotice,
        otherCurrencyAmount?.let { (code, amount) -> stringResource(R.string.charge_cost_other_currency, code, amount) },
        sourceUnconfirmedAmount?.let { stringResource(R.string.charge_cost_source_unconfirmed, it) }
    ).distinct()
    val scrollState = rememberScrollState()
    var showPriceDialog by remember { mutableStateOf(false) }
    var showCostNotice by remember { mutableStateOf(false) }
    val series = remember(detail) { ChargeStatsCalculator.project(detail) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        HistoryReadNotices(localArchiveLinkPending, historySyncWarning)

        // Map showing charge location
        ChargeMapCard(
            latitude = detail.latitude,
            longitude = detail.longitude,
            address = detail.address
        )

        // Unified charge card with location, times, energy, SOC and cost.
        UnifiedChargeCard(
            detail = detail,
            stats = stats,
            isDcCharge = isDcCharge,
            costText = costText,
            onCostNotice = if (costNotices.isNotEmpty()) {
                { showPriceDialog = false; showCostNotice = true }
            } else null,
            onEditPrice = { showCostNotice = false; showPriceDialog = true }
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

        if (series.power.size >= 2) {
            PowerChartCard(series.power, is24Hour, stringResource(R.string.power_profile))
        } else if (series.power.size == 1) {
            val sample = series.power.single()
            SingleChargeObservation(stringResource(R.string.power_profile), sample.first,
                "${sample.second} kW", Color(0xFF4CAF50), is24Hour)
        }
    }

    if (showCostNotice && costNotices.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { showCostNotice = false },
            title = { Text(stringResource(R.string.charge_cost_basis)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    costNotices.forEach { Text(it) }
                }
            },
            confirmButton = {
                TextButton(onClick = { showCostNotice = false }) { Text(stringResource(R.string.close)) }
            }
        )
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
private fun UnifiedChargeCard(
    detail: ChargeDetail,
    stats: ChargeDetailStats?,
    isDcCharge: Boolean?,
    costText: String,
    onCostNotice: (() -> Unit)?,
    onEditPrice: () -> Unit
) {
    val is24Hour = android.text.format.DateFormat.is24HourFormat(LocalContext.current)
    val unknownLocationLabel = stringResource(R.string.unknown_location)
    val startedLabel = stringResource(R.string.started)
    val endedLabel = stringResource(R.string.ended)
    val durationLabel = stringResource(R.string.duration)
    val unknownLabel = stringResource(R.string.unknown)
    val notAvailableLabel = stringResource(R.string.not_available)

    TelemetryPanel(
        modifier = Modifier.fillMaxWidth(),
        accent = MaterialTheme.colorScheme.primary
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.LocationOn,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
                )
                Spacer(modifier = Modifier.width(6.dp))
                val displayLocation = detail.address?.takeIf { !it.contains("未知") }?.toChineseDisplayAddress()
                    ?: if (detail.latitude != null && detail.longitude != null) "%.3f°N, %.3f°E".format(detail.latitude, detail.longitude)
                    else unknownLocationLabel
                Text(
                    text = displayLocation,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Header: Title with Charge Type Badge & Price Edit Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.EvStation,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = stringResource(R.string.charge_details_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    ChargeTypeBadge(isDcCharge = isDcCharge)
                }
                Surface(
                    onClick = onEditPrice,
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = null,
                            modifier = Modifier.size(13.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = stringResource(R.string.edit_charge_cost),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
            )

            // Times: Started, Ended, Duration
            val durationText = detail.durationMin?.let(::formatDurationCompact)
                ?: detail.durationStr?.takeIf { it.isNotBlank() }
                ?: notAvailableLabel

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                DetailTimeValue(
                    label = startedLabel,
                    value = formatDateTime(detail.startDate, unknownLabel, is24Hour),
                    modifier = Modifier.weight(1f).testTag("charge-detail-start")
                )
                DetailTimeValue(
                    label = endedLabel,
                    value = formatDateTime(detail.endDate, unknownLabel, is24Hour),
                    modifier = Modifier.weight(1f).testTag("charge-detail-end")
                )
                DetailTimeValue(
                    label = durationLabel,
                    value = durationText,
                    modifier = Modifier.weight(1f).testTag("charge-detail-duration")
                )
            }

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
            )

            val partialEnergy = detail.partialBatteryInputKwh
            val energy = stats?.energyAdded ?: detail.batteryInputKwh ?: partialEnergy
            val energyPrefix = if (stats?.energyAdded == null && partialEnergy != null) "≈" else ""
            val energyValue = energy?.let { "$energyPrefix%.2f".format(it) } ?: notAvailableLabel

            val startSoc = stats?.batteryStart ?: detail.startBatteryLevel
            val endSoc = stats?.batteryEnd ?: detail.endBatteryLevel
            val socValue = when {
                startSoc != null && endSoc != null -> "$startSoc% → $endSoc%"
                startSoc != null -> "$startSoc%"
                endSoc != null -> "$endSoc%"
                else -> notAvailableLabel
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ChargeStatCapsule(
                    label = stringResource(if (energyPrefix.isNotEmpty()) R.string.charge_partial_energy else R.string.energy_added),
                    value = energyValue,
                    unit = "kWh",
                    valueColor = Color(0xFF22C55E),
                    modifier = Modifier.weight(1f).testTag("charge-detail-energy")
                )
                ChargeStatCapsule(
                    label = stringResource(R.string.battery_level),
                    value = socValue,
                    unit = "",
                    valueColor = Color(0xFF0284C7),
                    modifier = Modifier.weight(1f).testTag("charge-detail-soc")
                )
                ChargeStatCapsule(
                    label = stringResource(R.string.cost),
                    value = costText,
                    unit = "",
                    valueColor = Color(0xFFD97706),
                    modifier = Modifier.weight(1f).testTag("charge-detail-cost"),
                    onNotice = onCostNotice,
                    onEditValue = onEditPrice
                )
            }
        }
    }
}

@Composable
private fun ChargeStatCapsule(
    label: String,
    value: String,
    unit: String,
    valueColor: Color,
    modifier: Modifier = Modifier,
    onNotice: (() -> Unit)? = null,
    onEditValue: (() -> Unit)? = null
) {
    val unitColor = MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier = modifier.sizeIn(minHeight = 48.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        if (onNotice != null) ChargeCostNoticeMark(onNotice)
        }
        val valueModifier = if (onEditValue == null) Modifier else Modifier.testTag("charge-detail-edit-total")
            .clickable(onClickLabel = stringResource(R.string.edit_charge_cost), role = Role.Button, onClick = onEditValue)
        Text(
            text = buildAnnotatedString {
                append(value)
                if (unit.isNotBlank()) withStyle(SpanStyle(color = unitColor, fontSize = 10.sp, fontWeight = FontWeight.Normal)) {
                    append(" $unit")
                }
            },
            style = MaterialTheme.typography.titleSmall,
            modifier = valueModifier,
            fontWeight = FontWeight.Bold,
            color = valueColor,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
internal fun ChargeCostNoticeMark(onClick: () -> Unit) {
    val label = stringResource(R.string.charge_cost_basis)
    Box(Modifier.size(24.dp).testTag("charge_cost_basis").clickable(
        onClickLabel = label, role = Role.Button, onClick = onClick)
        .semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        Text("!", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.background(MaterialTheme.colorScheme.tertiaryContainer, CircleShape)
                .padding(horizontal = 4.dp))
    }
}

internal fun chargeEstimateBasisResource(reason: String?): Int = when (reason) {
    "tariff_time_weighted_app_zone" -> R.string.charge_estimate_tiered_basis
    "estimate_partial_counter" -> R.string.charge_estimate_partial_basis
    else -> R.string.charge_estimate_default_basis
}

@Composable
private fun DetailTimeValue(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.sizeIn(minHeight = 48.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
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
            Column(Modifier.verticalScroll(rememberScrollState())) {
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
    val series = remember(samples) {
        com.matelink.ui.components.projectTimedChartSeries(samples.mapIndexed { index, (time, value) ->
            com.matelink.ui.components.TimedChartSample(time.toEpochMilli(), value, index)
        }, maxGapMs = 5 * 60 * 1000L)
    }

    ChartCard(
        title = title,
        icon = Icons.Default.Bolt,
        data = powers,
        color = Color(0xFF4CAF50),
        unit = "kW",
        sampleTimes = samples.map { it.first },
        timedSeries = series,
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
    timedSeries: com.matelink.ui.components.TimedChartSeries? = null,
    is24Hour: Boolean = true,
    convertValue: (Float) -> Float = { it }
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
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
                xFractions = timedSeries?.xFractions.orEmpty(),
                sourceIndices = timedSeries?.sourceIndices.orEmpty(),
                gaps = timedSeries?.gaps.orEmpty(),
                timeLabels = if (timedSeries == null) extractTimeLabels(sampleTimes, is24Hour)
                    else listOf(0f, .25f, .5f, .75f, 1f).map { fraction ->
                        timedSeries.axisTimestampAtFraction(fraction)?.let {
                            formatObservationTime(Instant.ofEpochMilli(it), is24Hour)
                        }.orEmpty()
                    },
                convertValue = convertValue,
                fractionToTimeLabel = { fraction ->
                    timedSeries?.nearestTimestampAtFraction(fraction)?.let {
                        formatObservationTime(Instant.ofEpochMilli(it), is24Hour)
                    } ?: timeAtFraction(sampleTimes, fraction, is24Hour)
                },
                modifier = Modifier.fillMaxWidth()
            )
            if (timedSeries?.hasInternalBreak == true) Text(
                stringResource(R.string.curve_gap_explanation),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
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

internal data class PendingChargeAmount(val amount: Double, val currencySymbol: String?)

internal fun pendingChargeAmountForDisplay(
    hasVerifiedManual: Boolean, bound: Pair<String?, Double>?, legacy: Double?
): PendingChargeAmount? {
    if (hasVerifiedManual) return null
    if (bound != null && bound.second.isFinite() && bound.second >= 0.0) {
        val symbol = Currency.entries.firstOrNull { it.code.equals(bound.first?.trim(), ignoreCase = true) }?.symbol
        return PendingChargeAmount(bound.second, symbol)
    }
    return legacy?.takeIf { it.isFinite() && it >= 0.0 }?.let { PendingChargeAmount(it, null) }
}

internal data class ChargeUnitCost(val amount: Double, val estimated: Boolean)

internal fun chargeUnitCost(
    cost: ChargeDetailCostPresentation, fullEnergy: Double?, partialEnergy: Double?
): ChargeUnitCost? {
    val total = cost.cost?.takeIf { it.isFinite() && it >= 0.0 } ?: return null
    if (cost.state == ChargeDetailCostState.UNAVAILABLE) return null
    val energy = if (fullEnergy != null) fullEnergy.takeIf { it.isFinite() && it > 0.0 }
        else partialEnergy?.takeIf {
            cost.state == ChargeDetailCostState.ESTIMATE && cost.qualificationReason == "estimate_partial_counter" &&
                it.isFinite() && it > 0.0
        }
    energy ?: return null
    val value = (total / energy).takeIf(Double::isFinite) ?: return null
    return ChargeUnitCost(value, cost.state == ChargeDetailCostState.ESTIMATE)
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
