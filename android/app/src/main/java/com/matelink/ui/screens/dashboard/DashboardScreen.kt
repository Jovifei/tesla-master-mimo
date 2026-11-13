package com.matelink.ui.screens.dashboard

import com.matelink.ui.components.VehicleOpeningsVisual
import android.content.Intent
import com.matelink.BuildConfig
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.animation.core.*
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.ui.zIndex
import kotlinx.coroutines.delay
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.res.stringResource
import com.matelink.R
import com.matelink.data.api.models.CarData
import com.matelink.data.api.models.CarStatus
import com.matelink.data.api.models.Units
import com.matelink.data.local.TirePosition
import com.matelink.data.repository.ApiErrorKind
import com.matelink.ui.components.AmapPointView
import com.matelink.ui.components.MateLinkLoadingPlaceholder
import com.matelink.ui.components.HistoryForegroundRefreshEffect
import com.matelink.ui.components.TelemetryPanel
import com.matelink.ui.components.TelemetryMetricSpec
import com.matelink.ui.components.TelemetryMetricStrip
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import com.matelink.ui.components.VehicleHeroGraphic
import com.matelink.ui.components.VehicleHeroImage
import com.matelink.domain.model.VehicleHeroModel
import com.matelink.domain.model.VehicleHeroProfile
import com.matelink.domain.model.UnitFormatter
import com.matelink.domain.telemetry.SnapshotFreshness
import com.matelink.domain.model.resolveVehicleHeroProfile
import com.matelink.ui.theme.StatusSuccess
import com.matelink.ui.theme.StatusWarning
import com.matelink.ui.theme.SwissInk
import com.matelink.ui.theme.SwissMuted
import com.matelink.ui.navigation.PearlDriveMotion
import com.matelink.ui.components.launchExternalIntentSafely
import com.matelink.ui.screens.readiness.DataReadinessRows
import com.matelink.ui.screens.readiness.readinessDashboardValue
import com.matelink.ui.screens.battery.classifyBatteryCharge
import androidx.compose.foundation.BorderStroke
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Eco
import androidx.compose.material.icons.filled.Timer
import androidx.compose.ui.unit.sp
import com.matelink.data.local.entity.DriveSummary
import kotlinx.coroutines.launch
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    intent: android.content.Intent? = null,
    onNavigateToSettings: () -> Unit = {},
    onNavigateToCharges: (carId: Int, exteriorColor: String?) -> Unit = { _, _ -> },
    onNavigateToDrives: (carId: Int, exteriorColor: String?) -> Unit = { _, _ -> },
    onNavigateToBattery: (carId: Int, efficiency: Double?, exteriorColor: String?) -> Unit = { _, _, _ -> },
    onNavigateToMileage: (carId: Int, exteriorColor: String?) -> Unit = { _, _ -> },
    onNavigateToUpdates: (carId: Int, exteriorColor: String?) -> Unit = { _, _ -> },
    onNavigateToBatterySocTrend: (carId: Int) -> Unit = {},
    onNavigateToCurrentCharge: (carId: Int, exteriorColor: String?) -> Unit = { _, _ -> },
    onNavigateToAmapPreview: () -> Unit = {},
    onNavigateToAmapSetup: () -> Unit = {},
    onNavigateToSentryHistory: (carId: Int, exteriorColor: String?) -> Unit = { _, _ -> },
    onNavigateToTpmsTrend: (carId: Int, exteriorColor: String?) -> Unit = { _, _ -> },
    onNavigateToTemperatureTrend: (carId: Int, exteriorColor: String?) -> Unit = { _, _ -> },
    onNavigateToTrips: (carId: Int, exteriorColor: String?) -> Unit = { _, _ -> },
    onNavigateToReadiness: (carId: Int) -> Unit = {},
    onNavigateToTeslaLogin: () -> Unit = {},
    onNavigateToDriveDetail: (carId: Int, driveId: Int) -> Unit = { _, _ -> },
    viewModel: DashboardViewModel = hiltViewModel()
) {
    val remoteViewModel: VehicleRemoteViewModel = hiltViewModel()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    HistoryForegroundRefreshEffect { viewModel.refresh() }
    val context = LocalContext.current
    val refreshRotation = remember { Animatable(0f) }
    val refreshScope = rememberCoroutineScope()
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
        onResult = { uri ->
            if (uri != null) {
                try {
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        viewModel.saveCustomPhoto(stream)
                    }
                } catch (_: Exception) {
                }
            }
        }
    )

    val refresh = {
        viewModel.refresh()
        refreshScope.launch {
            refreshRotation.snapTo(0f)
            refreshRotation.animateTo(
                targetValue = 360f,
                animationSpec = tween(PearlDriveMotion.RefreshDurationMillis)
            )
        }
        Unit
    }

    if (!uiState.isLoading && uiState.showReadinessIntro && uiState.dataReadiness != null) {
        AlertDialog(
            onDismissRequest = viewModel::dismissReadinessIntro,
            title = { Text(stringResource(R.string.data_readiness_intro_title)) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(stringResource(R.string.data_readiness_intro_body))
                    DataReadinessRows(uiState.dataReadiness!!)
                }
            },
            confirmButton = {
                TextButton(onClick = viewModel::dismissReadinessIntro) {
                    Text(stringResource(R.string.data_readiness_intro_continue))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    viewModel.dismissReadinessIntro()
                    uiState.dataReadinessCarId?.let(onNavigateToReadiness)
                }) {
                    Text(stringResource(R.string.data_readiness_intro_open))
                }
            }
        )
    }

    if (uiState.isLoading) {
        MateLinkLoadingPlaceholder()
        return
    }

    val status = uiState.status
    val car = uiState.car

    if (status == null) {
        PartialVehicleDashboard(
            car = car,
            error = uiState.error,
            errorKind = uiState.errorKind,
            onRefresh = { viewModel.refresh() },
            onNavigateToSettings = onNavigateToSettings
        )
        return
    }

    val carId = car?.carId ?: uiState.requestedCarId ?: return
    val exteriorColor = car?.carExterior?.exteriorColor
    val heroProfile = remember(
        car?.carDetails?.model,
        exteriorColor,
        car?.carExterior?.wheelType,
        car?.carDetails?.trimBadging
    ) {
        resolveVehicleHeroProfile(
            model = car?.carDetails?.model,
            exteriorColor = exteriorColor,
            wheelType = car?.carExterior?.wheelType,
            trimBadging = car?.carDetails?.trimBadging
        )
    }
    val openOpenings = openVehicleOpenings(status)
    val warningTires = warningTires(status.tpmsDetails)
    var showLockConfirmDialog by remember { mutableStateOf<Boolean?>(null) }
    var showPortConfirmDialog by remember { mutableStateOf<Boolean?>(null) }
    var showClimateDialog by remember { mutableStateOf(false) }
    var showSentryConfirmDialog by remember { mutableStateOf<Boolean?>(null) }
    var climateTempTarget by remember(status.driverTempSetting) {
        mutableStateOf(status.driverTempSetting ?: 22.0)
    }

    if (showLockConfirmDialog != null) {
        val willLock = showLockConfirmDialog == true
        AlertDialog(
            onDismissRequest = { showLockConfirmDialog = null },
            title = { Text(if (willLock) "确认锁闭车辆" else stringResource(R.string.cmd_unlock_confirm_title), fontWeight = FontWeight.Bold) },
            text = { Text(if (willLock) "确定要锁闭车辆门锁吗？" else stringResource(R.string.cmd_unlock_confirm_body)) },
            confirmButton = {
                Button(
                    onClick = {
                        val cmdName = if (willLock) "lock" else "unlock"
                        vehicleCommandCatalog.firstOrNull { it.name == cmdName }?.let {
                            remoteViewModel.executeCommand(carId, it, confirm = true)
                        }
                        showLockConfirmDialog = null
                    }
                ) { Text(stringResource(R.string.cmd_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showLockConfirmDialog = null }) { Text(stringResource(R.string.cmd_cancel)) }
            }
        )
    }

    if (showPortConfirmDialog != null) {
        val willClose = showPortConfirmDialog == true
        AlertDialog(
            onDismissRequest = { showPortConfirmDialog = null },
            title = { Text(if (willClose) "关闭充电口" else "打开充电口", fontWeight = FontWeight.Bold) },
            text = { Text(if (willClose) "确定要关闭车辆充电接口吗？" else "确定要开启车辆充电接口吗？") },
            confirmButton = {
                Button(
                    onClick = {
                        val cmdName = if (willClose) "charge_port_close" else "charge_port_open"
                        vehicleCommandCatalog.firstOrNull { it.name == cmdName }?.let {
                            remoteViewModel.executeCommand(carId, it, confirm = true)
                        }
                        showPortConfirmDialog = null
                    }
                ) { Text(stringResource(R.string.cmd_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showPortConfirmDialog = null }) { Text(stringResource(R.string.cmd_cancel)) }
            }
        )
    }

    if (showClimateDialog) {
        val isClimateOn = status.isClimateOn == true
        AlertDialog(
            onDismissRequest = { showClimateDialog = false },
            title = { Text("空调温度与控制", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(
                        text = if (isClimateOn) "当前空调已开启 · 目标设定 %.1f°C".format(climateTempTarget)
                        else "当前空调已关闭 · 预设目标 %.1f°C".format(climateTempTarget),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        IconButton(
                            onClick = { if (climateTempTarget > 16.0) climateTempTarget -= 0.5 },
                            enabled = climateTempTarget > 16.0
                        ) {
                            Text("-", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                        }
                        Text(
                            text = "%.1f°C".format(climateTempTarget),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        IconButton(
                            onClick = { if (climateTempTarget < 28.0) climateTempTarget += 0.5 },
                            enabled = climateTempTarget < 28.0
                        ) {
                            Text("+", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val cmdName = if (isClimateOn) "climate_off" else "climate_on"
                        vehicleCommandCatalog.firstOrNull { it.name == cmdName }?.let {
                            remoteViewModel.executeCommand(
                                carId,
                                it,
                                params = if (!isClimateOn) mapOf("driver_temp" to climateTempTarget) else null,
                                confirm = true
                            )
                        }
                        showClimateDialog = false
                    }
                ) {
                    Text(if (isClimateOn) "关闭空调" else "开启空调")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClimateDialog = false }) { Text("取消") }
            }
        )
    }

    if (showSentryConfirmDialog != null) {
        val willTurnOn = showSentryConfirmDialog == true
        AlertDialog(
            onDismissRequest = { showSentryConfirmDialog = null },
            title = { Text(if (willTurnOn) "开启哨兵模式" else "关闭哨兵模式", fontWeight = FontWeight.Bold) },
            text = { Text(if (willTurnOn) "确定要开启哨兵防盗监控模式吗？" else "确定要关闭哨兵模式吗？") },
            confirmButton = {
                Button(
                    onClick = {
                        val cmdName = if (willTurnOn) "sentry_on" else "sentry_off"
                        vehicleCommandCatalog.firstOrNull { it.name == cmdName }?.let {
                            remoteViewModel.executeCommand(carId, it, confirm = true)
                        }
                        showSentryConfirmDialog = null
                    }
                ) { Text(stringResource(R.string.cmd_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showSentryConfirmDialog = null }) { Text(stringResource(R.string.cmd_cancel)) }
            }
        )
    }

    val remoteUiState by remoteViewModel.uiState.collectAsState()
    LaunchedEffect(remoteUiState.outcome) {
        if (remoteUiState.outcome != null) {
            delay(3500)
            remoteViewModel.consumeOutcome()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
        // Top bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = car?.displayName ?: "My Tesla",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false).padding(end = 8.dp)
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = refresh, modifier = Modifier.size(36.dp)) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = stringResource(R.string.refresh),
                        modifier = Modifier.size(20.dp).graphicsLayer { rotationZ = refreshRotation.value }
                    )
                }
                IconButton(onClick = onNavigateToSettings) {
                    Icon(Icons.Default.Settings, stringResource(R.string.settings_title), modifier = Modifier.size(20.dp))
                }
            }
        }

        // Active Driving or Charging Dynamic HUD
        val isDrivingActive = (status.state.equals("driving", ignoreCase = true) || (status.speed ?: 0.0) > 0.0)
        val isChargingActive = (status.isCharging || status.chargingState.equals("charging", ignoreCase = true))

        val navigateToLiveDrives: () -> Unit = { onNavigateToDrives(carId, exteriorColor) }

        if (isDrivingActive && !uiState.isHudDismissed) {
            ActiveDrivingHudCard(
                status = status,
                units = uiState.units,
                onDismiss = viewModel::toggleHudDismissed,
                onClick = navigateToLiveDrives
            )
        } else if (isChargingActive && !uiState.isHudDismissed) {
            ActiveChargingHudCard(
                status = status,
                onDismiss = viewModel::toggleHudDismissed,
                onClick = { onNavigateToCurrentCharge(carId, exteriorColor) }
            )
        } else if ((isDrivingActive || isChargingActive) && uiState.isHudDismissed) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { viewModel.toggleHudDismissed() },
                shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = if (isDrivingActive) Icons.Default.Speed else Icons.Default.Bolt,
                            contentDescription = null,
                            tint = if (isDrivingActive) MaterialTheme.colorScheme.primary else StatusWarning,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = if (isDrivingActive) stringResource(R.string.active_driving_hud_title) else stringResource(R.string.active_charging_hud_title),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Text(
                        text = stringResource(R.string.expand_hud),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        // Vehicle hero and the most important live telemetry.
        TelemetryPanel(
            modifier = Modifier.fillMaxWidth(),
            accent = MaterialTheme.colorScheme.primary
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
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
                            imageVector = Icons.Default.DirectionsCar,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = stringResource(R.string.vehicle),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    val displayState = status.state?.takeIf { it.isNotBlank() }
                        ?: if (uiState.snapshotFreshness == SnapshotFreshness.HISTORY) "asleep" else null
                    displayState?.let { state ->
                        Surface(
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                            shape = MaterialTheme.shapes.extraLarge
                        ) {
                            Text(
                                text = vehicleStateLabel(state),
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                            )
                        }
                    }
                }

                VehicleHeroImage(
                    accent = MaterialTheme.colorScheme.primary,
                    model = car?.carDetails?.model,
                    exteriorColor = exteriorColor,
                    wheelType = car?.carExterior?.wheelType,
                    trimBadging = car?.carDetails?.trimBadging,
                    customPhotoFile = uiState.customPhotoFile,
                    onPickPhotoRequested = {
                        photoPickerLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    onResetPhotoRequested = viewModel::clearCustomPhoto
                )

                drivingTelemetryFor(status)?.let { telemetry ->
                    val metrics = buildList {
                        telemetry.speed?.let {
                            add(
                                TelemetryMetricSpec(
                                    icon = Icons.Default.Speed,
                                    label = stringResource(R.string.vehicle_speed),
                                    value = UnitFormatter.formatSpeed(it, uiState.units, 0),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            )
                        }
                        telemetry.power?.let {
                            val direction = powerDirection(it)
                            add(
                                TelemetryMetricSpec(
                                    icon = Icons.Default.Bolt,
                                    label = when (direction) {
                                        PowerDirection.CONSUMING -> stringResource(R.string.vehicle_power_consuming)
                                        PowerDirection.REGENERATING -> stringResource(R.string.vehicle_power_regenerating)
                                        PowerDirection.STEADY -> stringResource(R.string.vehicle_power_steady)
                                        null -> stringResource(R.string.power)
                                    },
                                    value = "%.1f kW".format(Locale.ROOT, kotlin.math.abs(it)),
                                    tint = if (direction == PowerDirection.REGENERATING) StatusSuccess else StatusWarning
                                )
                            )
                        }
                        telemetry.shiftState?.let {
                            add(
                                TelemetryMetricSpec(
                                    icon = Icons.Default.DirectionsCar,
                                    label = stringResource(R.string.vehicle_gear),
                                    value = when (it) {
                                        ShiftState.DRIVE -> stringResource(R.string.vehicle_shift_drive)
                                        ShiftState.REVERSE -> stringResource(R.string.vehicle_shift_reverse)
                                        ShiftState.NEUTRAL -> stringResource(R.string.vehicle_shift_neutral)
                                        ShiftState.PARK -> stringResource(R.string.vehicle_shift_park)
                                    },
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            )
                        }
                    }
                    if (metrics.isNotEmpty()) {
                        TelemetryMetricStrip(metrics = metrics)
                    }
                }

                val rangeKm = status.ratedBatteryRangeKm ?: status.idealBatteryRangeKm ?: status.estBatteryRangeKm
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                            onNavigateToBattery(carId, car?.carDetails?.efficiency, exteriorColor)
                        },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom
                ) {
                    Column {
                        AnimatedContent(
                            targetState = status.batteryLevel?.let { "$it%" } ?: "--",
                            transitionSpec = {
                                if (initialState == "--" && targetState != "--") {
                                    (
                                        fadeIn(
                                            animationSpec = tween(
                                                PearlDriveMotion.ValueTransitionDurationMillis
                                            )
                                        ) + slideInVertically(initialOffsetY = { it / 3 })
                                        ) togetherWith (
                                        fadeOut(
                                            animationSpec = tween(
                                                PearlDriveMotion.ValueTransitionDurationMillis
                                            )
                                        ) + slideOutVertically(targetOffsetY = { -it / 3 })
                                        )
                                } else {
                                    EnterTransition.None togetherWith ExitTransition.None
                                }
                            },
                            label = "dashboard_battery_value"
                        ) { batteryValue ->
                            Text(
                                text = batteryValue,
                                style = MaterialTheme.typography.displayMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Text(
                            text = stringResource(R.string.battery),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            if (isChargingActive) {
                                val infiniteTransition = rememberInfiniteTransition(label = "charging_bolt")
                                val boltAlpha by infiniteTransition.animateFloat(
                                    initialValue = 0.35f,
                                    targetValue = 1.0f,
                                    animationSpec = infiniteRepeatable(
                                        animation = tween(800, easing = LinearEasing),
                                        repeatMode = RepeatMode.Reverse
                                    ),
                                    label = "bolt_alpha"
                                )
                                Icon(
                                    imageVector = Icons.Default.Bolt,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = Color(0xFF10B981).copy(alpha = boltAlpha)
                                )
                            }
                            Text(
                                text = rangeKm?.let { UnitFormatter.formatDistance(it, uiState.units, 0) } ?: "--",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Text(
                            text = stringResource(R.string.range),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                status.batteryLevel?.let { batteryLevel ->
                    LinearProgressIndicator(
                        progress = { batteryLevel.coerceIn(0, 100) / 100f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(7.dp),
                        strokeCap = androidx.compose.ui.graphics.StrokeCap.Round
                    )
                }
                val chargePresentation = classifyBatteryCharge(status.batteryLevel, status.chargeLimitSoc)
                if (chargePresentation.summary != null) {
                    val summary = chargePresentation.summary
                    Text(
                        stringResource(R.string.battery_charge_summary, summary.currentLevel, summary.targetLevel),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else if (status.chargeLimitSoc != null) {
                    Text(
                        stringResource(R.string.charge_limit, "${status.chargeLimitSoc}%"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                val remoteUiState by remoteViewModel.uiState.collectAsStateWithLifecycle()
                val busyCmd = remoteUiState.busyCommand
                val canExecute = status.commandAuthorization?.ready ?: (status.commandAuthorization == null)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    StatusChip(
                        icon = if (status.locked == false) Icons.Default.LockOpen else Icons.Default.Lock,
                        label = when (status.locked) {
                            true -> stringResource(R.string.lock_locked)
                            false -> stringResource(R.string.lock_unlocked)
                            null -> stringResource(R.string.status_unknown)
                        },
                        active = status.locked,
                        isLoading = busyCmd in listOf("lock", "unlock"),
                        onClick = if (canExecute) {
                            {
                                showLockConfirmDialog = status.locked != true
                            }
                        } else null,
                        modifier = Modifier.weight(1f)
                    )
                    StatusChip(
                        icon = Icons.Default.Bolt,
                        label = when (status.pluggedIn) {
                            true -> stringResource(R.string.plug_plugged)
                            false -> stringResource(R.string.plug_unplugged)
                            null -> stringResource(R.string.status_unknown)
                        },
                        active = status.pluggedIn,
                        isLoading = busyCmd in listOf("charge_port_open", "charge_port_close", "charge_start", "charge_stop"),
                        onClick = if (canExecute) {
                            {
                                showPortConfirmDialog = status.chargePortDoorOpen == true
                            }
                        } else null,
                        modifier = Modifier.weight(1f)
                    )
                    StatusChip(
                        icon = Icons.Default.AcUnit,
                        label = when (status.isClimateOn) {
                            true -> stringResource(R.string.climate_on)
                            false -> stringResource(R.string.climate_off)
                            null -> stringResource(R.string.status_unknown)
                        },
                        active = status.isClimateOn,
                        isLoading = busyCmd in listOf("climate_on", "climate_off"),
                        onClick = if (canExecute) {
                            {
                                showClimateDialog = true
                            }
                        } else null,
                        modifier = Modifier.weight(1f)
                    )
                    StatusChip(
                        icon = Icons.Default.Security,
                        label = when (status.sentryMode) {
                            true -> stringResource(R.string.sentry_armed)
                            false -> stringResource(R.string.sentry_off)
                            null -> stringResource(R.string.status_unknown)
                        },
                        active = status.sentryMode,
                        isLoading = busyCmd in listOf("sentry_on", "sentry_off"),
                        onClick = if (canExecute) {
                            {
                                showSentryConfirmDialog = status.sentryMode != true
                            }
                        } else null,
                        modifier = Modifier.weight(1f)
                    )
                }

                if (openOpenings.isNotEmpty()) {
                    VehicleOpeningAlert(openOpenings)
                }

                // Extended Fleet display lines (design 2026-11-05 §4). Every
                // line renders only when the vehicle reported the data; the
                // shaping rules live in ExtendedStatusPresentation.kt.
                mediaPlayback(status)?.let { media ->
                    Text(
                        stringResource(R.string.media_now_playing, media.displayText),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                softwareUpdateUi(status)?.let { update ->
                    val percent = update.percent
                    Text(
                        when {
                            percent == null -> stringResource(R.string.software_update_in_progress)
                            update.phase == SoftwareUpdatePhase.DOWNLOADING ->
                                stringResource(R.string.software_update_downloading, percent)
                            else -> stringResource(R.string.software_update_installing, percent)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (percent != null) {
                        LinearProgressIndicator(
                            progress = { percent / 100f },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(7.dp),
                            strokeCap = androidx.compose.ui.graphics.StrokeCap.Round
                        )
                    }
                }
                scheduledEvents(status).forEach { event ->
                    Text(
                        stringResource(
                            if (event.kind == ScheduledKind.CHARGING_START) {
                                R.string.scheduled_charging_start
                            } else {
                                R.string.scheduled_departure
                            },
                            event.timeText
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                vehicleModes(status)?.let { modes ->
                    if (modes.valetOn) {
                        Text(
                            stringResource(R.string.valet_mode_on),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    modes.speedLimitKmh?.let { limit ->
                        Text(
                            stringResource(R.string.speed_limit_on, limit),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                climateTargets(status)?.let { targets ->
                    val targetText = listOfNotNull(targets.driver, targets.passenger)
                        .joinToString(" / ") { value ->
                            if (value % 1.0 == 0.0) value.toInt().toString() else value.toString()
                        }
                    if (targetText.isNotEmpty()) {
                        Text(
                            stringResource(R.string.climate_target, targetText),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // Fleet expansion sections (design 2026-11-05 §5–�?): vehicle
        // controls (hidden until the backend reports command scopes
        // ready), nearby charging (loaded on demand to save Fleet API
        // quota) and named places.


        // Info cards row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            InfoCard(
                title = stringResource(R.string.odometer),
                value = status.odometer?.let { UnitFormatter.formatDistance(it, uiState.units, 0) } ?: "--",
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                icon = Icons.Default.Speed,
                onClick = { onNavigateToMileage(carId, exteriorColor) }
            )
            val isAmapConfigured = uiState.isAmapConfigured
            val hasGps = com.matelink.domain.map.AmapConfiguration.isUsableCoordinate(status.latitude, status.longitude)
            val locationText = locationDisplay(
                geofence = status.geofence,
                cachedAddress = uiState.cachedAddress
            ) ?: stringResource(if (hasGps) R.string.view_vehicle_location else R.string.gps_waiting_prompt)
            val onLocationClick: () -> Unit = when {
                !isAmapConfigured -> onNavigateToAmapSetup
                !hasGps -> { { onNavigateToReadiness(carId) } }
                else -> onNavigateToAmapPreview
            }

            InfoCard(
                title = stringResource(R.string.location),
                value = locationText,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                icon = Icons.Default.LocationOn,
                onClick = onLocationClick
            )
        }

        // Recent drive card (Apple HIG style)
        uiState.recentDrive?.let { recent ->
            DashboardRecentDriveCard(
                drive = recent,
                units = uiState.units,
                onClick = { onNavigateToDriveDetail(carId, recent.driveId) }
            )
        }

        // Temperature cards
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val onTempClick: () -> Unit = { onNavigateToTemperatureTrend(carId, exteriorColor) }
            InfoCard(
                stringResource(R.string.inside_temp),
                status.insideTemp?.let { UnitFormatter.formatTemperature(it, uiState.units, 1) }
                    ?: stringResource(R.string.data_readiness_status_waiting_vehicle),
                Modifier.weight(1f),
                icon = Icons.Default.Thermostat,
                onClick = onTempClick
            )
            InfoCard(
                stringResource(R.string.outside_temp),
                status.outsideTemp?.let { UnitFormatter.formatTemperature(it, uiState.units, 1) }
                    ?: stringResource(R.string.data_readiness_status_waiting_vehicle),
                Modifier.weight(1f),
                icon = Icons.Default.Thermostat,
                onClick = onTempClick
            )
        }

        // Tire pressure
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onNavigateToTpmsTrend(carId, exteriorColor) },
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(stringResource(R.string.tire_pressure), style = MaterialTheme.typography.titleSmall)
            val onTpmsClick: () -> Unit = { onNavigateToTpmsTrend(carId, exteriorColor) }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                InfoCard(
                    stringResource(R.string.tire_fl_full),
                    status.tpmsDetails?.pressureFl?.let { formatPressure(it, uiState.units) }
                        ?: readinessDashboardValue(uiState.dataReadiness?.item("tpms")),
                    Modifier.weight(1f),
                    warning = TirePosition.FL in warningTires,
                    onClick = onTpmsClick
                )
                InfoCard(
                    stringResource(R.string.tire_fr_full),
                    status.tpmsDetails?.pressureFr?.let { formatPressure(it, uiState.units) }
                        ?: readinessDashboardValue(uiState.dataReadiness?.item("tpms")),
                    Modifier.weight(1f),
                    warning = TirePosition.FR in warningTires,
                    onClick = onTpmsClick
                )
                InfoCard(
                    stringResource(R.string.tire_rl_full),
                    status.tpmsDetails?.pressureRl?.let { formatPressure(it, uiState.units) }
                        ?: readinessDashboardValue(uiState.dataReadiness?.item("tpms")),
                    Modifier.weight(1f),
                    warning = TirePosition.RL in warningTires,
                    onClick = onTpmsClick
                )
                InfoCard(
                    stringResource(R.string.tire_rr_full),
                    status.tpmsDetails?.pressureRr?.let { formatPressure(it, uiState.units) }
                        ?: readinessDashboardValue(uiState.dataReadiness?.item("tpms")),
                    Modifier.weight(1f),
                    warning = TirePosition.RR in warningTires,
                    onClick = onTpmsClick
                )
            }
        }

        // Location Map
        val latitude = status.latitude
        val longitude = status.longitude
        if (latitude != null && longitude != null) {
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                AmapPointView(
                    modifier = Modifier.fillMaxWidth().height(200.dp),
                    latitude = latitude,
                    longitude = longitude,
                    title = car?.displayName ?: stringResource(R.string.vehicle)
                )
            }
        }

        // Surrounding charging network and saved places
        NearbyChargingSection(carId = carId, status = status, viewModel = remoteViewModel)
        VehiclePlacesSection(carId = carId, status = status, viewModel = remoteViewModel)

        // Battery history navigation. No chart is drawn until real history is available.
        TelemetryPanel(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    onNavigateToBatterySocTrend(carId)
                },
            accent = MaterialTheme.colorScheme.primary
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Bolt,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.battery_trend),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = stringResource(R.string.battery_history_navigation_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Charging card
        if (status.isCharging) {
            TelemetryPanel(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        onNavigateToCurrentCharge(carId, exteriorColor)
                    },
                accent = StatusWarning
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.Bolt, contentDescription = null, tint = StatusWarning)
                        Text(
                            stringResource(R.string.charging_in_progress),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column {
                            Text(stringResource(R.string.charge_power))
                            Text(
                                status.chargerPowerValue?.let { "%.1f kW".format(Locale.ROOT, it) } ?: "--",
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Column {
                            Text(stringResource(R.string.charge_added))
                            Text(status.chargeEnergyAdded?.let { "$it kWh" } ?: "--", fontWeight = FontWeight.Bold)
                        }
                        Column {
                            Text(stringResource(R.string.charge_remaining))
                            Text(status.timeToFullCharge?.let { "${it}h" } ?: "--", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }

    // Apple-style Dynamic Floating Capsule for vehicle commands
    androidx.compose.animation.AnimatedVisibility(
        visible = remoteUiState.busyCommand != null || remoteUiState.outcome != null,
        enter = fadeIn() + slideInVertically(initialOffsetY = { -it }),
        exit = fadeOut() + slideOutVertically(targetOffsetY = { -it }),
        modifier = Modifier
            .align(Alignment.TopCenter)
            .padding(top = 16.dp)
            .zIndex(10f)
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            shadowElevation = 8.dp,
            border = androidx.compose.foundation.BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (remoteUiState.busyCommand != null) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "正在送达车机指令...",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium
                    )
                } else if (remoteUiState.outcome is CommandOutcome.Accepted) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = Color(0xFF10B981),
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "指令已送达并生效",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF10B981)
                    )
                } else if (remoteUiState.outcome is CommandOutcome.Failed) {
                    val outcome = remoteUiState.outcome as CommandOutcome.Failed
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp)
                    )
                    val errorText = when (outcome.errorClass) {
                        "offline" -> "车机离线休眠中"
                        "unauthorized" -> "特斯拉车机权限未授权"
                        else -> "指令未生效 (${outcome.errorClass})"
                    }
                    Text(
                        text = errorText,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}
}

@Composable
private fun PartialVehicleDashboard(
    car: CarData?,
    error: String?,
    errorKind: ApiErrorKind?,
    onRefresh: () -> Unit,
    onNavigateToSettings: () -> Unit
) {
    val details = car?.carDetails
    val exterior = car?.carExterior
    val stats = car?.teslamateStats
    val modelText = listOfNotNull(
        details?.model?.takeIf { it.isNotBlank() }?.let { "Model $it" },
        details?.trimBadging?.takeIf { it.isNotBlank() }
    ).joinToString(" ").ifBlank { "-" }
    val exteriorText = listOfNotNull(
        exterior?.exteriorColor?.takeIf { it.isNotBlank() },
        exterior?.wheelType?.takeIf { it.isNotBlank() }
    ).joinToString("\n").ifBlank { "-" }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = car?.displayName ?: stringResource(R.string.vehicle),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Surface(
                color = StatusWarning,
                shape = MaterialTheme.shapes.extraLarge
            ) {
                Text(
                    text = stringResource(R.string.dashboard_partial_badge),
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }

        TelemetryPanel(
            modifier = Modifier.fillMaxWidth(),
            accent = MaterialTheme.colorScheme.primary
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                VehicleHeroImage(
                    accent = MaterialTheme.colorScheme.primary,
                    model = car?.carDetails?.model,
                    exteriorColor = exterior?.exteriorColor,
                    wheelType = exterior?.wheelType,
                    trimBadging = details?.trimBadging
                )
                Text(
                    text = stringResource(
                        if (error == null) {
                            R.string.dashboard_status_unavailable_title
                        } else {
                            dashboardErrorTitleRes(errorKind)
                        }
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = stringResource(
                        if (error == null) {
                            R.string.dashboard_partial_status_body
                        } else {
                            dashboardErrorBodyRes(errorKind)
                        }
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            InfoCard(
                title = stringResource(R.string.dashboard_partial_model),
                value = modelText,
                modifier = Modifier.weight(1f)
            )
            InfoCard(
                title = stringResource(R.string.dashboard_partial_exterior),
                value = exteriorText,
                modifier = Modifier.weight(1f)
            )
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            InfoCard(
                title = stringResource(R.string.stats_total_drives),
                value = stats?.totalDrives?.toString() ?: "-",
                modifier = Modifier.weight(1f)
            )
            InfoCard(
                title = stringResource(R.string.stats_total_charges),
                value = stats?.totalCharges?.toString() ?: "-",
                modifier = Modifier.weight(1f)
            )
        }

        InfoCard(
            title = stringResource(R.string.dashboard_partial_source_label),
            value = stringResource(R.string.dashboard_partial_source_value),
            modifier = Modifier.fillMaxWidth()
        )

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onRefresh, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.refresh))
            }
            OutlinedButton(onClick = onNavigateToSettings, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.settings_title))
            }
        }
    }
}

@Composable
private fun vehicleProfileSubtitle(profile: VehicleHeroProfile, trim: String?): String? {
    val modelLabel = when (profile.model) {
        VehicleHeroModel.MODEL_3 -> stringResource(R.string.vehicle_model_3)
        VehicleHeroModel.MODEL_Y -> stringResource(R.string.vehicle_model_y)
        VehicleHeroModel.MODEL_S -> stringResource(R.string.vehicle_model_s)
        VehicleHeroModel.MODEL_X -> stringResource(R.string.vehicle_model_x)
        VehicleHeroModel.UNKNOWN -> null
    }
    val trimLabel = when (vehicleTrimFor(trim)) {
        VehicleTrim.PERFORMANCE -> stringResource(R.string.vehicle_trim_performance)
        VehicleTrim.LONG_RANGE -> stringResource(R.string.vehicle_trim_long_range)
        VehicleTrim.STANDARD_RANGE -> stringResource(R.string.vehicle_trim_standard_range)
        null -> null
    }
    return listOfNotNull(modelLabel, trimLabel).joinToString(" · ").takeIf { it.isNotEmpty() }
}

@Composable
internal fun VehicleOpeningAlert(openings: Set<VehicleOpening>) {
    var expanded by remember(openings) { mutableStateOf(false) }
    val ordered = listOf(
        VehicleOpening.DOORS,
        VehicleOpening.WINDOWS,
        VehicleOpening.FRUNK,
        VehicleOpening.TRUNK
    ).filter { it in openings }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = StatusWarning.copy(alpha = 0.12f),
        shape = MaterialTheme.shapes.medium
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = StatusWarning,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.vehicle_openings_title),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = StatusWarning
                )
            }
            TextButton(onClick = { expanded = !expanded }) {
                Text(stringResource(if (expanded) R.string.interaction_openings_hide else R.string.interaction_openings_show))
            }
            if (expanded) VehicleOpeningsVisual(
                doors = true.takeIf { VehicleOpening.DOORS in openings },
                windows = true.takeIf { VehicleOpening.WINDOWS in openings },
                frunk = true.takeIf { VehicleOpening.FRUNK in openings },
                trunk = true.takeIf { VehicleOpening.TRUNK in openings })
            ordered.chunked(2).forEach { rowOpenings ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    rowOpenings.forEach { opening ->
                        Surface(
                            modifier = Modifier.weight(1f),
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
                            shape = MaterialTheme.shapes.small
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = StatusWarning,
                                    modifier = Modifier.size(15.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = when (opening) {
                                        VehicleOpening.DOORS -> stringResource(R.string.vehicle_doors_open)
                                        VehicleOpening.WINDOWS -> stringResource(R.string.vehicle_windows_open)
                                        VehicleOpening.FRUNK -> stringResource(R.string.vehicle_frunk_open)
                                        VehicleOpening.TRUNK -> stringResource(R.string.vehicle_trunk_open)
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = StatusWarning
                                )
                            }
                        }
                    }
                    if (rowOpenings.size == 1) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

internal enum class SnapshotSourceKind {
    LIVE,
    HISTORY,
    RECENT,
    MOCK,
    UNAVAILABLE
}

internal fun dashboardErrorTitleRes(kind: ApiErrorKind?): Int = when (kind) {
    ApiErrorKind.AUTH_REQUIRED -> R.string.dashboard_error_auth_title
    ApiErrorKind.RATE_LIMITED -> R.string.dashboard_error_rate_limit_title
    ApiErrorKind.SERVICE_UNAVAILABLE -> R.string.dashboard_error_service_title
    ApiErrorKind.NETWORK -> R.string.dashboard_error_network_title
    else -> R.string.dashboard_error_generic_title
}

internal fun dashboardErrorBodyRes(kind: ApiErrorKind?): Int = when (kind) {
    ApiErrorKind.AUTH_REQUIRED -> R.string.dashboard_error_auth_body
    ApiErrorKind.RATE_LIMITED -> R.string.dashboard_error_rate_limit_body
    ApiErrorKind.SERVICE_UNAVAILABLE -> R.string.dashboard_error_service_body
    ApiErrorKind.NETWORK -> R.string.dashboard_error_network_body
    else -> R.string.dashboard_error_generic_body
}

internal fun snapshotSourceKind(source: String?): SnapshotSourceKind = when (source) {
    "live_mqtt", "telemetry_mqtt", "fleet_api" -> SnapshotSourceKind.LIVE
    "mqtt_latest" -> SnapshotSourceKind.RECENT
    "database_latest", "teslamate_api" -> SnapshotSourceKind.HISTORY
    BuildConfig.JOURVOLT_MOCK_SOURCE -> if (BuildConfig.JOURVOLT_MOCK_LOGIN) {
        SnapshotSourceKind.MOCK
    } else {
        SnapshotSourceKind.UNAVAILABLE
    }
    else -> SnapshotSourceKind.UNAVAILABLE
}

internal fun dashboardHudSpeed(status: CarStatus, units: Units): String =
    status.speed?.let { UnitFormatter.formatSpeed(it, units, 0) } ?: "--"

internal fun dashboardHudChargeEnergy(status: CarStatus): String =
    status.chargeEnergyAdded?.let { "%.1f kWh".format(Locale.ROOT, it) } ?: "--"

internal fun dashboardHudVoltageCurrent(status: CarStatus): String? {
    // Ignore transient non-charging samples so the HUD never presents values such as 2V/0A as live charging telemetry.
    val rawVoltage = status.chargerVoltage
    val rawCurrent = status.chargerActualCurrent
    val voltage = rawVoltage?.takeIf { it >= 100 }
    val current = rawCurrent?.takeIf { it > 0 }
    if ((rawVoltage != null && voltage == null) || (rawCurrent != null && current == null)) return null
    if (voltage == null && current == null) return null
    return "${voltage ?: "--"}V / ${current ?: "--"}A"
}

@Suppress("UNUSED_PARAMETER")
internal fun dashboardIsAsleep(state: String?, errorKind: ApiErrorKind?, freshness: SnapshotFreshness): Boolean =
    state.equals("asleep", ignoreCase = true)

internal fun dashboardSnapshotLabelRes(freshness: SnapshotFreshness, isAsleep: Boolean): Int =
    if (isAsleep) R.string.snapshot_source_asleep else when (freshness) {
        SnapshotFreshness.LIVE -> R.string.snapshot_source_live
        SnapshotFreshness.HISTORY -> R.string.snapshot_source_history
        SnapshotFreshness.RECENT -> R.string.snapshot_source_recent
        SnapshotFreshness.UNAVAILABLE -> R.string.snapshot_source_unavailable
    }

@Composable
private fun SnapshotBadge(freshness: SnapshotFreshness, mixedSources: Boolean, source: String?, isAsleep: Boolean = false) {
    val mockEvidence = source == BuildConfig.JOURVOLT_MOCK_SOURCE && BuildConfig.JOURVOLT_MOCK_LOGIN
    val (color, label) = when {
        mockEvidence && BuildConfig.DEBUG -> StatusWarning to stringResource(R.string.snapshot_source_mock)
        isAsleep -> Color(0xFF607D8B) to stringResource(dashboardSnapshotLabelRes(freshness, isAsleep))
        else -> when (freshness) {
            SnapshotFreshness.LIVE -> StatusSuccess to stringResource(R.string.snapshot_source_live)
            SnapshotFreshness.HISTORY -> StatusWarning to stringResource(R.string.snapshot_source_history)
            SnapshotFreshness.RECENT -> Color(0xFF607D8B) to stringResource(dashboardSnapshotLabelRes(freshness, isAsleep))
            SnapshotFreshness.UNAVAILABLE -> SwissMuted to stringResource(R.string.snapshot_source_unavailable)
        }
    }
    Surface(
        color = color,
        shape = MaterialTheme.shapes.extraLarge
    ) {
        Text(
            text = if (mixedSources && !isAsleep) "$label · ${stringResource(R.string.snapshot_source_mixed)}" else label,
            color = Color.White,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}

@Composable
private fun InfoCard(
    title: String,
    value: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    onClick: (() -> Unit)? = null,
    warning: Boolean = false
) {
    TelemetryPanel(
        modifier = modifier.then(
            if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                icon?.let {
                    Icon(
                        imageVector = it,
                        contentDescription = null,
                        tint = if (warning) StatusWarning else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Text(
                    title,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (warning) StatusWarning else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (warning) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = StatusWarning,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(3.dp))
                    Text(
                        text = stringResource(R.string.tire_pressure_warning),
                        style = MaterialTheme.typography.labelSmall,
                        color = StatusWarning
                    )
                }
            }
        }
    }
}

@Composable
private fun vehicleStateLabel(state: String): String = when (state.lowercase()) {
    "online" -> stringResource(R.string.state_online)
    "driving" -> stringResource(R.string.state_driving)
    "charging" -> stringResource(R.string.state_charging)
    "asleep", "suspended" -> stringResource(R.string.state_asleep)
    "offline" -> stringResource(R.string.state_offline)
    "unavailable" -> stringResource(R.string.snapshot_source_unavailable)
    "unknown" -> stringResource(R.string.status_unknown)
    else -> state
}

@Composable
private fun StatusChip(
    icon: ImageVector,
    label: String,
    active: Boolean?,
    modifier: Modifier = Modifier,
    isLoading: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    val clickableModifier = if (onClick != null) {
        modifier.clickable(enabled = !isLoading, onClick = onClick)
    } else {
        modifier.clickable(enabled = false) {}
    }
    Surface(
        modifier = clickableModifier,
        color = when (active) {
            true -> MaterialTheme.colorScheme.primaryContainer
            false -> MaterialTheme.colorScheme.surfaceVariant
            null -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
        },
        shape = MaterialTheme.shapes.medium
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = if (active == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (active == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun ActiveDrivingHudCard(
    status: CarStatus,
    units: Units,
    onDismiss: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    ElevatedCard(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(StatusSuccess, shape = CircleShape)
                    )
                    Text(
                        text = stringResource(R.string.active_driving_hud_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                TextButton(
                    onClick = onDismiss,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = stringResource(R.string.close_hud),
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = dashboardHudSpeed(status, units),
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = stringResource(R.string.vehicle_speed),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                val shiftState = shiftStateFor(status.shiftState)
                shiftState?.let { shift ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                            modifier = Modifier.size(44.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = when (shift) {
                                        ShiftState.DRIVE -> stringResource(R.string.vehicle_shift_drive)
                                        ShiftState.REVERSE -> stringResource(R.string.vehicle_shift_reverse)
                                        ShiftState.NEUTRAL -> stringResource(R.string.vehicle_shift_neutral)
                                        ShiftState.PARK -> stringResource(R.string.vehicle_shift_park)
                                    },
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = stringResource(R.string.vehicle_gear),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                status.power?.let { powerVal ->
                    val direction = powerDirection(powerVal)
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = "%.1f kW".format(Locale.ROOT, kotlin.math.abs(powerVal)),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = if (direction == PowerDirection.REGENERATING) StatusSuccess else StatusWarning
                        )
                        Text(
                            text = when (direction) {
                                PowerDirection.CONSUMING -> stringResource(R.string.vehicle_power_consuming)
                                PowerDirection.REGENERATING -> stringResource(R.string.vehicle_power_regenerating)
                                else -> stringResource(R.string.power)
                            },
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
private fun ActiveChargingHudCard(
    status: CarStatus,
    onDismiss: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    ElevatedCard(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.45f)
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.BatteryChargingFull,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = StatusSuccess
                        )
                        Text("->", color = StatusSuccess, style = MaterialTheme.typography.labelSmall)
                        Icon(
                            imageVector = Icons.Default.DirectionsCar,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    Text(
                        text = stringResource(R.string.active_charging_hud_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    (status.chargerPowerValue ?: status.chargerPower?.toDouble())
                        ?.takeIf { it.isFinite() && it > 0.0 }?.let {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = "%.1f kW".format(Locale.ROOT, it),
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    color = StatusSuccess
                                )
                                Text(
                                    text = stringResource(R.string.charge_power),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                }
                TextButton(
                    onClick = onDismiss,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = stringResource(R.string.close_hud),
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = dashboardHudChargeEnergy(status),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = stringResource(R.string.charge_added),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                dashboardHudVoltageCurrent(status)?.let { voltageCurrent ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = voltageCurrent,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "${stringResource(R.string.current_charge_voltage)} / ${stringResource(R.string.current_charge_current)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                status.timeToFullCharge?.let { hours ->
                    val minutes = (hours * 60).toInt()
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m" else "${minutes}m",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                        Text(
                            text = stringResource(R.string.charge_remaining),
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
private fun DashboardRecentDriveCard(
    drive: DriveSummary,
    units: Units,
    onClick: () -> Unit
) {
    val startText = com.matelink.ui.components.recordedTripEndpoint(drive.startAddress)
    val endText = com.matelink.ui.components.recordedTripEndpoint(drive.endAddress)

    val distText = if (drive.distance >= 0.0) UnitFormatter.formatDistance(drive.distance, units, 1) else "--"
    val durText = if (drive.durationMin >= 0) "${drive.durationMin} min" else "--"
    val effText = drive.efficiency?.let { "%.0f Wh/km".format(it) }
        ?: drive.energyConsumed?.let { "%.1f kWh".format(it) } ?: "--"
    val speedText = if (drive.speedMax >= 0) "${drive.speedMax} km/h" else "--"

    val socRange = if (drive.startBatteryLevel > 0 || drive.endBatteryLevel > 0) {
        UnitFormatter.formatSocRange(
            drive.startBatteryLevel.takeIf { it > 0 },
            drive.endBatteryLevel.takeIf { it > 0 },
            ""
        )
    } else ""
    val routeText = "$startText → $endText"

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header: Title & Time
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.DirectionsCar,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "最近行程",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = formatSnapshotTime(drive.endDate) ?: drive.endDate.take(16).replace("T", " "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            // Route & SOC delta
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                    Text(
                        text = routeText,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = distText,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    if (socRange.isNotBlank()) {
                        Text(
                            text = socRange,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // 4 Capsules
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val capsuleModifier = Modifier.weight(1f)
                DashboardMetricCapsule(
                    icon = Icons.Default.Eco,
                    label = "能耗",
                    value = effText,
                    tint = Color(0xFF10B981),
                    modifier = capsuleModifier
                )
                DashboardMetricCapsule(
                    icon = Icons.Default.Speed,
                    label = "里程",
                    value = distText,
                    tint = Color(0xFF0284C7),
                    modifier = capsuleModifier
                )
                DashboardMetricCapsule(
                    icon = Icons.Default.Timer,
                    label = "时长",
                    value = durText,
                    tint = Color(0xFFD97706),
                    modifier = capsuleModifier
                )
                DashboardMetricCapsule(
                    icon = Icons.Default.Speed,
                    label = "极速",
                    value = speedText,
                    tint = Color(0xFF6366F1),
                    modifier = capsuleModifier
                )
            }
        }
    }
}

@Composable
private fun DashboardMetricCapsule(
    icon: ImageVector,
    label: String,
    value: String,
    tint: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(11.dp)
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = value,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 11.sp
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

