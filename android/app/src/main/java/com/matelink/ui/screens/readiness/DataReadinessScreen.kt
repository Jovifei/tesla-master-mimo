package com.matelink.ui.screens.readiness

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
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

private fun actionResFor(item: DataReadinessItem): Int? = when (item.action) {
    "wake_vehicle" -> R.string.data_readiness_action_wake_vehicle
    "keep_vehicle_connected" -> R.string.data_readiness_action_keep_vehicle_connected
    "not_available" -> R.string.data_readiness_action_not_available
    "retry_later" -> R.string.data_readiness_action_retry_later
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
    ReadinessItemStatus.UNSUPPORTED -> stringResource(R.string.data_readiness_status_unsupported)
    ReadinessItemStatus.UNKNOWN -> stringResource(R.string.data_readiness_status_unavailable)
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
                DataReadinessItemRow(item, titleResForKey(key))
            } ?: DataReadinessItemRow(
                item = null,
                titleRes = titleResForKey(key)
            )
        }
    }
}

@Composable
private fun DataReadinessItemRow(item: DataReadinessItem?, titleRes: Int) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(titleRes),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = if (item != null) {
                        localizedStatus(item)
                    } else {
                        stringResource(R.string.data_readiness_status_waiting_vehicle)
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            item?.let { readinessItem ->
                actionResFor(readinessItem)?.let { actionRes ->
                    Text(
                        text = stringResource(actionRes),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                readinessItem.lastObservedAt?.takeIf(String::isNotBlank)?.let {
                    Text(
                        text = stringResource(R.string.data_readiness_last_observed, it),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                readinessItem.source.takeIf(String::isNotBlank)?.let {
                    Text(
                        text = stringResource(
                            R.string.data_readiness_source,
                            stringResource(readinessSourceLabelRes(it))
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DataReadinessScreen(
    carId: Int,
    onNavigateBack: () -> Unit,
    viewModel: DataReadinessViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var showMigrationDialog by remember { mutableStateOf(false) }
    var showBindingDialog by remember { mutableStateOf(false) }
    LaunchedEffect(carId) { viewModel.setCarId(carId) }

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
            uiState.data != null -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    text = stringResource(R.string.data_readiness_intro_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                DataReadinessRows(uiState.data!!)
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
            else -> Column(
                modifier = Modifier.fillMaxWidth().padding(padding).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(stringResource(R.string.data_readiness_load_error))
                Button(onClick = viewModel::refresh) { Text(stringResource(R.string.refresh)) }
            }
        }
    }
}
