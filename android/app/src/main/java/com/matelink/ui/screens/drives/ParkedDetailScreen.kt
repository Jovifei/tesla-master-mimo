package com.matelink.ui.screens.drives

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.matelink.R
import com.matelink.data.api.models.ParkedDetailData
import com.matelink.util.formatCompactDateTimeRange
import com.matelink.util.toChineseDisplayAddress

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ParkedDetailScreen(
    carId: Int,
    olderDriveId: Int,
    newerDriveId: Int,
    onNavigateBack: () -> Unit,
    viewModel: ParkedDetailViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    LaunchedEffect(carId, olderDriveId, newerDriveId) {
        viewModel.load(carId, olderDriveId, newerDriveId)
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("驻车详情") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                }
            )
        }
    ) { padding ->
        when {
            state.isLoading -> Column(
                modifier = Modifier.fillMaxSize().padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) { CircularProgressIndicator() }
            state.data != null -> ParkedDetailContent(state.data!!, Modifier.padding(padding))
            else -> Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) { Text(state.error ?: stringResource(R.string.not_available)) }
        }
    }
}

@Composable
private fun ParkedDetailContent(data: ParkedDetailData, modifier: Modifier = Modifier) {
    val unavailable = stringResource(R.string.not_available)
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = data.address.toChineseDisplayAddress() ?: stringResource(R.string.unknown_location),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = formatCompactDateTimeRange(data.startDate, data.endDate),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        MetricBand(
            title = "驻车消耗",
            entries = listOf(
                "电量变化" to data.batteryDelta?.let { "$it%" }.orUnavailable(unavailable),
                "估算能耗" to data.energyKwh?.let { "%.2f kWh".format(it) }.orUnavailable(unavailable),
                "平均功率" to data.averagePowerKw?.let { "%.1f kW".format(it) }.orUnavailable(unavailable),
                "峰值功率" to data.peakPowerKw?.let { "%.1f kW".format(it) }.orUnavailable(unavailable)
            )
        )
        MetricBand(
            title = "环境与数据",
            entries = listOf(
                "车内温度" to data.insideTempAverage?.let { "%.1f °C".format(it) }.orUnavailable(unavailable),
                "车外温度" to data.outsideTempAverage?.let { "%.1f °C".format(it) }.orUnavailable(unavailable),
                "采样数" to data.sampleCount.toString(),
                "覆盖率" to "%.0f%%".format(data.coverageRatio * 100)
            )
        )
        Text(
            text = "数据来源：${if (data.source == "database_latest") "TeslaMate 历史数据库" else data.source}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun MetricBand(title: String, entries: List<Pair<String, String>>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            entries.chunked(2).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { (label, value) ->
                        Column(Modifier.weight(1f)) {
                            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

private fun String?.orUnavailable(fallback: String): String = this ?: fallback
