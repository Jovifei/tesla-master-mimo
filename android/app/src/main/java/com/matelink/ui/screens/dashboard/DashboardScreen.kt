package com.matelink.ui.screens.dashboard

import com.matelink.BuildConfig
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.res.stringResource
import com.matelink.R
import com.matelink.data.api.models.CarData
import com.matelink.data.repository.ApiErrorKind
import com.matelink.ui.components.AmapPointView
import com.matelink.ui.components.TelemetryPanel
import com.matelink.ui.components.VehicleHeroGraphic
import com.matelink.ui.theme.StatusSuccess
import com.matelink.ui.theme.StatusWarning
import com.matelink.ui.theme.SwissInk
import com.matelink.ui.theme.SwissMuted
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
    onNavigateToSentryHistory: (carId: Int, exteriorColor: String?) -> Unit = { _, _ -> },
    onNavigateToTrips: (carId: Int, exteriorColor: String?) -> Unit = { _, _ -> },
    viewModel: DashboardViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

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
                modifier = Modifier.weight(1f)
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                SnapshotBadge(uiState.snapshotSource)
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(onClick = { viewModel.refresh() }) {
                    Icon(Icons.Default.Refresh, stringResource(R.string.refresh))
                }
                IconButton(onClick = onNavigateToSettings) {
                    Icon(Icons.Default.Settings, stringResource(R.string.settings_title))
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
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
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

                VehicleHeroGraphic(accent = MaterialTheme.colorScheme.primary)

                val rangeKm = status.ratedBatteryRangeKm ?: status.idealBatteryRangeKm ?: status.estBatteryRangeKm
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom
                ) {
                    Column {
                        Text(
                            text = status.batteryLevel?.let { "$it%" } ?: "--",
                            style = MaterialTheme.typography.displayMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = stringResource(R.string.battery),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = rangeKm?.let { stringResource(R.string.km_range, it.toInt()) } ?: "--",
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
                uiState.observedAt?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        stringResource(R.string.dashboard_snapshot_time, it),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if ((status.chargeLimitSoc ?: 0) > 0) {
                    Text(stringResource(R.string.charge_limit, "${status.chargeLimitSoc ?: 0}%"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if ((status.chargeLimitSoc ?: 0) > 90) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.high_soc_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFFB8C00)
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
            }
        }

        // Info cards row
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            InfoCard(
                title = stringResource(R.string.odometer),
                value = status.odometer?.let { "${String.format(Locale.getDefault(), "%,.0f", it)} km" } ?: "--",
                modifier = Modifier.weight(1f),
                icon = Icons.Default.Speed,
                onClick = { onNavigateToMileage(carId, exteriorColor) }
            )
            InfoCard(
                title = stringResource(R.string.location),
                value = if (status.latitude != null && status.longitude != null) {
                    "${String.format(Locale.getDefault(), "%.4f", status.latitude)}, ${String.format(Locale.getDefault(), "%.4f", status.longitude)}" +
                        (status.elevation?.let { "\n${stringResource(R.string.elevation_label, "$it", "m")}" } ?: "")
                } else "--",
                modifier = Modifier.weight(1f),
                icon = Icons.Default.LocationOn,
                onClick = onNavigateToAmapPreview
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
            InfoCard(
                stringResource(R.string.inside_temp),
                status.insideTemp?.let { "$it°C" } ?: "--",
                Modifier.weight(1f),
                icon = Icons.Default.Thermostat
            )
            InfoCard(
                stringResource(R.string.outside_temp),
                status.outsideTemp?.let { "$it°C" } ?: "--",
                Modifier.weight(1f),
                icon = Icons.Default.Thermostat
            )
        }

        // Tire pressure
        Text(stringResource(R.string.tire_pressure), style = MaterialTheme.typography.titleSmall)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            InfoCard("FL", status.tpmsDetails?.pressureFl?.let { "$it bar" } ?: "--", Modifier.weight(1f))
            InfoCard("FR", status.tpmsDetails?.pressureFr?.let { "$it bar" } ?: "--", Modifier.weight(1f))
            InfoCard("RL", status.tpmsDetails?.pressureRl?.let { "$it bar" } ?: "--", Modifier.weight(1f))
            InfoCard("RR", status.tpmsDetails?.pressureRr?.let { "$it bar" } ?: "--", Modifier.weight(1f))
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
                            Text(status.chargerPower?.let { "$it kW" } ?: "--", fontWeight = FontWeight.Bold)
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
                VehicleHeroGraphic(accent = MaterialTheme.colorScheme.primary)
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

internal enum class SnapshotSourceKind {
    LIVE,
    HISTORY,
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
    "live_mqtt", "teslamate_api", "fleet_api" -> SnapshotSourceKind.LIVE
    "database_latest" -> SnapshotSourceKind.HISTORY
    BuildConfig.JOURVOLT_MOCK_SOURCE -> if (BuildConfig.JOURVOLT_MOCK_LOGIN) {
        SnapshotSourceKind.MOCK
    } else {
        SnapshotSourceKind.UNAVAILABLE
    }
    else -> SnapshotSourceKind.UNAVAILABLE
}

@Composable
private fun SnapshotBadge(source: String?) {
    val (color, label) = when (snapshotSourceKind(source)) {
        SnapshotSourceKind.LIVE -> StatusSuccess to stringResource(R.string.snapshot_source_live)
        SnapshotSourceKind.HISTORY -> StatusWarning to stringResource(R.string.snapshot_source_history)
        SnapshotSourceKind.MOCK -> if (BuildConfig.DEBUG) {
            StatusWarning to stringResource(R.string.snapshot_source_mock)
        } else {
            SwissMuted to stringResource(R.string.snapshot_source_unavailable)
        }
        SnapshotSourceKind.UNAVAILABLE -> SwissMuted to stringResource(R.string.snapshot_source_unavailable)
    }
    Surface(
        color = color,
        shape = MaterialTheme.shapes.extraLarge
    ) {
        Text(
            text = label,
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
    onClick: (() -> Unit)? = null
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
                        tint = MaterialTheme.colorScheme.primary,
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
                fontWeight = FontWeight.SemiBold
            )
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
