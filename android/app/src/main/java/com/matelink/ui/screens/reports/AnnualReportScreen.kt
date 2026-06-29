package com.matelink.ui.screens.reports

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.matelink.data.local.dao.MonthlyChargeAggregation
import com.matelink.data.local.dao.MonthlyDriveAggregation
import com.matelink.domain.model.CarStats

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnnualReportScreen(
    carId: Int = 1,
    year: Int = 2025,
    onBack: () -> Unit = {},
    onNavigateToPDF: () -> Unit = {},
    viewModel: AnnualReportViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(carId, year) {
        viewModel.init(carId, year)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Annual Report ${uiState.year}") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { padding ->
        if (uiState.isLoading) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        val stats = uiState.carStats
        if (stats == null) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text("No data available for ${uiState.year}")
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Year selector
            YearSelector(
                years = uiState.availableYears,
                selectedYear = uiState.year,
                onYearSelected = viewModel::selectYear
            )

            // T-101: Annual Summary
            AnnualSummarySection(stats)

            // T-102: Monthly Trends
            MonthlyTrendsSection(
                monthlyDrives = uiState.monthlyDrives,
                monthlyCharges = uiState.monthlyCharges
            )

            // T-103: Driving Habits
            DrivingHabitsSection(stats)

            // T-104: Generate PDF
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = onNavigateToPDF,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Generate PDF Report")
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun YearSelector(
    years: List<Int>,
    selectedYear: Int,
    onYearSelected: (Int) -> Unit
) {
    if (years.size <= 1) return

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        years.forEach { year ->
            FilterChip(
                selected = year == selectedYear,
                onClick = { onYearSelected(year) },
                label = { Text(year.toString()) },
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }
    }
}

// ==================== T-101: Annual Summary ====================

@Composable
private fun AnnualSummarySection(stats: CarStats) {
    val qs = stats.quickStats

    Text(
        text = "Annual Summary",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold
    )

    // Main metrics row
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SummaryCard(
            title = "Total Distance",
            value = String.format("%,.0f km", qs.totalDistanceKm),
            modifier = Modifier.weight(1f)
        )
        SummaryCard(
            title = "Total Drives",
            value = "${qs.totalDrives}",
            modifier = Modifier.weight(1f)
        )
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SummaryCard(
            title = "Energy Used",
            value = String.format("%,.1f kWh", qs.totalEnergyConsumedKwh),
            modifier = Modifier.weight(1f)
        )
        SummaryCard(
            title = "Avg Efficiency",
            value = String.format("%.0f Wh/km", qs.avgEfficiencyWhKm),
            modifier = Modifier.weight(1f)
        )
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SummaryCard(
            title = "Charges",
            value = "${qs.totalCharges}",
            modifier = Modifier.weight(1f)
        )
        SummaryCard(
            title = "Energy Added",
            value = String.format("%,.1f kWh", qs.totalEnergyAddedKwh),
            modifier = Modifier.weight(1f)
        )
    }

    if (qs.totalCost != null && qs.totalCost > 0) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SummaryCard(
                title = "Total Cost",
                value = String.format("€%.2f", qs.totalCost),
                modifier = Modifier.weight(1f)
            )
            SummaryCard(
                title = "Avg Cost/kWh",
                value = if (qs.avgCostPerKwh != null) String.format("€%.3f", qs.avgCostPerKwh) else "N/A",
                modifier = Modifier.weight(1f)
            )
        }
    }

    // Records
    if (qs.longestDrive != null || qs.fastestDrive != null) {
        Text(
            text = "Records",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 8.dp)
        )

        qs.longestDrive?.let {
            RecordRow("Longest Drive", String.format("%.1f km", it.distance), it.startDate)
        }
        qs.fastestDrive?.let {
            RecordRow("Fastest Drive", "${it.speedMax} km/h", it.startDate)
        }
        qs.mostEfficientDrive?.let {
            RecordRow("Most Efficient", String.format("%.0f Wh/km", it.efficiency), it.startDate)
        }
    }
}

@Composable
private fun SummaryCard(
    title: String,
    value: String,
    modifier: Modifier = Modifier
) {
    ElevatedCard(modifier = modifier) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun RecordRow(label: String, value: String, date: String?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            text = "$value${if (date != null) " ($date)" else ""}",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold
        )
    }
}

// ==================== T-102: Monthly Trends ====================

@Composable
private fun MonthlyTrendsSection(
    monthlyDrives: List<MonthlyDriveAggregation>,
    monthlyCharges: List<MonthlyChargeAggregation>
) {
    if (monthlyDrives.isEmpty() && monthlyCharges.isEmpty()) return

    Text(
        text = "Monthly Trends",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold
    )

    // Distance chart
    if (monthlyDrives.isNotEmpty()) {
        Text("Distance (km)", style = MaterialTheme.typography.labelMedium)
        BarChart(
            data = monthlyDrives.map { it.totalDistance.toFloat() },
            labels = monthlyDrives.map { monthLabel(it.month) },
            color = Color(0xFF1E88E5),
            modifier = Modifier.fillMaxWidth().height(160.dp)
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Drive count line chart
        Text("Drives per Month", style = MaterialTheme.typography.labelMedium)
        LineChart(
            data = monthlyDrives.map { it.driveCount.toFloat() },
            labels = monthlyDrives.map { monthLabel(it.month) },
            color = Color(0xFF43A047),
            modifier = Modifier.fillMaxWidth().height(120.dp)
        )
    }

    // Charging energy chart
    if (monthlyCharges.isNotEmpty()) {
        Spacer(modifier = Modifier.height(12.dp))
        Text("Energy Added (kWh)", style = MaterialTheme.typography.labelMedium)
        BarChart(
            data = monthlyCharges.map { it.totalEnergy.toFloat() },
            labels = monthlyCharges.map { monthLabel(it.month) },
            color = Color(0xFFFB8C00),
            modifier = Modifier.fillMaxWidth().height(160.dp)
        )
    }
}

@Composable
private fun BarChart(
    data: List<Float>,
    labels: List<String>,
    color: Color,
    modifier: Modifier = Modifier
) {
    val maxVal = data.maxOrNull()?.coerceAtLeast(1f) ?: 1f

    Canvas(modifier = modifier) {
        val barWidth = size.width / (data.size * 2f)
        val chartHeight = size.height - 24f

        data.forEachIndexed { index, value ->
            val barHeight = (value / maxVal) * chartHeight
            val x = index * (size.width / data.size) + barWidth / 2
            val y = chartHeight - barHeight

            drawRoundRect(
                color = color,
                topLeft = Offset(x, y),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(4f, 4f)
            )
        }
    }

    // Labels
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        labels.forEach { label ->
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun LineChart(
    data: List<Float>,
    labels: List<String>,
    color: Color,
    modifier: Modifier = Modifier
) {
    val maxVal = data.maxOrNull()?.coerceAtLeast(1f) ?: 1f

    Canvas(modifier = modifier) {
        val chartHeight = size.height - 16f
        val stepX = size.width / (data.size - 1).coerceAtLeast(1)

        val path = Path()
        data.forEachIndexed { index, value ->
            val x = index * stepX
            val y = chartHeight - (value / maxVal) * chartHeight
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color = color, style = Stroke(width = 3f))

        // Dots
        data.forEachIndexed { index, value ->
            val x = index * stepX
            val y = chartHeight - (value / maxVal) * chartHeight
            drawCircle(color = color, radius = 5f, center = Offset(x, y))
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        labels.forEach { label ->
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

private fun monthLabel(month: Int): String {
    return when (month) {
        1 -> "Jan"; 2 -> "Feb"; 3 -> "Mar"; 4 -> "Apr"
        5 -> "May"; 6 -> "Jun"; 7 -> "Jul"; 8 -> "Aug"
        9 -> "Sep"; 10 -> "Oct"; 11 -> "Nov"; 12 -> "Dec"
        else -> "$month"
    }
}

// ==================== T-103: Driving Habits ====================

@Composable
private fun DrivingHabitsSection(stats: CarStats) {
    val qs = stats.quickStats

    Text(
        text = "Driving Habits",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold
    )

    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Average drive duration
            qs.avgDriveMinutes?.let { mins ->
                HabitRow("Average Drive Duration", String.format("%.0f min", mins))
            }

            // Driving days
            qs.totalDrivingDays?.let { days ->
                HabitRow("Unique Driving Days", "$days days")
                if (qs.totalDrives > 0 && days > 0) {
                    HabitRow("Drives per Day", String.format("%.1f", qs.totalDrives.toFloat() / days))
                }
            }

            // Efficiency rating
            if (qs.avgEfficiencyWhKm > 0) {
                val rating = when {
                    qs.avgEfficiencyWhKm < 150 -> "Excellent"
                    qs.avgEfficiencyWhKm < 180 -> "Good"
                    qs.avgEfficiencyWhKm < 220 -> "Average"
                    else -> "High"
                }
                HabitRow("Efficiency Rating", rating)
            }

            // Max speed
            qs.maxSpeedKmh?.let {
                HabitRow("Top Speed", "$it km/h")
            }

            // Busiest day
            qs.busiestDay?.let {
                HabitRow("Busiest Day", "${it.day} (${it.count} drives)")
            }

            // Most distance day
            qs.mostDistanceDay?.let {
                HabitRow("Most Distance in a Day", String.format("%.1f km (%s)", it.totalDistance, it.day))
            }
        }
    }
}

@Composable
private fun HabitRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}
