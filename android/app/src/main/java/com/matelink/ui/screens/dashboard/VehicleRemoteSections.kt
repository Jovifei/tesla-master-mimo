package com.matelink.ui.screens.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.EvStation
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Percent
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Window
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.matelink.R
import com.matelink.data.api.models.CarStatus
import com.matelink.ui.components.TelemetryPanel
import kotlin.math.roundToInt

/**
 * Dashboard sections for the Fleet expansion (design 2026-11-05 §5–§7):
 * vehicle controls, nearby charging and named places. All gating lives
 * on the backend; these composables only shape what is offered (via the
 * tested catalog rules) and report outcomes honestly — an accepted but
 * unverified command is never dressed up as confirmed.
 */

private fun commandLabelRes(name: String): Int = when (name) {
    "lock" -> R.string.cmd_lock
    "unlock" -> R.string.cmd_unlock
    "flash" -> R.string.cmd_flash
    "honk" -> R.string.cmd_honk
    "climate_on" -> R.string.cmd_climate_on
    "climate_off" -> R.string.cmd_climate_off
    "set_climate_temp" -> R.string.cmd_set_climate_temp
    "charge_port_open" -> R.string.cmd_charge_port_open
    "charge_port_close" -> R.string.cmd_charge_port_close
    "charge_start" -> R.string.cmd_charge_start
    "charge_stop" -> R.string.cmd_charge_stop
    "set_charge_limit" -> R.string.cmd_set_charge_limit
    "windows_vent" -> R.string.cmd_windows_vent
    "windows_close" -> R.string.cmd_windows_close
    "sentry_on" -> R.string.cmd_sentry_on
    "sentry_off" -> R.string.cmd_sentry_off
    else -> R.string.cmd_generic
}

private fun commandIcon(name: String): ImageVector = when (name) {
    "lock" -> Icons.Default.Lock
    "unlock" -> Icons.Default.LockOpen
    "flash" -> Icons.Default.FlashOn
    "honk" -> Icons.Default.VolumeUp
    "climate_on", "climate_off" -> Icons.Default.AcUnit
    "set_climate_temp" -> Icons.Default.Thermostat
    "charge_port_open", "charge_port_close" -> Icons.Default.EvStation
    "charge_start" -> Icons.Default.PlayArrow
    "charge_stop" -> Icons.Default.Stop
    "set_charge_limit" -> Icons.Default.Percent
    "windows_vent", "windows_close" -> Icons.Default.Window
    "sentry_on", "sentry_off" -> Icons.Default.Security
    else -> Icons.Default.Refresh
}

private fun commandErrorRes(errorClass: String?): Int = when (errorClass) {
    "commands_disabled" -> R.string.cmd_err_commands_disabled
    "scope_missing" -> R.string.cmd_err_scope_missing
    "confirmation_required" -> R.string.cmd_err_confirmation_required
    "rate_limited" -> R.string.cmd_err_rate_limited
    "vehicle_moving" -> R.string.cmd_err_vehicle_moving
    "not_plugged_in" -> R.string.cmd_err_not_plugged_in
    "key_not_paired" -> R.string.cmd_err_key_not_paired
    "invalid_params" -> R.string.cmd_err_invalid_params
    else -> R.string.cmd_err_generic
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun VehicleControlsSection(
    carId: Int,
    status: CarStatus,
    onReauthorize: () -> Unit,
    viewModel: VehicleRemoteViewModel
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    if (needsCommandReauthorization(status)) {
        TelemetryPanel(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(stringResource(R.string.remote_reauth_title), style = MaterialTheme.typography.titleSmall)
                Text(
                    stringResource(R.string.remote_reauth_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(onClick = onReauthorize) { Text(stringResource(R.string.remote_reauth_action)) }
            }
        }
        return
    }

    val offered = offeredCommands(status)
    if (offered.isEmpty()) return

    var confirmUnlock by remember { mutableStateOf(false) }
    var showClimateDialog by remember { mutableStateOf(false) }
    var showChargeLimitDialog by remember { mutableStateOf(false) }
    val busy = state.busyCommand != null

    TelemetryPanel(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(stringResource(R.string.remote_controls_title), style = MaterialTheme.typography.titleSmall)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                offered.forEach { command ->
                    FilledTonalButton(
                        enabled = !busy,
                        onClick = {
                            when {
                                command.name == "set_climate_temp" -> showClimateDialog = true
                                command.name == "set_charge_limit" -> showChargeLimitDialog = true
                                command.needsConfirmation -> confirmUnlock = true
                                else -> viewModel.executeCommand(carId, command)
                            }
                        }
                    ) {
                        Icon(
                            imageVector = commandIcon(command.name),
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            stringResource(commandLabelRes(command.name)),
                            modifier = Modifier.padding(start = 6.dp)
                        )
                    }
                }
            }
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp))
            }
            when (val outcome = state.outcome) {
                is CommandOutcome.Accepted -> Text(
                    stringResource(
                        if (outcome.verified) R.string.cmd_accepted else R.string.cmd_accepted_unverified,
                        stringResource(commandLabelRes(outcome.command))
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                is CommandOutcome.Failed -> Text(
                    stringResource(commandErrorRes(outcome.errorClass)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
                null -> Unit
            }
        }
    }

    if (confirmUnlock) {
        AlertDialog(
            onDismissRequest = { confirmUnlock = false },
            title = { Text(stringResource(R.string.cmd_unlock_confirm_title)) },
            text = { Text(stringResource(R.string.cmd_unlock_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmUnlock = false
                    offered.firstOrNull { it.name == "unlock" }?.let {
                        viewModel.executeCommand(carId, it, confirm = true)
                    }
                }) { Text(stringResource(R.string.cmd_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmUnlock = false }) { Text(stringResource(R.string.cmd_cancel)) }
            }
        )
    }

    if (showClimateDialog) {
        val targets = climateTargets(status)
        var driver by remember { mutableFloatStateOf((targets?.driver ?: 21.0).toFloat()) }
        var passenger by remember { mutableFloatStateOf((targets?.passenger ?: 21.0).toFloat()) }
        AlertDialog(
            onDismissRequest = { showClimateDialog = false },
            title = { Text(stringResource(R.string.cmd_climate_dialog_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.cmd_driver_temp) + "  ${formatTemp(driver)}°C")
                    Slider(value = driver, onValueChange = { driver = it }, valueRange = 15f..28f, steps = 25)
                    Text(stringResource(R.string.cmd_passenger_temp) + "  ${formatTemp(passenger)}°C")
                    Slider(value = passenger, onValueChange = { passenger = it }, valueRange = 15f..28f, steps = 25)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showClimateDialog = false
                    offered.firstOrNull { it.name == "set_climate_temp" }?.let {
                        viewModel.executeCommand(
                            carId, it,
                            params = mapOf(
                                "driver_temp" to driver.toDouble(),
                                "passenger_temp" to passenger.toDouble()
                            )
                        )
                    }
                }) { Text(stringResource(R.string.cmd_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showClimateDialog = false }) { Text(stringResource(R.string.cmd_cancel)) }
            }
        )
    }

    if (showChargeLimitDialog) {
        var percent by remember { mutableFloatStateOf((status.chargeLimitSoc ?: 80).toFloat()) }
        AlertDialog(
            onDismissRequest = { showChargeLimitDialog = false },
            title = { Text(stringResource(R.string.cmd_charge_limit_dialog_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${percent.roundToInt()}%")
                    Slider(value = percent, onValueChange = { percent = it }, valueRange = 50f..100f, steps = 49)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showChargeLimitDialog = false
                    offered.firstOrNull { it.name == "set_charge_limit" }?.let {
                        viewModel.executeCommand(
                            carId, it,
                            params = mapOf("percent" to percent.roundToInt())
                        )
                    }
                }) { Text(stringResource(R.string.cmd_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showChargeLimitDialog = false }) { Text(stringResource(R.string.cmd_cancel)) }
            }
        )
    }
}

private fun formatTemp(value: Float): String =
    if (value % 1f == 0f) value.toInt().toString() else value.toString()

@Composable
internal fun NearbyChargingSection(
    carId: Int,
    viewModel: VehicleRemoteViewModel
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    TelemetryPanel(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(stringResource(R.string.nearby_title), style = MaterialTheme.typography.titleSmall)
                TextButton(onClick = { viewModel.loadNearby(carId) }) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text(
                        stringResource(R.string.nearby_load),
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }
            }
            when (val nearby = state.nearby) {
                NearbyState.NotLoaded -> Text(
                    stringResource(R.string.nearby_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                NearbyState.Loading -> CircularProgressIndicator(modifier = Modifier.size(18.dp))
                NearbyState.Failed -> Text(
                    stringResource(R.string.nearby_failed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
                is NearbyState.Loaded -> {
                    if (nearby.sites.isEmpty()) {
                        Text(
                            stringResource(R.string.nearby_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        nearby.sites.forEach { site ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    Icons.Default.EvStation,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        site.name ?: stringResource(R.string.nearby_unnamed),
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Text(
                                        stringResource(
                                            if (site.isSupercharger) R.string.nearby_supercharger
                                            else R.string.nearby_destination
                                        ) + (site.stallsText?.let { " · $it" } ?: ""),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (site.siteClosed == true) {
                                    Text(
                                        stringResource(R.string.nearby_closed),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                                site.distanceKm?.let { km ->
                                    Text(
                                        stringResource(R.string.nearby_distance, km),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        }
                    }
                    if (nearby.source == "cache") {
                        Text(
                            stringResource(R.string.nearby_cached),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun VehiclePlacesSection(
    carId: Int,
    status: CarStatus,
    viewModel: VehicleRemoteViewModel
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(carId) { viewModel.loadPlaces(carId) }

    var showAddDialog by remember { mutableStateOf(false) }
    var pendingDeleteId by remember { mutableStateOf<Long?>(null) }
    var pendingDeleteName by remember { mutableStateOf("") }

    TelemetryPanel(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(stringResource(R.string.places_title), style = MaterialTheme.typography.titleSmall)
                TextButton(onClick = { viewModel.loadPlaces(carId) }) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                }
            }
            when (val places = state.places) {
                PlacesState.NotLoaded, PlacesState.Loading ->
                    CircularProgressIndicator(modifier = Modifier.size(18.dp))
                PlacesState.Failed -> Text(
                    stringResource(R.string.places_failed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
                is PlacesState.Loaded -> {
                    if (places.places.isEmpty()) {
                        Text(
                            stringResource(R.string.places_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        places.places.forEach { place ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    Icons.Default.LocationOn,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(place.name ?: "", style = MaterialTheme.typography.bodyMedium)
                                    place.radiusM?.let { radius ->
                                        Text(
                                            stringResource(R.string.places_radius, radius),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                IconButton(onClick = {
                                    pendingDeleteId = place.id
                                    pendingDeleteName = place.name ?: ""
                                }) {
                                    Icon(Icons.Default.Delete, contentDescription = null)
                                }
                            }
                        }
                    }
                }
            }
            if (status.latitude != null && status.longitude != null) {
                Button(onClick = { showAddDialog = true }) {
                    Text(stringResource(R.string.places_add_here))
                }
            } else {
                Text(
                    stringResource(R.string.places_no_position),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    if (showAddDialog) {
        var name by remember { mutableStateOf("") }
        var radius by remember { mutableFloatStateOf(200f) }
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text(stringResource(R.string.places_add_here)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text(stringResource(R.string.places_name_label)) },
                        singleLine = true
                    )
                    Text(stringResource(R.string.places_radius, radius.roundToInt()))
                    Slider(value = radius, onValueChange = { radius = it }, valueRange = 50f..2000f, steps = 38)
                }
            },
            confirmButton = {
                TextButton(
                    enabled = name.isNotBlank(),
                    onClick = {
                        val lat = status.latitude
                        val lng = status.longitude
                        showAddDialog = false
                        if (lat != null && lng != null) {
                            viewModel.createPlace(carId, name.trim(), lat, lng, radius.roundToInt())
                        }
                    }
                ) { Text(stringResource(R.string.places_add)) }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) { Text(stringResource(R.string.cmd_cancel)) }
            }
        )
    }

    pendingDeleteId?.let { placeId ->
        AlertDialog(
            onDismissRequest = { pendingDeleteId = null },
            title = { Text(stringResource(R.string.places_delete_confirm, pendingDeleteName)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingDeleteId = null
                    viewModel.deletePlace(carId, placeId)
                }) { Text(stringResource(R.string.cmd_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteId = null }) { Text(stringResource(R.string.cmd_cancel)) }
            }
        )
    }
}
