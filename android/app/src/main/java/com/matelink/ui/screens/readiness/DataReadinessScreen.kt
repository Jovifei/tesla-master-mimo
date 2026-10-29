package com.matelink.ui.screens.readiness

import android.content.Intent
import android.content.ActivityNotFoundException
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.EvStation
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.TireRepair
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.matelink.R
import com.matelink.data.api.models.DataReadiness
import com.matelink.data.api.models.DataReadinessItem
import com.matelink.ui.components.MateLinkLoadingPlaceholder

private val readinessKeys = listOf(
    "live_status",
    "location",
    "tpms",
    "drives",
    "charges",
    "battery_health"
)

private fun titleResForKey(key: String): Int = when (key) {
    "live_status" -> R.string.data_readiness_item_live_status
    "location" -> R.string.data_readiness_item_location
    "tpms" -> R.string.data_readiness_item_tpms
    "drives" -> R.string.data_readiness_item_drives
    "charges" -> R.string.data_readiness_item_charges
    "battery_health" -> R.string.data_readiness_item_battery_health
    else -> R.string.data_readiness_title
}

private fun readinessIcon(key: String): ImageVector = when (key) {
    "live_status" -> Icons.Filled.Sensors
    "location" -> Icons.Filled.LocationOn
    "tpms" -> Icons.Filled.TireRepair
    "drives" -> Icons.Filled.DirectionsCar
    "charges" -> Icons.Filled.EvStation
    "battery_health" -> Icons.Filled.BatteryChargingFull
    else -> Icons.Filled.Info
}

@Composable
private fun ReadinessIcon(icon: ImageVector, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.size(42.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.size(23.dp))
    }
}

@Composable
private fun StatusBadge(label: String, background: Color, foreground: Color) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        color = foreground,
        modifier = Modifier.background(background, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp)
    )
}

@Composable
private fun ReadinessOverview(readiness: DataReadiness?) {
    val items = readinessKeys.map { readiness?.item(it) }
    val reported = items.count { it != null }
    val available = items.count { it != null && readinessItemStatus(it) == ReadinessItemStatus.AVAILABLE }
    val unknown = items.count { it == null || readinessItemStatus(it) == ReadinessItemStatus.UNKNOWN }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ReadinessIcon(Icons.Filled.Sensors, Modifier.size(48.dp))
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.visual_status_overview_title),
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(stringResource(R.string.data_readiness_intro_body),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OverviewCount("$reported/${readinessKeys.size}", R.string.visual_status_reported, Modifier.weight(1f))
                OverviewCount(available.toString(), R.string.visual_status_available, Modifier.weight(1f))
                OverviewCount(unknown.toString(), R.string.visual_status_unknown, Modifier.weight(1f))
            }
            Text(stringResource(R.string.visual_status_overview_body),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun OverviewCount(value: String, labelRes: Int, modifier: Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(value, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
        Text(stringResource(labelRes), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ReadinessEvidenceLine(icon: ImageVector, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
    }
}

private fun actionResFor(item: DataReadinessItem): Int? = when (item.action) {
    "wake_vehicle" -> R.string.data_readiness_action_wake_vehicle
    "keep_vehicle_connected" -> R.string.data_readiness_action_keep_vehicle_connected
    "not_available" -> R.string.data_readiness_action_not_available
    "retry_later" -> R.string.data_readiness_action_retry_later
    "reauthorize_tesla", "pair_tesla" -> R.string.tesla_account_reauthorize
    "none" -> if (item.messageKey == "data_readiness_legacy_compatibility") {
        R.string.data_readiness_action_legacy
    } else null
    else -> null
}

@Composable
private fun localizedStatus(item: DataReadinessItem): String = when (readinessItemStatus(item)) {
    ReadinessItemStatus.AVAILABLE -> stringResource(R.string.data_readiness_status_available)
    ReadinessItemStatus.COLLECTING -> stringResource(R.string.data_readiness_status_collecting)
    ReadinessItemStatus.WAITING_VEHICLE -> stringResource(R.string.data_readiness_status_waiting_vehicle)
    ReadinessItemStatus.PERMISSION_REQUIRED -> stringResource(R.string.telemetry_setup_permission_required)
    ReadinessItemStatus.UNSUPPORTED -> stringResource(R.string.data_readiness_status_unsupported)
    ReadinessItemStatus.UNKNOWN -> stringResource(R.string.data_readiness_status_unavailable)
}

private fun telemetrySetupLabelRes(presentation: TelemetrySetupPresentation): Int = when (presentation) {
    TelemetrySetupPresentation.PAIRING_REQUIRED -> R.string.telemetry_setup_pairing_required
    TelemetrySetupPresentation.WAITING_VEHICLE -> R.string.telemetry_setup_waiting_vehicle
    TelemetrySetupPresentation.PERMISSION_REQUIRED -> R.string.telemetry_setup_permission_required
    TelemetrySetupPresentation.BILLING_BLOCKED -> R.string.telemetry_setup_billing_blocked
    TelemetrySetupPresentation.TELEMETRY_ERROR -> R.string.telemetry_setup_error
    TelemetrySetupPresentation.TELEMETRY_NOT_CONFIGURED -> R.string.telemetry_setup_not_configured
    TelemetrySetupPresentation.COLLECTING -> R.string.telemetry_setup_collecting
    TelemetrySetupPresentation.AVAILABLE -> R.string.telemetry_setup_available
}

private fun telemetryConfigSyncLabelRes(presentation: TelemetryConfigSyncPresentation): Int = when (presentation) {
    TelemetryConfigSyncPresentation.SYNCED -> R.string.telemetry_config_synced
    TelemetryConfigSyncPresentation.PENDING -> R.string.telemetry_config_pending
    TelemetryConfigSyncPresentation.UNKNOWN -> R.string.telemetry_config_unknown
}

@Composable
private fun FleetTelemetryCard(
    state: DataReadinessUiState,
    onOpenVirtualKey: () -> Unit,
    onConfigure: () -> Unit,
    onReauthorize: () -> Unit
) {
    val rawStatus = state.telemetryErrorCode ?: state.pairing?.status
    val mappedPresentation = telemetrySetupPresentation(rawStatus, state.pairing?.configSynced)
    val configureAction = telemetryConfigureActionPresentation(rawStatus, state.pairing?.configSynced)
    val presentation = if (
        state.isTelemetryActivationPending &&
        state.telemetryErrorCode == null &&
        state.pairing?.configSynced != true
    ) {
        TelemetrySetupPresentation.WAITING_VEHICLE
    } else {
        mappedPresentation
    }
    val configSync = telemetryConfigSyncPresentation(state.pairing?.configSynced)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ReadinessIcon(Icons.Filled.CloudSync)
                Text(
                    text = stringResource(R.string.telemetry_setup_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
            }
            StatusBadge(
                label = stringResource(telemetrySetupLabelRes(presentation)),
                background = when (presentation) {
                    TelemetrySetupPresentation.AVAILABLE -> MaterialTheme.colorScheme.primaryContainer
                    TelemetrySetupPresentation.TELEMETRY_ERROR -> MaterialTheme.colorScheme.errorContainer
                    else -> MaterialTheme.colorScheme.surfaceVariant
                },
                foreground = when (presentation) {
                    TelemetrySetupPresentation.AVAILABLE -> MaterialTheme.colorScheme.onPrimaryContainer
                    TelemetrySetupPresentation.TELEMETRY_ERROR -> MaterialTheme.colorScheme.onErrorContainer
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
            Text(
                text = stringResource(
                    R.string.telemetry_config_sync_state,
                    stringResource(telemetryConfigSyncLabelRes(configSync))
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            state.pairing?.updatedAt?.takeIf(String::isNotBlank)?.let {
                Text(
                    text = stringResource(R.string.data_readiness_last_observed, it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            when (presentation) {
                TelemetrySetupPresentation.PAIRING_REQUIRED -> {
                    Button(onClick = onOpenVirtualKey, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.telemetry_action_open_virtual_key))
                    }
                    if (configureAction == TelemetryConfigureActionPresentation.CONFIGURE) {
                        OutlinedButton(
                            onClick = onConfigure,
                            enabled = !state.isConfiguringTelemetry,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.telemetry_action_configure))
                        }
                    }
                }
                TelemetrySetupPresentation.PERMISSION_REQUIRED -> {
                    Button(onClick = onReauthorize, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.tesla_account_reauthorize))
                    }
                }
                else -> if (configureAction == TelemetryConfigureActionPresentation.CONFIGURE) {
                    OutlinedButton(
                        onClick = onConfigure,
                        enabled = !state.isConfiguringTelemetry,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.telemetry_action_retry_configuration))
                    }
                }
            }
            if (state.pairingLinkUnavailable) {
                Text(
                    text = stringResource(R.string.telemetry_virtual_key_unavailable),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
fun DataReadinessRows(
    readiness: DataReadiness,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        readinessKeys.forEach { key ->
            readiness.item(key)?.let { item ->
                DataReadinessItemRow(item, titleResForKey(key), readinessIcon(key))
            } ?: DataReadinessItemRow(
                item = null,
                titleRes = titleResForKey(key),
                icon = readinessIcon(key)
            )
        }
    }
}

@Composable
private fun DataReadinessItemRow(item: DataReadinessItem?, titleRes: Int, icon: ImageVector) {
    val status = item?.let(::readinessItemStatus) ?: ReadinessItemStatus.UNKNOWN
    val statusBackground = when (status) {
        ReadinessItemStatus.AVAILABLE -> MaterialTheme.colorScheme.primaryContainer
        ReadinessItemStatus.COLLECTING, ReadinessItemStatus.WAITING_VEHICLE,
        ReadinessItemStatus.PERMISSION_REQUIRED -> MaterialTheme.colorScheme.tertiaryContainer
        ReadinessItemStatus.UNSUPPORTED, ReadinessItemStatus.UNKNOWN -> MaterialTheme.colorScheme.surfaceVariant
    }
    val statusForeground = when (status) {
        ReadinessItemStatus.AVAILABLE -> MaterialTheme.colorScheme.onPrimaryContainer
        ReadinessItemStatus.COLLECTING, ReadinessItemStatus.WAITING_VEHICLE,
        ReadinessItemStatus.PERMISSION_REQUIRED -> MaterialTheme.colorScheme.onTertiaryContainer
        ReadinessItemStatus.UNSUPPORTED, ReadinessItemStatus.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ReadinessIcon(icon)
                Text(
                    text = stringResource(titleRes),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
            }
            StatusBadge(
                label = item?.let { localizedStatus(it) } ?: stringResource(R.string.visual_status_unknown),
                background = statusBackground,
                foreground = statusForeground
            )
            item?.let { readinessItem ->
                actionResFor(readinessItem)?.let { actionRes ->
                    Text(
                        text = stringResource(actionRes),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            ReadinessEvidenceLine(
                Icons.Filled.CloudSync,
                stringResource(R.string.data_readiness_source,
                    item?.source?.takeIf(String::isNotBlank)?.let { stringResource(readinessSourceLabelRes(it)) }
                        ?: stringResource(R.string.visual_status_unknown))
            )
            ReadinessEvidenceLine(
                Icons.Filled.Schedule,
                stringResource(R.string.data_readiness_last_observed,
                    item?.lastObservedAt?.takeIf(String::isNotBlank) ?: stringResource(R.string.visual_status_unknown))
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DataReadinessScreen(
    carId: Int,
    onNavigateBack: () -> Unit,
    onReauthorize: () -> Unit = {},
    viewModel: DataReadinessViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var showMigrationDialog by remember { mutableStateOf(false) }
    var showBindingDialog by remember { mutableStateOf(false) }
    LaunchedEffect(carId) { viewModel.setCarId(carId) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> viewModel.onScreenPaused()
                Lifecycle.Event.ON_RESUME -> viewModel.onScreenResumed()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.onScreenPaused()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.data_readiness_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                }
            )
        }
    ) { padding ->
        when {
            uiState.isLoading -> MateLinkLoadingPlaceholder(modifier = Modifier.padding(padding))
            else -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                ReadinessDiagnosticCards(uiState.diagnosticFacts)
                Text(stringResource(R.string.diagnostic_server_capabilities), style = MaterialTheme.typography.titleMedium)
                ReadinessOverview(uiState.data)
                uiState.data?.let { DataReadinessRows(it) } ?: Text(
                    text = stringResource(R.string.data_readiness_load_error),
                    color = MaterialTheme.colorScheme.error
                )
                FleetTelemetryCard(
                    state = uiState,
                    onOpenVirtualKey = {
                        viewModel.openExternalPairing { officialUrl ->
                            try {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(officialUrl)))
                                true
                            } catch (_: ActivityNotFoundException) {
                                false
                            } catch (_: SecurityException) {
                                false
                            }
                        }
                    },
                    onConfigure = viewModel::configureTelemetry,
                    onReauthorize = onReauthorize
                )
                if (uiState.migrationBindingRequired) {
                    OutlinedButton(
                        onClick = { showBindingDialog = true },
                        enabled = !uiState.isMigrating,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.data_readiness_bind_legacy_archive))
                    }
                    if (showBindingDialog) {
                        AlertDialog(
                            onDismissRequest = { showBindingDialog = false },
                            title = { Text(stringResource(R.string.data_readiness_bind_legacy_title)) },
                            text = { Text(stringResource(R.string.data_readiness_bind_legacy_message)) },
                            confirmButton = {
                                Button(onClick = {
                                    showBindingDialog = false
                                    viewModel.recordExplicitUpgradeOrigin()
                                }) { Text(stringResource(R.string.data_readiness_bind_legacy_confirm)) }
                            },
                            dismissButton = {
                                OutlinedButton(onClick = { showBindingDialog = false }) {
                                    Text(stringResource(R.string.cancel))
                                }
                            }
                        )
                    }
                }
                uiState.migrationEligibility?.takeIf { it.eligible }?.let { eligibility ->
                    OutlinedButton(
                        onClick = { showMigrationDialog = true },
                        enabled = !uiState.isMigrating,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.data_readiness_migrate_legacy))
                    }
                    Text(
                        text = stringResource(
                            R.string.data_readiness_migrate_excluded_note
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (uiState.migrationComplete) {
                        Text(
                            text = stringResource(R.string.data_readiness_migrate_complete),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    if (showMigrationDialog) {
                        AlertDialog(
                            onDismissRequest = { showMigrationDialog = false },
                            title = { Text(stringResource(R.string.data_readiness_migrate_title)) },
                            text = {
                                Text(
                                    stringResource(
                                        R.string.data_readiness_migrate_message,
                                        eligibility.legacyDriveCount,
                                        eligibility.legacyChargeCount,
                                        uiState.targetVehicleName.orEmpty()
                                    )
                                )
                            },
                            confirmButton = {
                                Button(onClick = {
                                    showMigrationDialog = false
                                    viewModel.migrateLegacyHistory()
                                }) { Text(stringResource(R.string.data_readiness_migrate_confirm)) }
                            },
                            dismissButton = {
                                OutlinedButton(onClick = { showMigrationDialog = false }) {
                                    Text(stringResource(R.string.cancel))
                                }
                            }
                        )
                    }
                }
                OutlinedButton(onClick = viewModel::refresh, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.refresh))
                }
            }
        }
    }
}
