package com.matelink.ui.screens.timeline

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.hilt.navigation.compose.hiltViewModel
import com.matelink.R
import com.matelink.ui.theme.CarColorPalette
import com.matelink.ui.theme.SwissOutline
import java.text.SimpleDateFormat
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimelineScreen(
    onNavigateBack: () -> Unit,
    palette: CarColorPalette,
    viewModel: TimelineViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()

    LaunchedEffect(uiState.error) {
        uiState.error?.let {
            snackbarHostState.showSnackbar(it)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.timeline_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.White
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        if (uiState.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = palette.accent)
            }
        } else if (uiState.events.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.timeline_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = palette.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                uiState.groupedEvents.forEach { (date, dayEvents) ->
                    item(key = "header_$date") {
                        DateHeader(date = date, palette = palette)
                    }
                    items(
                        items = dayEvents,
                        key = { event ->
                            when (event) {
                                is TimelineEvent.DriveEvent -> "drive_${event.driveId}"
                                is TimelineEvent.ChargeEvent -> "charge_${event.chargeId}"
                            }
                        }
                    ) { event ->
                        TimelineEventCard(event = event, palette = palette)
                    }
                }
            }
        }
    }
}

@Composable
private fun DateHeader(date: String, palette: CarColorPalette) {
    val displayDate = remember(date) {
        formatDisplayDate(date)
    }

    Text(
        text = displayDate,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = palette.onSurface,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    )
}

@Composable
private fun TimelineEventCard(event: TimelineEvent, palette: CarColorPalette) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, SwissOutline),
        colors = CardDefaults.cardColors(containerColor = palette.surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Timeline indicator dot
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(
                        when (event) {
                            is TimelineEvent.DriveEvent -> palette.acColor.copy(alpha = 0.15f)
                            is TimelineEvent.ChargeEvent -> palette.dcColor.copy(alpha = 0.15f)
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = when (event) {
                        is TimelineEvent.DriveEvent -> Icons.Filled.DirectionsCar
                        is TimelineEvent.ChargeEvent -> Icons.Filled.Bolt
                    },
                    contentDescription = null,
                    tint = when (event) {
                        is TimelineEvent.DriveEvent -> palette.acColor
                        is TimelineEvent.ChargeEvent -> palette.dcColor
                    },
                    modifier = Modifier.size(22.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Event details
            Column(modifier = Modifier.weight(1f)) {
                when (event) {
                    is TimelineEvent.DriveEvent -> DriveEventContent(event, palette)
                    is TimelineEvent.ChargeEvent -> ChargeEventContent(event, palette)
                }
            }

            // Time
            Text(
                text = formatTime(event.timestamp),
                style = MaterialTheme.typography.bodySmall,
                color = palette.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun DriveEventContent(event: TimelineEvent.DriveEvent, palette: CarColorPalette) {
    Text(
        text = stringResource(R.string.timeline_drive),
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Medium,
        color = palette.onSurface
    )
    Spacer(modifier = Modifier.height(2.dp))

    val addressText = when {
        !event.startAddress.isNullOrBlank() && !event.endAddress.isNullOrBlank() ->
            "${event.startAddress} -> ${event.endAddress}"
        !event.startAddress.isNullOrBlank() -> event.startAddress
        else -> stringResource(R.string.timeline_unknown_route)
    }
    Text(
        text = addressText,
        style = MaterialTheme.typography.bodySmall,
        color = palette.onSurfaceVariant,
        maxLines = 1
    )
    Spacer(modifier = Modifier.height(4.dp))

    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        event.distance?.let { dist ->
            Text(
                text = stringResource(R.string.timeline_distance_value, dist),
                style = MaterialTheme.typography.bodySmall,
                color = palette.onSurfaceVariant
            )
        }
        event.durationStr?.let { dur ->
            Text(
                text = dur,
                style = MaterialTheme.typography.bodySmall,
                color = palette.onSurfaceVariant
            )
        }
        event.endBatteryLevel?.let { battery ->
            Text(
                text = stringResource(R.string.timeline_battery_end, battery),
                style = MaterialTheme.typography.bodySmall,
                color = palette.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ChargeEventContent(event: TimelineEvent.ChargeEvent, palette: CarColorPalette) {
    Text(
        text = stringResource(R.string.timeline_charge),
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Medium,
        color = palette.onSurface
    )
    Spacer(modifier = Modifier.height(2.dp))

    val addressText = event.address?.takeIf { it.isNotBlank() }
        ?: stringResource(R.string.timeline_unknown_location)
    Text(
        text = addressText,
        style = MaterialTheme.typography.bodySmall,
        color = palette.onSurfaceVariant,
        maxLines = 1
    )
    Spacer(modifier = Modifier.height(4.dp))

    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        event.energyAdded?.let { energy ->
            Text(
                text = stringResource(R.string.timeline_energy_added, energy),
                style = MaterialTheme.typography.bodySmall,
                color = palette.onSurfaceVariant
            )
        }
        event.cost?.let { cost ->
            Text(
                text = stringResource(R.string.timeline_cost_value, cost),
                style = MaterialTheme.typography.bodySmall,
                color = palette.onSurfaceVariant
            )
        }
        event.durationStr?.let { dur ->
            Text(
                text = dur,
                style = MaterialTheme.typography.bodySmall,
                color = palette.onSurfaceVariant
            )
        }
    }
}

private fun formatDisplayDate(isoDate: String): String {
    return try {
        val inputFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val outputFormat = SimpleDateFormat("yyyy年MM月dd日", Locale.getDefault())
        val date = inputFormat.parse(isoDate)
        date?.let { outputFormat.format(it) } ?: isoDate
    } catch (e: Exception) {
        isoDate
    }
}

private fun formatTime(isoTimestamp: String): String {
    return try {
        if (isoTimestamp.length < 16) return ""
        val timePart = isoTimestamp.substring(11, 16)
        timePart
    } catch (e: Exception) {
        ""
    }
}
