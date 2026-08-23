package com.matelink.ui.screens.range

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CompareArrows
import androidx.compose.material.icons.filled.Route
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.BorderStroke
import androidx.hilt.navigation.compose.hiltViewModel
import com.matelink.R
import com.matelink.domain.analytics.HistoryFreshness
import com.matelink.domain.analytics.PersonalizedRangeSource
import com.matelink.domain.analytics.RangeSpeedBand
import com.matelink.domain.analytics.RangeTemperatureBand
import com.matelink.ui.components.MateLinkLoadingPlaceholder
import com.matelink.ui.components.AnalysisWindowSelector
import com.matelink.ui.components.CachedHistoryBanner
import com.matelink.ui.components.MetricPanelKind
import com.matelink.ui.components.MetricStatusPanel
import com.matelink.ui.components.HistoryStatusPanel
import com.matelink.ui.theme.SwissOutline
import com.matelink.ui.theme.StatusSuccess
import com.matelink.ui.theme.StatusWarning
import com.matelink.ui.theme.StatusError
import java.text.SimpleDateFormat
import java.util.Locale

private val AccuracyGreen = StatusSuccess
private val AccuracyYellow = StatusWarning
private val AccuracyRed = StatusError
private val EstimatedBlue = Color(0xFF42A5F5)
private val ActualGreen = Color(0xFF66BB6A)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RangeScreen(
    carId: Int,
    onNavigateBack: () -> Unit,
    viewModel: RangeViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(carId) {
        viewModel.setCarId(carId)
    }

    LaunchedEffect(uiState.error) {
        uiState.error?.let { error ->
            snackbarHostState.showSnackbar(error)
            viewModel.clearError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.range_analysis_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = uiState.isRefreshing,
            onRefresh = { viewModel.refresh() },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (uiState.isLoading && !uiState.isRefreshing) {
                MateLinkLoadingPlaceholder()
            } else {
                RangeContent(
                    uiState = uiState,
                    onWindowSelected = viewModel::selectWindow,
                    onCustomRangeSelected = viewModel::selectCustomRange
                )
            }
        }
    }
}

@Composable
private fun RangeContent(
    uiState: RangeUiState,
    onWindowSelected: (com.matelink.domain.analytics.AnalysisWindow) -> Unit,
    onCustomRangeSelected: (java.time.LocalDate, java.time.LocalDate) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        AnalysisWindowSelector(
            selected = uiState.selectedWindow,
            onSelected = onWindowSelected,
            onCustomSelected = onCustomRangeSelected,
            modifier = Modifier.fillMaxWidth()
        )

        if (uiState.historyFreshness == HistoryFreshness.STALE) {
            CachedHistoryBanner(
                title = stringResource(R.string.metric_state_cached_title),
                body = stringResource(R.string.metric_state_cached_body)
            )
        }

        PersonalizedRangeCard(uiState = uiState)

        if (uiState.trips.isEmpty()) {
            HistoryStatusPanel(
                reason = uiState.noDataReason,
                emptyBody = stringResource(R.string.metric_state_empty_body)
            )
            return@Column
        }

        // Accuracy summary card
        AccuracySummaryCard(uiState = uiState)

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, SwissOutline),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.range_influences_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = stringResource(
                        R.string.range_influences_values,
                        uiState.summerDeviationPercent?.let { "%.1f%%".format(it) } ?: stringResource(R.string.analysis_no_records),
                        uiState.winterDeviationPercent?.let { "%.1f%%".format(it) } ?: stringResource(R.string.analysis_no_records),
                        uiState.lowSpeedDeviationPercent?.let { "%.1f%%".format(it) } ?: stringResource(R.string.analysis_no_records),
                        uiState.highSpeedDeviationPercent?.let { "%.1f%%".format(it) } ?: stringResource(R.string.analysis_no_records)
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Trip list header
        if (uiState.trips.isNotEmpty()) {
            Text(
                text = stringResource(R.string.range_trip_list_title, uiState.tripCount),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        // Trip list
        uiState.trips.forEach { trip ->
            RangeTripCard(trip = trip)
        }

    }
}

@Composable
private fun PersonalizedRangeCard(uiState: RangeUiState) {
    val model = uiState.personalizedRange ?: return
    val sourceLabel = when (model.source) {
        PersonalizedRangeSource.GROUPED -> stringResource(R.string.range_personalized_source_grouped)
        PersonalizedRangeSource.GLOBAL -> stringResource(R.string.range_personalized_source_global)
        PersonalizedRangeSource.UNAVAILABLE -> null
    }
    val temperatureLabel = model.temperatureBand?.let { band ->
        stringResource(
            when (band) {
                RangeTemperatureBand.COLD -> R.string.range_temperature_cold
                RangeTemperatureBand.MILD -> R.string.range_temperature_mild
                RangeTemperatureBand.HOT -> R.string.range_temperature_hot
            }
        )
    }
    val speedLabel = model.speedBand?.let { band ->
        stringResource(
            when (band) {
                RangeSpeedBand.LOW -> R.string.range_speed_low
                RangeSpeedBand.CRUISE -> R.string.range_speed_cruise
                RangeSpeedBand.HIGH -> R.string.range_speed_high
            }
        )
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, SwissOutline),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Route,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.range_personalized_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.range_personalized_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))

            when {
                model.rangeKm != null -> {
                    Text(
                        text = stringResource(R.string.range_personalized_value, model.rangeKm),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    sourceLabel?.let { label ->
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                model.source != PersonalizedRangeSource.UNAVAILABLE -> {
                    Text(
                        text = stringResource(R.string.range_personalized_capacity_unavailable),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    uiState.ratedRangeKm?.let { ratedRange ->
                        Text(
                            text = stringResource(R.string.range_personalized_rated_fallback, ratedRange),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                else -> {
                    Text(
                        text = if (model.sampleCount == 0) {
                            stringResource(R.string.range_personalized_no_recent_data)
                        } else {
                            stringResource(
                                R.string.range_personalized_insufficient,
                                model.sampleCount,
                                model.distanceKm
                            )
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (model.sampleCount > 0) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(
                        R.string.range_personalized_details,
                        model.sampleCount,
                        model.distanceKm,
                        model.confidencePercent
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (temperatureLabel != null && speedLabel != null) {
                Text(
                    text = stringResource(
                        R.string.range_personalized_conditions,
                        temperatureLabel,
                        speedLabel
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun AccuracySummaryCard(uiState: RangeUiState) {
    val deviationColor = when {
        uiState.avgDeviationPercent == null -> MaterialTheme.colorScheme.onSurfaceVariant
        uiState.avgDeviationPercent <= 10.0 -> AccuracyGreen
        uiState.avgDeviationPercent <= 25.0 -> AccuracyYellow
        else -> AccuracyRed
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, SwissOutline),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            // Header
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Filled.CompareArrows,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.range_accuracy_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Rated-range deviation percentage
            if (uiState.avgDeviationPercent != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "%.1f%%".format(uiState.avgDeviationPercent),
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold,
                        color = deviationColor
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                LinearProgressIndicator(
                    progress = { (uiState.avgDeviationPercent / 100.0).coerceIn(0.0, 1.0).toFloat() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    color = deviationColor,
                    trackColor = MaterialTheme.colorScheme.surface,
                    strokeCap = StrokeCap.Round
                )
            } else {
                Text(
                    text = stringResource(R.string.analysis_no_records),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Stats row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                StatItem(
                    label = stringResource(R.string.range_trip_count),
                    value = if (uiState.tripCount > 0) "${uiState.tripCount}" else stringResource(R.string.analysis_no_records)
                )
                StatItem(
                    label = stringResource(R.string.range_total_distance),
                    value = if (uiState.tripCount > 0) "%.1f km".format(uiState.totalDistanceKm)
                    else stringResource(R.string.analysis_no_records)
                )
            }
        }
    }
}

@Composable
private fun StatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun RangeTripCard(trip: RangeTrip) {
    val deviationColor = when {
        trip.deviationPercent <= 10.0 -> AccuracyGreen
        trip.deviationPercent <= 25.0 -> AccuracyYellow
        else -> AccuracyRed
    }

    val dateStr = trip.startDate?.let { formatDate(it) } ?: ""

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, SwissOutline),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            // Date and address
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    if (dateStr.isNotEmpty()) {
                        Text(
                            text = dateStr,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    val address = buildString {
                        trip.startAddress?.let { append(it) }
                        if (trip.startAddress != null && trip.endAddress != null) append(" -> ")
                        trip.endAddress?.let { append(it) }
                    }
                    if (address.isNotEmpty()) {
                        Text(
                            text = address,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // Accuracy badge
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = deviationColor,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "%.1f%%".format(trip.deviationPercent),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = deviationColor
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(modifier = Modifier.height(8.dp))

            // Estimated vs actual
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                RangeValueColumn(
                    label = stringResource(R.string.range_estimated),
                    value = "%.1f km".format(trip.estimatedRangeKm),
                    color = EstimatedBlue,
                    modifier = Modifier.weight(1f)
                )
                RangeValueColumn(
                    label = stringResource(R.string.range_actual),
                    value = "%.1f km".format(trip.actualDistanceKm),
                    color = ActualGreen,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun RangeValueColumn(
    label: String,
    value: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .background(color.copy(alpha = 0.08f), RoundedCornerShape(8.dp))
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = color
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun formatDate(isoDate: String): String {
    return try {
        val inputFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
        val outputFormat = SimpleDateFormat("MM/dd HH:mm", Locale.getDefault())
        val date = inputFormat.parse(isoDate)
        date?.let { outputFormat.format(it) } ?: isoDate
    } catch (_: Exception) {
        isoDate
    }
}
