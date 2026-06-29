package com.matelink.ui.screens.reports

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.matelink.data.export.DataExporter
import com.matelink.data.export.ExportDataType
import com.matelink.data.export.ExportFormat

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportScreen(
    carId: Int = 1,
    onBack: () -> Unit = {},
    viewModel: ExportViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(carId) {
        viewModel.init(carId)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Export Data") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // T-201: Format Selection
            Text(
                text = "Export Format",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ExportFormat.entries.forEach { format ->
                    FilterChip(
                        selected = uiState.format == format,
                        onClick = { viewModel.setFormat(format) },
                        label = { Text(format.name) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // Data Type Selection
            Text(
                text = "Data to Export",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ExportDataType.entries.forEach { type ->
                    FilterChip(
                        selected = uiState.dataType == type,
                        onClick = { viewModel.setDataType(type) },
                        label = {
                            Text(when (type) {
                                ExportDataType.DRIVES -> "Drives"
                                ExportDataType.CHARGES -> "Charges"
                                ExportDataType.BOTH -> "Both"
                            })
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // T-202: Date Range Selection
            Text(
                text = "Date Range",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            // Year filter
            if (uiState.availableYears.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = uiState.selectedYear == null,
                        onClick = { viewModel.setYear(null) },
                        label = { Text("All Time") }
                    )
                    uiState.availableYears.take(5).forEach { year ->
                        FilterChip(
                            selected = uiState.selectedYear == year,
                            onClick = { viewModel.setYear(year) },
                            label = { Text(year.toString()) }
                        )
                    }
                }
            }

            HorizontalDivider()

            // Export summary
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Export Summary",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Format: ${uiState.format.name}")
                    Text("Data: ${when (uiState.dataType) {
                        ExportDataType.DRIVES -> "Drives"
                        ExportDataType.CHARGES -> "Charges"
                        ExportDataType.BOTH -> "Drives + Charges"
                    }}")
                    Text("Range: ${uiState.selectedYear?.toString() ?: "All Time"}")
                    Text("Drives: ${uiState.driveCount}")
                    Text("Charges: ${uiState.chargeCount}")
                }
            }

            // Export button
            Button(
                onClick = { viewModel.export() },
                enabled = !uiState.isExporting && (uiState.driveCount > 0 || uiState.chargeCount > 0),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (uiState.isExporting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text("Export & Share")
            }

            // Share intent launcher
            uiState.shareUri?.let { uri ->
                LaunchedEffect(uri) {
                    viewModel.clearShareUri() // clear first to prevent re-trigger
                    try {
                        val shareIntent = DataExporter.createShareIntent(uri, uiState.format)
                        context.startActivity(Intent.createChooser(shareIntent, "Share Export"))
                    } catch (_: Exception) {
                        // No activity to handle share intent
                    }
                }
            }

            uiState.error?.let { error ->
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}
