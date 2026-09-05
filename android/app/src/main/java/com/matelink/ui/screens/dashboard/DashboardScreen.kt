package com.matelink.ui.screens.dashboard

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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Bolt
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
    onNavigateToStats: (carId: Int, exteriorColor: String?) -> Unit = { _, _ -> },
    onNavigateToCurrentCharge: (carId: Int, exteriorColor: String?) -> Unit = { _, _ -> },
    onNavigateToAmapPreview: () -> Unit = {},
    onNavigateToAmapSetup: () -> Unit = {},
    onNavigateToSentryHistory: (carId: Int, exteriorColor: String?) -> Unit = { _, _ -> },
    onNavigateToTpmsTrend: (carId: Int, exteriorColor: String?) -> Unit = { _, _ -> },
    onNavigateToTemperatureTrend: (carId: Int, exteriorColor: String?) -> Unit = { _, _ -> },
    onNavigateToTrips: (carId: Int, exteriorColor: String?) -> Unit = { _, _ -> },
    onNavigateToReadiness: (carId: Int) -> Unit = {},
    viewModel: DashboardViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
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
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
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

    val carId = car?.carId ?: 1
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
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = car?.displayName ?: "My Tesla",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                vehicleProfileSubtitle(heroProfile, car?.carDetails?.trimBadging)?.let { subtitle ->
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val isAsleep = status.state?.equals("asleep", ignoreCase = true) == true ||
                        uiState.errorKind == ApiErrorKind.SERVICE_UNAVAILABLE ||
                        uiState.snapshotFreshness == SnapshotFreshness.RECENT
                    SnapshotBadge(uiState.snapshotFreshness, uiState.snapshotMixedSources, uiState.snapshotSource, isAsleep)
                    Spacer(modifier = Modifier.width(8.dp))
                    IconButton(onClick = refresh) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.refresh),
                            modifier = Modifier.graphicsLayer { rotationZ = refreshRotation.value }
                        )
                    }
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, stringResource(R.string.settings_title))
                    }
                }
                uiState.observedAt?.takeIf { it.isNotBlank() }?.let {
                    formatSnapshotTime(it)?.let { formatted ->
                        Text(
                            text = formatted,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(end = 6.dp)
                        )
                    }
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
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    onNavigateToBattery(carId, car?.carDetails?.efficiency, exteriorColor)
                },
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
                    status.state?.takeIf { it.isNotBlank() }?.let { state ->
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
                    modifier = Modifier.fillMaxWidth(),
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
                        Text(
                            text = rangeKm?.let { UnitFormatter.formatDistance(it, uiState.units, 0) } ?: "--",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
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
                        modifier = Modifier.weight(1f)
                    )
                }

                if (openOpenings.isNotEmpty()) {
                    VehicleOpeningAlert(openOpenings)
                }
            }
        }

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
            val hasGps = status.latitude != null && status.longitude != null
            val locationText = when {
                !isAmapConfigured -> stringResource(R.string.amap_unconfigured_prompt)
                !hasGps -> stringResource(R.string.gps_waiting_prompt)
                else -> locationDisplay(
                    geofence = status.geofence,
                    cachedAddress = uiState.cachedAddress,
                    latitude = status.latitude,
                    longitude = status.longitude
                ) ?: "${status.latitude}, ${status.longitude}"
            }
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

        // Temperature cards
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val onTempClick: () -> Unit = { onNavigateToTemperatureTrend(carId, exteriorColor) }
            InfoCard(
                stringResource(R.string.inside_temp),
                status.insideTemp?.let { UnitFormatter.formatTemperature(it, uiState.units, 1) } ?: "--",
                Modifier.weight(1f),
                icon = Icons.Default.Thermostat,
                onClick = onTempClick
            )
            InfoCard(
                stringResource(R.string.outside_temp),
                status.outsideTemp?.let { UnitFormatter.formatTemperature(it, uiState.units, 1) } ?: "--",
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

        // Battery history navigation. No chart is drawn until real history is available.
        TelemetryPanel(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    onNavigateToStats(carId, exteriorColor)
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
    "live_mqtt", "fleet_api" -> SnapshotSourceKind.LIVE
    "mqtt_latest" -> SnapshotSourceKind.RECENT
    "database_latest", "teslamate_api" -> SnapshotSourceKind.HISTORY
    BuildConfig.JOURVOLT_MOCK_SOURCE -> if (BuildConfig.JOURVOLT_MOCK_LOGIN) {
        SnapshotSourceKind.MOCK
    } else {
        SnapshotSourceKind.UNAVAILABLE
    }
    else -> SnapshotSourceKind.UNAVAILABLE
}

@Composable
private fun SnapshotBadge(freshness: SnapshotFreshness, mixedSources: Boolean, source: String?, isAsleep: Boolean = false) {
    val mockEvidence = source == BuildConfig.JOURVOLT_MOCK_SOURCE && BuildConfig.JOURVOLT_MOCK_LOGIN
    val (color, label) = when {
        mockEvidence && BuildConfig.DEBUG -> StatusWarning to stringResource(R.string.snapshot_source_mock)
        isAsleep -> Color(0xFF607D8B) to stringResource(R.string.snapshot_source_asleep)
        else -> when (freshness) {
            SnapshotFreshness.LIVE -> StatusSuccess to stringResource(R.string.snapshot_source_live)
            SnapshotFreshness.HISTORY -> StatusWarning to stringResource(R.string.snapshot_source_history)
            SnapshotFreshness.RECENT -> Color(0xFF607D8B) to stringResource(R.string.snapshot_source_asleep)
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
    else -> state
}

@Composable
private fun StatusChip(
    icon: ImageVector,
    label: String,
    active: Boolean?,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
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
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (active == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
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
                        text = UnitFormatter.formatSpeed(status.speed ?: 0.0, units, 0),
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
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(StatusWarning, shape = CircleShape)
                    )
                    Text(
                        text = stringResource(R.string.active_charging_hud_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    (status.chargerPowerValue ?: status.chargerPower?.toDouble())?.let {
                        Text(
                            text = "%.1f kW".format(Locale.ROOT, it),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = StatusWarning
                        )
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
                    val chargeEnergy = status.chargeEnergyAdded ?: 0.0
                    Text(
                        text = "%.1f kWh".format(Locale.ROOT, chargeEnergy),
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

                status.chargerVoltage?.let { v ->
                    val a = status.chargerActualCurrent ?: 0
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "${v}V / ${a}A",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = stringResource(R.string.charge_power),
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

