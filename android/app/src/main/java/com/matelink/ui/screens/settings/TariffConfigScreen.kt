package com.matelink.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.matelink.R
import com.matelink.data.local.SettingsDataStore
import com.matelink.ui.theme.MateLinkTheme
import org.json.JSONArray

/**
 * Data class representing a time range for tariff periods.
 * Stored as JSON arrays of [startHour, endHour] pairs.
 */
data class TimeRange(
    val startHour: Int,
    val endHour: Int
) {
    val display: String
        get() = String.format("%02d:00-%02d:00", startHour, endHour)

    val hours: Double
        get() = if (endHour > startHour) {
            (endHour - startHour).toDouble()
        } else {
            (24 - startHour + endHour).toDouble()
        }
}

/**
 * Parse JSON array of [start, end] pairs into TimeRange list.
 */
fun parseTimeRanges(json: String): List<TimeRange> {
    return try {
        val arr = JSONArray(json)
        (0 until arr.length()).map { i ->
            val pair = arr.getJSONArray(i)
            TimeRange(pair.getInt(0), pair.getInt(1))
        }
    } catch (e: Exception) {
        emptyList()
    }
}

/**
 * Serialize TimeRange list to JSON array string.
 */
fun serializeTimeRanges(ranges: List<TimeRange>): String {
    val arr = JSONArray()
    for (range in ranges) {
        val pair = JSONArray()
        pair.put(range.startHour)
        pair.put(range.endHour)
        arr.put(pair)
    }
    return arr.toString()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TariffConfigScreen(
    onNavigateBack: () -> Unit,
    viewModel: TariffConfigViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.loadConfig()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.tariff_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        TariffConfigContent(
            modifier = Modifier.padding(paddingValues),
            isEnabled = uiState.isEnabled,
            peakPrice = uiState.peakPrice,
            flatPrice = uiState.flatPrice,
            valleyPrice = uiState.valleyPrice,
            peakRanges = uiState.peakRanges,
            flatRanges = uiState.flatRanges,
            valleyRanges = uiState.valleyRanges,
            onEnabledChange = viewModel::updateEnabled,
            onPeakPriceChange = viewModel::updatePeakPrice,
            onFlatPriceChange = viewModel::updateFlatPrice,
            onValleyPriceChange = viewModel::updateValleyPrice,
            onPeakRangesChange = viewModel::updatePeakRanges,
            onFlatRangesChange = viewModel::updateFlatRanges,
            onValleyRangesChange = viewModel::updateValleyRanges,
            onReset = viewModel::resetToDefaults,
            isSaving = uiState.isSaving
        )
    }
}

@Composable
private fun TariffConfigContent(
    modifier: Modifier = Modifier,
    isEnabled: Boolean,
    peakPrice: Double,
    flatPrice: Double,
    valleyPrice: Double,
    peakRanges: List<TimeRange>,
    flatRanges: List<TimeRange>,
    valleyRanges: List<TimeRange>,
    onEnabledChange: (Boolean) -> Unit,
    onPeakPriceChange: (Double) -> Unit,
    onFlatPriceChange: (Double) -> Unit,
    onValleyPriceChange: (Double) -> Unit,
    onPeakRangesChange: (List<TimeRange>) -> Unit,
    onFlatRangesChange: (List<TimeRange>) -> Unit,
    onValleyRangesChange: (List<TimeRange>) -> Unit,
    onReset: () -> Unit,
    isSaving: Boolean
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        // Enable/Disable Toggle
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.tariff_enable_label),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = stringResource(R.string.tariff_enable_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                androidx.compose.material3.Switch(
                    checked = isEnabled,
                    onCheckedChange = onEnabledChange
                )
            }
        }

        if (isEnabled) {
            Spacer(modifier = Modifier.height(16.dp))

            // Peak Period
            PeriodSection(
                label = stringResource(R.string.tariff_peak),
                color = Color(0xFFE53935),
                price = peakPrice,
                ranges = peakRanges,
                onPriceChange = onPeakPriceChange,
                onRangesChange = onPeakRangesChange
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Flat Period
            PeriodSection(
                label = stringResource(R.string.tariff_flat),
                color = Color(0xFFFF9800),
                price = flatPrice,
                ranges = flatRanges,
                onPriceChange = onFlatPriceChange,
                onRangesChange = onFlatRangesChange
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Valley Period
            PeriodSection(
                label = stringResource(R.string.tariff_valley),
                color = Color(0xFF2196F3),
                price = valleyPrice,
                ranges = valleyRanges,
                onPriceChange = onValleyPriceChange,
                onRangesChange = onValleyRangesChange
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Cost Preview
            CostPreviewSection(
                peakPrice = peakPrice,
                flatPrice = flatPrice,
                valleyPrice = valleyPrice,
                peakRanges = peakRanges,
                flatRanges = flatRanges,
                valleyRanges = valleyRanges
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Reset Button
            TextButton(
                onClick = onReset,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.tariff_reset))
            }
        }
    }
}

@Composable
private fun PeriodSection(
    label: String,
    color: Color,
    price: Double,
    ranges: List<TimeRange>,
    onPriceChange: (Double) -> Unit,
    onRangesChange: (List<TimeRange>) -> Unit
) {
    var showAddDialog by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Section Header
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(color)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Price Input
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = stringResource(R.string.tariff_price_label),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "¥",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = String.format("%.2f", price),
                        onValueChange = { value ->
                            value.toDoubleOrNull()?.let { onPriceChange(it) }
                        },
                        modifier = Modifier.width(100.dp),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        textStyle = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "/kWh",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Time Ranges
            ranges.forEachIndexed { index, range ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = range.display,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    IconButton(
                        onClick = {
                            onRangesChange(ranges.toMutableList().apply { removeAt(index) })
                        },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Delete,
                            contentDescription = stringResource(R.string.tariff_delete_range),
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
                if (index < ranges.lastIndex) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Add Range Button
            TextButton(
                onClick = { showAddDialog = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.tariff_add_range))
            }
        }
    }

    // Add Time Range Dialog
    if (showAddDialog) {
        AddTimeRangeDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { start, end ->
                onRangesChange(ranges + TimeRange(start, end))
                showAddDialog = false
            }
        )
    }
}

@Composable
private fun AddTimeRangeDialog(
    onDismiss: () -> Unit,
    onConfirm: (Int, Int) -> Unit
) {
    var startHour by remember { mutableIntStateOf(0) }
    var endHour by remember { mutableIntStateOf(1) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.tariff_add_range_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                // Start Hour
                Column {
                    Text(
                        text = stringResource(R.string.tariff_start_hour),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Slider(
                        value = startHour.toFloat(),
                        onValueChange = { startHour = it.toInt() },
                        valueRange = 0f..23f,
                        steps = 22,
                        colors = SliderDefaults.colors(
                            thumbColor = MaterialTheme.colorScheme.primary
                        )
                    )
                    Text(
                        text = String.format("%02d:00", startHour),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold
                    )
                }

                // End Hour
                Column {
                    Text(
                        text = stringResource(R.string.tariff_end_hour),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Slider(
                        value = endHour.toFloat(),
                        onValueChange = { endHour = it.toInt() },
                        valueRange = 0f..23f,
                        steps = 22,
                        colors = SliderDefaults.colors(
                            thumbColor = MaterialTheme.colorScheme.primary
                        )
                    )
                    Text(
                        text = String.format("%02d:00", endHour),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(startHour, endHour) }) {
                Text(stringResource(R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
private fun CostPreviewSection(
    peakPrice: Double,
    flatPrice: Double,
    valleyPrice: Double,
    peakRanges: List<TimeRange>,
    flatRanges: List<TimeRange>,
    valleyRanges: List<TimeRange>
) {
    val totalKwh = 50.0 // Assume 50 kWh monthly charging
    val peakHours = peakRanges.sumOf { it.hours }
    val flatHours = flatRanges.sumOf { it.hours }
    val valleyHours = valleyRanges.sumOf { it.hours }
    val totalHours = peakHours + flatHours + valleyHours

    if (totalHours <= 0) return

    val touCost = totalKwh * (
        peakHours * peakPrice +
        flatHours * flatPrice +
        valleyHours * valleyPrice
    ) / totalHours

    val flatRate = (peakPrice + flatPrice + valleyPrice) / 3.0
    val noTouCost = totalKwh * flatRate
    val savings = maxOf(noTouCost - touCost, 0.0)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(R.string.tariff_preview_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Savings Amount
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = if (savings > 0) stringResource(R.string.tariff_saves) else stringResource(R.string.tariff_costs),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = String.format("¥%.2f", if (savings > 0) savings else touCost),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (savings > 0) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Breakdown
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = stringResource(R.string.tariff_assume_monthly, totalKwh.toInt()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    PeriodBreakdownRow(
                        color = Color(0xFFE53935),
                        label = stringResource(R.string.tariff_peak),
                        price = peakPrice,
                        hours = peakHours,
                        totalHours = totalHours
                    )

                    PeriodBreakdownRow(
                        color = Color(0xFFFF9800),
                        label = stringResource(R.string.tariff_flat),
                        price = flatPrice,
                        hours = flatHours,
                        totalHours = totalHours
                    )

                    PeriodBreakdownRow(
                        color = Color(0xFF2196F3),
                        label = stringResource(R.string.tariff_valley),
                        price = valleyPrice,
                        hours = valleyHours,
                        totalHours = totalHours
                    )
                }
            }
        }
    }
}

@Composable
private fun PeriodBreakdownRow(
    color: Color,
    label: String,
    price: Double,
    hours: Double,
    totalHours: Double
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "$label ¥${String.format("%.2f", price)}/kWh",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = "${String.format("%.0f", if (totalHours > 0) hours / totalHours * 100 else 0.0)}% · ${hours.toInt()}h",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun TariffConfigScreenPreview() {
    MateLinkTheme {
        TariffConfigContent(
            isEnabled = true,
            peakPrice = 1.0,
            flatPrice = 0.7,
            valleyPrice = 0.3,
            peakRanges = listOf(TimeRange(10, 14), TimeRange(18, 20)),
            flatRanges = listOf(TimeRange(7, 9), TimeRange(15, 17), TimeRange(21, 22)),
            valleyRanges = listOf(TimeRange(23, 23), TimeRange(0, 6)),
            onEnabledChange = {},
            onPeakPriceChange = {},
            onFlatPriceChange = {},
            onValleyPriceChange = {},
            onPeakRangesChange = {},
            onFlatRangesChange = {},
            onValleyRangesChange = {},
            onReset = {},
            isSaving = false
        )
    }
}
