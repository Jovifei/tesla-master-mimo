package com.matelink.ui.screens.dashboard

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
import com.matelink.ui.components.AmapPointView

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
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No data available")
        }
        return
    }

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
                text = car?.name ?: "My Tesla",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                StateBadge(status.state)
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(onClick = { viewModel.refresh() }) {
                    Icon(Icons.Default.Refresh, "Refresh")
                }
            }
        }

        // Battery card
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Battery", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    "${status.batteryLevel}%",
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text("${status.usableBatteryRangeKm} km range", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { status.batteryLevel / 100f },
                    modifier = Modifier.fillMaxWidth().height(8.dp),
                )
                if (status.chargeLimitSoc > 0) {
                    Text("Limit: ${status.chargeLimitSoc}%", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (status.chargeLimitSoc > 90) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "⚠️ High charge level - consider reducing to 80-90% for daily use",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFFB8C00)
                    )
                }
            }
        }

        // Info cards row
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            InfoCard("Odometer", "${String.format("%,.0f", status.odometer)} km", Modifier.weight(1f))
            InfoCard("Location", "${String.format("%.4f", status.latitude)}, ${String.format("%.4f", status.longitude)}\nElevation: ${status.elevation}m", Modifier.weight(1f))
        }

        // Location Map
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            AmapPointView(
                modifier = Modifier.fillMaxWidth().height(200.dp),
                latitude = status.latitude,
                longitude = status.longitude,
                title = car?.name ?: "Vehicle"
            )
        }

        // Temperature + Status cards
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            InfoCard("Inside", "${status.insideTemp}°C", Modifier.weight(1f))
            InfoCard("Outside", "${status.outsideTemp}°C", Modifier.weight(1f))
            InfoCard("Lock", if (status.locked) "🔒 Locked" else "🔓 Unlocked", Modifier.weight(1f))
            InfoCard("Plug", if (status.pluggedIn) "⚡ Plugged" else "Not Plugged", Modifier.weight(1f))
        }

        // Status row
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusChip("🔒", if (status.locked) "Locked" else "Unlocked", status.locked)
            StatusChip("⚡", if (status.pluggedIn) "Plugged" else "Unplugged", status.pluggedIn)
            StatusChip("💨", if (status.isClimateOn) "Climate ON" else "Climate OFF", status.isClimateOn)
            StatusChip("🛡️", if (status.sentryMode) "Sentry" else "Sentry OFF", status.sentryMode)
        }

        // Tire pressure
        Text("Tire Pressure", style = MaterialTheme.typography.titleSmall)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            InfoCard("FL", "${status.tirePressureFrontLeft} bar", Modifier.weight(1f))
            InfoCard("FR", "${status.tirePressureFrontRight} bar", Modifier.weight(1f))
            InfoCard("RL", "${status.tirePressureRearLeft} bar", Modifier.weight(1f))
            InfoCard("RR", "${status.tirePressureRearRight} bar", Modifier.weight(1f))
        }

        // 7-Day Battery Trend
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("7-Day Battery Trend", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                BatteryTrendChart()
            }
        }

        // Charging card
        if (status.state == "charging") {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("⚡ Charging", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column { Text("Power"); Text("${status.chargerPower} kW", fontWeight = FontWeight.Bold) }
                        Column { Text("Added"); Text("${status.chargeEnergyAdded} kWh", fontWeight = FontWeight.Bold) }
                        Column { Text("Remaining"); Text("${status.timeToFullCharge}h", fontWeight = FontWeight.Bold) }
                    }
                }
            }
        }
    }
}

@Composable
private fun StateBadge(state: String) {
    val (color, label) = when (state) {
        "online" -> Color(0xFF43A047) to "Online"
        "driving" -> Color(0xFF1E88E5) to "Driving"
        "charging" -> Color(0xFFFB8C00) to "Charging"
        "asleep" -> Color(0xFF9E9E9E) to "Asleep"
        else -> Color(0xFF616161) to "Offline"
    }
    SuggestionChip(
        onClick = {},
        label = { Text(label, color = Color.White) },
        colors = SuggestionChipDefaults.suggestionChipColors(containerColor = color)
    )
}

@Composable
private fun InfoCard(title: String, value: String, modifier: Modifier = Modifier) {
    ElevatedCard(modifier = modifier) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun StatusChip(icon: String, label: String, active: Boolean) {
    SuggestionChip(
        onClick = {},
        label = { Text("$icon $label", style = MaterialTheme.typography.bodySmall) },
        colors = SuggestionChipDefaults.suggestionChipColors(
            containerColor = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
        )
    )
}

@Composable
private fun BatteryTrendChart() {
    // Mock 7-day data - replace with real data from ViewModel
    val data = listOf(75, 72, 68, 70, 73, 76, 78)
    val labels = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    val maxVal = data.max()
    val minVal = data.min()
    val range = (maxVal - minVal).coerceAtLeast(1)

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
        drawPath(path, color = Color(0xFF1E88E5), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f))

        // Draw dots
        data.forEachIndexed { index, value ->
            val x = index * stepX
            val y = height - padding - ((value - minVal) / range.toFloat()) * (height - 2 * padding)
            drawCircle(color = Color(0xFF1E88E5), radius = 5f, center = androidx.compose.ui.geometry.Offset(x, y))
        }
    }

    // Labels
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        labels.forEach { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
