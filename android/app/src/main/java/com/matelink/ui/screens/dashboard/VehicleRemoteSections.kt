package com.matelink.ui.screens.dashboard

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.LocalParking
import androidx.compose.material.icons.filled.Paid
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.ErrorOutline
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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.matelink.R
import com.matelink.data.api.models.CarStatus
import com.matelink.data.api.models.ChargingSite
import com.matelink.ui.components.TelemetryPanel
import kotlin.math.roundToInt

/**
 * Dashboard sections for the Fleet expansion (design 2026-11-05 §5–§7):
 * vehicle controls, nearby charging and named places. All gating lives
 * on the backend; these composables shape actions into a refined automotive
 * control hub and report outcomes honestly.
 */

internal fun commandLabelRes(name: String): Int = when (name) {
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

@Composable
private fun RemoteActionButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    busy: Boolean = false,
    enabled: Boolean = true,
    warning: Boolean = false
) {
    val containerColor = when {
        warning && active -> MaterialTheme.colorScheme.errorContainer
        active -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surface
    }
    val contentColor = when {
        warning && active -> MaterialTheme.colorScheme.onErrorContainer
        active -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurface
    }
    val borderColor = when {
        warning && active -> MaterialTheme.colorScheme.error.copy(alpha = 0.5f)
        active -> MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
        else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
    }

    Surface(
        onClick = onClick,
        enabled = enabled && !busy,
        shape = RoundedCornerShape(12.dp),
        color = containerColor,
        contentColor = contentColor,
        border = BorderStroke(0.8.dp, borderColor),
        modifier = modifier.heightIn(min = 44.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = contentColor
                )
            } else {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(17.dp),
                    tint = contentColor
                )
            }
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun TemperatureStepper(
    currentTemp: Double,
    onAdjust: (Double) -> Unit,
    onClickDisplay: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(0.8.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
        modifier = modifier.heightIn(min = 44.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            IconButton(
                onClick = { onAdjust((currentTemp - 0.5).coerceAtLeast(15.0)) },
                enabled = enabled,
                modifier = Modifier.size(34.dp)
            ) {
                Text(
                    text = "−",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(enabled = enabled, onClick = onClickDisplay)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Thermostat,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(3.dp))
                Text(
                    text = "${formatTemp(currentTemp.toFloat())}°C",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            IconButton(
                onClick = { onAdjust((currentTemp + 0.5).coerceAtMost(28.0)) },
                enabled = enabled,
                modifier = Modifier.size(34.dp)
            ) {
                Text(
                    text = "+",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun VehicleQuickStatusAndControlSheet(
    carId: Int,
    status: CarStatus,
    onReauthorize: () -> Unit,
    viewModel: VehicleRemoteViewModel
) {
    var showSheet by remember { mutableStateOf(false) }
    var confirmCommand by remember { mutableStateOf<String?>(null) }
    var confirmTitle by remember { mutableStateOf("") }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val isLocked = status.locked ?: true
    val isClimateOn = status.isClimateOn ?: false
    val isSentryOn = status.sentryMode ?: false
    val climateTarget = status.driverTempSetting ?: 21.0

    // 1. Sleek Apple-style Status Pill Strip on Homepage
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Lock Pill - directly toggles with confirmation
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = if (isLocked) MaterialTheme.colorScheme.surfaceContainerHigh else Color(0xFFEF4444).copy(alpha = 0.15f),
            border = BorderStroke(0.5.dp, if (isLocked) MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f) else Color(0xFFEF4444).copy(alpha = 0.4f)),
            onClick = {
                if (isLocked) {
                    confirmTitle = "确定要解锁车辆吗？"
                    confirmCommand = "unlock"
                } else {
                    confirmTitle = "确定要锁闭车辆吗？"
                    confirmCommand = "lock"
                }
            }
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Icon(
                    imageVector = if (isLocked) Icons.Default.Lock else Icons.Default.LockOpen,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = if (isLocked) MaterialTheme.colorScheme.onSurfaceVariant else Color(0xFFEF4444)
                )
                Text(
                    text = if (isLocked) "已上锁" else "未上锁",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    color = if (isLocked) MaterialTheme.colorScheme.onSurface else Color(0xFFEF4444)
                )
            }
        }

        // Climate Pill - directly toggles with confirmation
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = if (isClimateOn) Color(0xFF0284C7).copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceContainerHigh,
            border = BorderStroke(0.5.dp, if (isClimateOn) Color(0xFF0284C7).copy(alpha = 0.4f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
            onClick = {
                if (isClimateOn) {
                    confirmTitle = "确定要关闭车内空调吗？"
                    confirmCommand = "climate_off"
                } else {
                    confirmTitle = "确定要开启车内空调吗？(设定目标 %.1f°C)".format(climateTarget)
                    confirmCommand = "climate_on"
                }
            }
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.AcUnit,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = if (isClimateOn) Color(0xFF0284C7) else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = if (isClimateOn) "空调 %.1f°C".format(climateTarget) else "空调关闭",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    color = if (isClimateOn) Color(0xFF0284C7) else MaterialTheme.colorScheme.onSurface
                )
            }
        }

        // Sentry Pill - directly toggles with confirmation
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = if (isSentryOn) Color(0xFF10B981).copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceContainerHigh,
            border = BorderStroke(0.5.dp, if (isSentryOn) Color(0xFF10B981).copy(alpha = 0.4f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
            onClick = {
                if (isSentryOn) {
                    confirmTitle = "确定要关闭哨兵模式吗？"
                    confirmCommand = "sentry_off"
                } else {
                    confirmTitle = "确定要开启哨兵模式吗？"
                    confirmCommand = "sentry_on"
                }
            }
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Security,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = if (isSentryOn) Color(0xFF10B981) else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = if (isSentryOn) "哨兵开启" else "哨兵关闭",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    color = if (isSentryOn) Color(0xFF10B981) else MaterialTheme.colorScheme.onSurface
                )
            }
        }

        // Control Panel Entry Pill
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
            border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)),
            onClick = { showSheet = true }
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.DirectionsCar,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "车辆控制 >",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }

    // 2. Apple-Style Modal Bottom Sheet (Prevents accidental clicks on homepage)
        if (confirmCommand != null) {
        AlertDialog(
            onDismissRequest = { confirmCommand = null },
            title = { Text(confirmTitle, fontWeight = FontWeight.Bold) },
            text = { Text("防误触保护已就绪，点击确认后指令将立即送达车辆。") },
            confirmButton = {
                Button(
                    onClick = {
                        val cmdName = confirmCommand!!
                        vehicleCommandCatalog.firstOrNull { it.name == cmdName }?.let { cmd ->
                            viewModel.executeCommand(carId, cmd)
                        }
                        confirmCommand = null
                    }
                ) {
                    Text("确认执行")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmCommand = null }) {
                    Text("取消")
                }
            }
        )
    }

    if (showSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSheet = false },
            sheetState = sheetState,
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 32.dp)
            ) {
                VehicleControlsSection(
                    carId = carId,
                    status = status,
                    onReauthorize = {
                        showSheet = false
                        onReauthorize()
                    },
                    viewModel = viewModel
                )
            }
        }
    }
}

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
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        stringResource(R.string.remote_reauth_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(
                    stringResource(R.string.remote_reauth_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(
                    onClick = onReauthorize,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.remote_reauth_action))
                }
            }
        }
        return
    }

    val offered = offeredCommands(status).ifEmpty {
        if (status.commandAuthorization == null) vehicleCommandCatalog else emptyList()
    }
    if (offered.isEmpty()) return

    fun findCommand(name: String) = vehicleCommandCatalog.firstOrNull { it.name == name } ?: offered.firstOrNull { it.name == name }

    var confirmUnlock by remember { mutableStateOf(false) }
    var showClimateDialog by remember { mutableStateOf(false) }
    var showChargeLimitDialog by remember { mutableStateOf(false) }
    val busy = state.busyCommand != null

    val isLocked = status.locked ?: true
    val isClimateOn = status.isClimateOn ?: false
    val isPortOpen = status.chargePortDoorOpen ?: false
    val isCharging = status.chargingState?.lowercase() == "charging"
    val isSentryOn = status.sentryMode ?: false
    val isWindowsOpen = status.windowsOpen ?: false

    val climateTarget = status.driverTempSetting ?: 21.0

    TelemetryPanel(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Header: Title & Execution status
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.DirectionsCar,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        stringResource(R.string.remote_controls_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                if (busy) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = stringResource(R.string.cmd_executing),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            // Inline Feedback Pill
            AnimatedVisibility(
                visible = state.outcome != null,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                state.outcome?.let { outcome ->
                    val isSuccess = outcome is CommandOutcome.Accepted
                    val isVerified = (outcome as? CommandOutcome.Accepted)?.verified == true
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (isSuccess) {
                            if (isVerified) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                            else MaterialTheme.colorScheme.surfaceVariant
                        } else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f),
                        border = BorderStroke(
                            0.5.dp,
                            if (isSuccess) MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                            else MaterialTheme.colorScheme.error.copy(alpha = 0.3f)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = if (isSuccess) Icons.Default.CheckCircle else Icons.Default.ErrorOutline,
                                    contentDescription = null,
                                    modifier = Modifier.size(15.dp),
                                    tint = if (isSuccess) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                                )
                                Text(
                                    text = when (outcome) {
                                        is CommandOutcome.Accepted -> stringResource(
                                            if (outcome.verified) R.string.cmd_accepted else R.string.cmd_accepted_unverified,
                                            stringResource(commandLabelRes(outcome.command))
                                        )
                                        is CommandOutcome.Failed -> stringResource(commandErrorRes(outcome.errorClass))
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isSuccess) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                            IconButton(
                                onClick = { viewModel.consumeOutcome() },
                                modifier = Modifier.size(20.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = stringResource(R.string.close),
                                    modifier = Modifier.size(13.dp)
                                )
                            }
                        }
                    }
                }
            }

            // 1. Group: 门锁与寻车
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    stringResource(R.string.cmd_group_locks),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (isLocked) {
                        RemoteActionButton(
                            icon = Icons.Default.LockOpen,
                            label = stringResource(R.string.cmd_unlock),
                            active = false,
                            busy = state.busyCommand == "unlock",
                            enabled = !busy,
                            onClick = { confirmUnlock = true },
                            modifier = Modifier.weight(1.3f)
                        )
                    } else {
                        RemoteActionButton(
                            icon = Icons.Default.Lock,
                            label = stringResource(R.string.cmd_lock),
                            active = true,
                            busy = state.busyCommand == "lock",
                            enabled = !busy,
                            onClick = { findCommand("lock")?.let { viewModel.executeCommand(carId, it) } },
                            modifier = Modifier.weight(1.3f)
                        )
                    }
                    RemoteActionButton(
                        icon = Icons.Default.FlashOn,
                        label = stringResource(R.string.cmd_flash),
                        busy = state.busyCommand == "flash",
                        enabled = !busy,
                        onClick = { findCommand("flash")?.let { viewModel.executeCommand(carId, it) } },
                        modifier = Modifier.weight(1f)
                    )
                    RemoteActionButton(
                        icon = Icons.Default.VolumeUp,
                        label = stringResource(R.string.cmd_honk),
                        busy = state.busyCommand == "honk",
                        enabled = !busy,
                        onClick = { findCommand("honk")?.let { viewModel.executeCommand(carId, it) } },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))

            // 2. Group: 空调与车窗
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    stringResource(R.string.cmd_group_climate),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (isClimateOn) {
                        RemoteActionButton(
                            icon = Icons.Default.AcUnit,
                            label = stringResource(R.string.cmd_climate_off),
                            active = true,
                            busy = state.busyCommand in listOf("climate_on", "climate_off"),
                            enabled = !busy,
                            onClick = { findCommand("climate_off")?.let { viewModel.executeCommand(carId, it) } },
                            modifier = Modifier.weight(1.1f)
                        )
                    } else {
                        RemoteActionButton(
                            icon = Icons.Default.AcUnit,
                            label = stringResource(R.string.cmd_climate_on),
                            active = false,
                            busy = state.busyCommand in listOf("climate_on", "climate_off"),
                            enabled = !busy,
                            onClick = { findCommand("climate_on")?.let { viewModel.executeCommand(carId, it) } },
                            modifier = Modifier.weight(1.1f)
                        )
                    }
                    TemperatureStepper(
                        currentTemp = climateTarget,
                        enabled = !busy,
                        onAdjust = { newTemp ->
                            findCommand("set_climate_temp")?.let {
                                viewModel.executeCommand(
                                    carId, it,
                                    params = mapOf("driver_temp" to newTemp, "passenger_temp" to newTemp)
                                )
                            }
                        },
                        onClickDisplay = { showClimateDialog = true },
                        modifier = Modifier.weight(1.3f)
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    RemoteActionButton(
                        icon = Icons.Default.Window,
                        label = stringResource(R.string.cmd_windows_vent),
                        active = isWindowsOpen,
                        busy = state.busyCommand == "windows_vent",
                        enabled = !busy,
                        onClick = { findCommand("windows_vent")?.let { viewModel.executeCommand(carId, it) } },
                        modifier = Modifier.weight(1f)
                    )
                    RemoteActionButton(
                        icon = Icons.Default.Window,
                        label = stringResource(R.string.cmd_windows_close),
                        active = false,
                        busy = state.busyCommand == "windows_close",
                        enabled = !busy,
                        onClick = { findCommand("windows_close")?.let { viewModel.executeCommand(carId, it) } },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))

            // 3. Group: 充电管理
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    stringResource(R.string.cmd_group_charging),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (isPortOpen) {
                        RemoteActionButton(
                            icon = Icons.Default.EvStation,
                            label = stringResource(R.string.cmd_charge_port_close),
                            active = true,
                            busy = state.busyCommand in listOf("charge_port_open", "charge_port_close"),
                            enabled = !busy,
                            onClick = { findCommand("charge_port_close")?.let { viewModel.executeCommand(carId, it) } },
                            modifier = Modifier.weight(1.1f)
                        )
                    } else {
                        RemoteActionButton(
                            icon = Icons.Default.EvStation,
                            label = stringResource(R.string.cmd_charge_port_open),
                            active = false,
                            busy = state.busyCommand in listOf("charge_port_open", "charge_port_close"),
                            enabled = !busy,
                            onClick = { findCommand("charge_port_open")?.let { viewModel.executeCommand(carId, it) } },
                            modifier = Modifier.weight(1.1f)
                        )
                    }
                    if (isCharging) {
                        RemoteActionButton(
                            icon = Icons.Default.Stop,
                            label = stringResource(R.string.cmd_charge_stop),
                            active = true,
                            warning = true,
                            busy = state.busyCommand in listOf("charge_start", "charge_stop"),
                            enabled = !busy,
                            onClick = { findCommand("charge_stop")?.let { viewModel.executeCommand(carId, it) } },
                            modifier = Modifier.weight(1f)
                        )
                    } else {
                        RemoteActionButton(
                            icon = Icons.Default.PlayArrow,
                            label = stringResource(R.string.cmd_charge_start),
                            active = false,
                            busy = state.busyCommand in listOf("charge_start", "charge_stop"),
                            enabled = !busy && status.pluggedIn == true,
                            onClick = { findCommand("charge_start")?.let { viewModel.executeCommand(carId, it) } },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    RemoteActionButton(
                        icon = Icons.Default.Percent,
                        label = "${status.chargeLimitSoc ?: 80}%",
                        busy = state.busyCommand == "set_charge_limit",
                        enabled = !busy,
                        onClick = { showChargeLimitDialog = true },
                        modifier = Modifier.weight(0.9f)
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))

            // 4. Group: 安全防护 (哨兵)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    stringResource(R.string.sentry_mode),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
                if (isSentryOn) {
                    RemoteActionButton(
                        icon = Icons.Default.Security,
                        label = stringResource(R.string.cmd_sentry_off),
                        active = true,
                        busy = state.busyCommand in listOf("sentry_on", "sentry_off"),
                        enabled = !busy,
                        onClick = { findCommand("sentry_off")?.let { viewModel.executeCommand(carId, it) } },
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    RemoteActionButton(
                        icon = Icons.Default.Security,
                        label = stringResource(R.string.cmd_sentry_on),
                        active = false,
                        busy = state.busyCommand in listOf("sentry_on", "sentry_off"),
                        enabled = !busy,
                        onClick = { findCommand("sentry_on")?.let { viewModel.executeCommand(carId, it) } },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
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
                    findCommand("unlock")?.let {
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
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = stringResource(R.string.cmd_driver_temp) + "  ${formatTemp(driver)}°C",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Slider(value = driver, onValueChange = { driver = it }, valueRange = 15f..28f, steps = 25)
                    Text(
                        text = stringResource(R.string.cmd_passenger_temp) + "  ${formatTemp(passenger)}°C",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Slider(value = passenger, onValueChange = { passenger = it }, valueRange = 15f..28f, steps = 25)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showClimateDialog = false
                    findCommand("set_climate_temp")?.let {
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
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "${percent.roundToInt()}%",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Slider(
                        value = percent,
                        onValueChange = { percent = it },
                        valueRange = 50f..100f,
                        steps = 49,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showChargeLimitDialog = false
                    findCommand("set_charge_limit")?.let {
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
    if (value % 1f == 0f) value.toInt().toString() else "%.1f".format(value)

@Composable
internal fun NearbyChargingSection(
    carId: Int,
    status: CarStatus? = null,
    viewModel: VehicleRemoteViewModel
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val lat = status?.latitude
    val lon = status?.longitude

    LaunchedEffect(carId, lat, lon) {
        viewModel.loadNearby(carId, lat, lon)
    }

    TelemetryPanel(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.EvStation,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        stringResource(R.string.nearby_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                IconButton(
                    onClick = { viewModel.loadNearby(carId, lat, lon) },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = stringResource(R.string.refresh),
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            when (val nearby = state.nearby) {
                NearbyState.NotLoaded, NearbyState.Loading -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.5.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                NearbyState.Failed -> {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                stringResource(R.string.nearby_failed),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                            TextButton(onClick = { viewModel.loadNearby(carId, lat, lon) }) {
                                Text(stringResource(R.string.retry))
                            }
                        }
                    }
                }
                is NearbyState.Loaded -> {
                    if (nearby.sites.isEmpty()) {
                        Text(
                            stringResource(R.string.nearby_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            nearby.sites.take(5).forEach { site ->
                                ChargingSiteRow(site)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChargingSiteRow(site: ChargingSite) {
    val context = LocalContext.current
    val isSuper = site.isSupercharger
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(0.6.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                if (site.latitude != null && site.longitude != null) {
                    runCatching {
                        val uri = Uri.parse("geo:${site.latitude},${site.longitude}?q=${Uri.encode(site.name.orEmpty())}")
                        val mapIntent = Intent(Intent.ACTION_VIEW, uri)
                        context.startActivity(Intent.createChooser(mapIntent, "导航到充电站"))
                    }
                }
            }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
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
                        .background(
                            if (isSuper) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.8f)
                            else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.8f)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isSuper) Icons.Default.Bolt else Icons.Default.EvStation,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = if (isSuper) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = site.name ?: stringResource(R.string.nearby_unnamed),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = stringResource(
                            if (isSuper) R.string.nearby_supercharger else R.string.nearby_destination
                        ) + (site.stallsText?.let { " · 空闲 $it" } ?: ""),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (site.siteClosed == true) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = MaterialTheme.colorScheme.errorContainer
                    ) {
                        Text(
                            stringResource(R.string.nearby_closed),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                } else {
                    site.distanceKm?.let { km ->
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                        ) {
                            Text(
                                text = stringResource(R.string.nearby_distance, km),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            }

            // Price and Parking Strategy Row
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f),
                thickness = 0.6.dp
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Price Tag
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Paid,
                        contentDescription = null,
                        tint = Color(0xFF10B981),
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = site.displayPrice,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF047857)
                    )
                }

                // Parking Policy Tag
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.LocalParking,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = site.displayParking,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
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
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.LocationOn,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        stringResource(R.string.places_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                IconButton(
                    onClick = { viewModel.loadPlaces(carId) },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = stringResource(R.string.refresh),
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            when (val places = state.places) {
                PlacesState.NotLoaded, PlacesState.Loading -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
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
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            places.places.forEach { place ->
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = MaterialTheme.colorScheme.surface,
                                    border = BorderStroke(0.6.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 12.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.LocationOn,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp),
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                place.name ?: "",
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                            place.radiusM?.let { radius ->
                                                Text(
                                                    stringResource(R.string.places_radius, radius),
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                        IconButton(
                                            onClick = {
                                                pendingDeleteId = place.id
                                                pendingDeleteName = place.name ?: ""
                                            },
                                            modifier = Modifier.size(28.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.Delete,
                                                contentDescription = stringResource(R.string.delete),
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (status.latitude != null && status.longitude != null) {
                OutlinedButton(
                    onClick = { showAddDialog = true },
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.LocationOn, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
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
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(stringResource(R.string.places_radius, radius.roundToInt()))
                    Slider(
                        value = radius,
                        onValueChange = { radius = it },
                        valueRange = 50f..2000f,
                        steps = 38,
                        modifier = Modifier.fillMaxWidth()
                    )
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
