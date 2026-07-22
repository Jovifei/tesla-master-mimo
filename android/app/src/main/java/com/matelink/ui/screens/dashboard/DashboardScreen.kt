package com.matelink.ui.screens.dashboard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.Canvas
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.res.stringResource
import com.matelink.R
import com.matelink.data.api.models.CarData
import com.matelink.ui.components.AmapPointView
import com.matelink.ui.theme.StatusSuccess
import com.matelink.ui.theme.StatusWarning
import com.matelink.ui.theme.SwissInk
import com.matelink.ui.theme.SwissMuted

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
    onNavigateToWhereWasI: (carId: Int, timestamp: String, exteriorColor: String?) -> Unit = { _, _, _ -> },
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
                fontWeight = FontWeight.Bold
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                SnapshotBadge(uiState.snapshotSource)
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(onClick = { viewModel.refresh() }) {
                    Icon(Icons.Default.Refresh, stringResource(R.string.refresh))
                }
            }
        }

        // Battery card
        ElevatedCard(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    onNavigateToBattery(carId, car?.carDetails?.efficiency, exteriorColor)
                }
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(stringResource(R.string.battery), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    status.batteryLevel?.let { "$it%" } ?: "--",
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                val rangeKm = status.ratedBatteryRangeKm ?: status.idealBatteryRangeKm ?: status.estBatteryRangeKm
                Text(
                    rangeKm?.let { stringResource(R.string.km_range, it.toInt()) } ?: "--",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                status.batteryLevel?.let { batteryLevel ->
                    LinearProgressIndicator(
                        progress = { batteryLevel.coerceIn(0, 100) / 100f },
                        modifier = Modifier.fillMaxWidth().height(8.dp),
                    )
                }
                uiState.observedAt?.takeIf { it.isNotBlank() }?.let {
                    Text("快照时间 $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            }
        }

        // Info cards row
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            InfoCard(
                title = stringResource(R.string.odometer),
                value = status.odometer?.let { "${String.format("%,.0f", it)} km" } ?: "--",
                modifier = Modifier.weight(1f),
                onClick = { onNavigateToMileage(carId, exteriorColor) }
            )
            InfoCard(
                title = stringResource(R.string.location),
                value = if (status.latitude != null && status.longitude != null) {
                    "${String.format("%.4f", status.latitude)}, ${String.format("%.4f", status.longitude)}" +
                        (status.elevation?.let { "\n${stringResource(R.string.elevation_label, "$it", "m")}" } ?: "")
                } else "--",
                modifier = Modifier.weight(1f),
                onClick = { onNavigateToDrives(carId, exteriorColor) }
            )
        }

        // Location Map
        val latitude = status.latitude
        val longitude = status.longitude
        if (latitude != null && longitude != null) {
            ElevatedCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        val ts = status.stateSince
                        if (!ts.isNullOrBlank()) onNavigateToWhereWasI(carId, ts, exteriorColor)
                    }
            ) {
                AmapPointView(
                    modifier = Modifier.fillMaxWidth().height(200.dp),
                    latitude = latitude,
                    longitude = longitude,
                    title = car?.displayName ?: stringResource(R.string.vehicle)
                )
            }
        }

        // Temperature + Status cards
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            InfoCard(stringResource(R.string.inside_temp), status.insideTemp?.let { "$it°C" } ?: "--", Modifier.weight(1f))
            InfoCard(stringResource(R.string.outside_temp), status.outsideTemp?.let { "$it°C" } ?: "--", Modifier.weight(1f))
            InfoCard(stringResource(R.string.lock), status.locked?.let { if (it) stringResource(R.string.lock_locked) else stringResource(R.string.lock_unlocked) } ?: "--", Modifier.weight(1f))
            InfoCard(stringResource(R.string.plug), status.pluggedIn?.let { if (it) stringResource(R.string.plug_plugged) else stringResource(R.string.plug_unplugged) } ?: "--", Modifier.weight(1f))
        }

        // Status row
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusChip("🔒", if (status.locked == true) stringResource(R.string.lock_locked) else stringResource(R.string.lock_unlocked), status.locked == true)
            StatusChip("⚡", if (status.pluggedIn == true) stringResource(R.string.plug_plugged) else stringResource(R.string.plug_unplugged), status.pluggedIn == true)
            StatusChip("💨", if (status.isClimateOn == true) stringResource(R.string.climate_on) else stringResource(R.string.climate_off), status.isClimateOn == true)
            StatusChip("🛡️", if (status.sentryMode == true) stringResource(R.string.sentry_armed) else stringResource(R.string.sentry_off), status.sentryMode == true)
        }

        // Tire pressure
        Text(stringResource(R.string.tire_pressure), style = MaterialTheme.typography.titleSmall)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            InfoCard("FL", status.tpmsDetails?.pressureFl?.let { "$it bar" } ?: "--", Modifier.weight(1f))
            InfoCard("FR", status.tpmsDetails?.pressureFr?.let { "$it bar" } ?: "--", Modifier.weight(1f))
            InfoCard("RL", status.tpmsDetails?.pressureRl?.let { "$it bar" } ?: "--", Modifier.weight(1f))
            InfoCard("RR", status.tpmsDetails?.pressureRr?.let { "$it bar" } ?: "--", Modifier.weight(1f))
        }

        // 7-Day Battery Trend
        ElevatedCard(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    onNavigateToStats(carId, exteriorColor)
                }
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(stringResource(R.string.battery_trend), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(
                    text = stringResource(R.string.battery_trend_estimated_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                status.batteryLevel?.let { BatteryTrendChart(currentBatteryLevel = it) }
            }
        }

        // Charging card
        if (status.isCharging) {
            ElevatedCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        onNavigateToCurrentCharge(carId, exteriorColor)
                    },
                colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("⚡ ${stringResource(R.string.charging_in_progress)}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column { Text(stringResource(R.string.charge_power)); Text("${status.chargerPower ?: 0} kW", fontWeight = FontWeight.Bold) }
                        Column { Text(stringResource(R.string.charge_added)); Text("${status.chargeEnergyAdded ?: 0.0} kWh", fontWeight = FontWeight.Bold) }
                        Column { Text(stringResource(R.string.charge_remaining)); Text("${status.timeToFullCharge ?: 0.0}h", fontWeight = FontWeight.Bold) }
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

        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = stringResource(R.string.dashboard_status_unavailable_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = stringResource(R.string.dashboard_partial_status_body),
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
private fun SnapshotBadge(source: String?) {
    val (color, label) = when (source) {
        "live_mqtt", "teslamate_api" -> StatusSuccess to "实时数据"
        "database_latest" -> StatusWarning to "历史快照"
        else -> SwissMuted to "数据不可用"
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
    onClick: (() -> Unit)? = null
) {
    ElevatedCard(
        modifier = modifier.then(
            if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun StatusChip(icon: String, label: String, active: Boolean) {
    Surface(
        color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.extraLarge
    ) {
        Text(
            text = "$icon $label",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        )
    }
}

@Composable
private fun BatteryTrendChart(currentBatteryLevel: Int) {
    // TODO: replace with real 7-day history from API when available
    // Generate plausible 7-day mock anchored to the actual current battery level
    val data = remember(currentBatteryLevel) {
        listOf(
            (currentBatteryLevel - 6).coerceIn(0, 100),
            (currentBatteryLevel - 5).coerceIn(0, 100),
            (currentBatteryLevel - 8).coerceIn(0, 100),
            (currentBatteryLevel - 4).coerceIn(0, 100),
            (currentBatteryLevel - 3).coerceIn(0, 100),
            (currentBatteryLevel - 1).coerceIn(0, 100),
            currentBatteryLevel.coerceIn(0, 100)
        )
    }
    val labels = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    val maxVal = data.max()
    val minVal = data.min()
    val range = (maxVal - minVal).coerceAtLeast(1)
    val primaryColor = MaterialTheme.colorScheme.primary

    Canvas(modifier = Modifier.fillMaxWidth().height(120.dp)) {
        val width = size.width
        val height = size.height
        val stepX = width / (data.size - 1)
        val padding = 20f

        // Draw line
        val path = androidx.compose.ui.graphics.Path()
        data.forEachIndexed { index, value ->
            val x = index * stepX
            val y = height - padding - ((value - minVal) / range.toFloat()) * (height - 2 * padding)
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color = primaryColor, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f))

        // Draw dots
        data.forEachIndexed { index, value ->
            val x = index * stepX
            val y = height - padding - ((value - minVal) / range.toFloat()) * (height - 2 * padding)
            drawCircle(color = primaryColor, radius = 5f, center = androidx.compose.ui.geometry.Offset(x, y))
        }
    }

    // Labels
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        labels.forEach { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
