package com.matelink.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.InsertChartOutlined
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.matelink.R
import com.matelink.domain.analytics.NoDataReason
import androidx.compose.ui.res.stringResource

enum class MetricPanelKind {
    LOADING,
    COLLECTING,
    EMPTY,
    UNAVAILABLE,
    ERROR
}

/**
 * One visual treatment for mutually-exclusive loading, collecting, empty,
 * unavailable and error states. Callers own the copy so page-specific facts
 * remain explicit and localizable.
 */
@Composable
fun MetricStatusPanel(
    kind: MetricPanelKind,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    val (icon, accent) = when (kind) {
        MetricPanelKind.LOADING -> Icons.Default.HourglassEmpty to MaterialTheme.colorScheme.primary
        MetricPanelKind.COLLECTING -> Icons.Default.InsertChartOutlined to MaterialTheme.colorScheme.primary
        MetricPanelKind.EMPTY, MetricPanelKind.UNAVAILABLE -> Icons.Default.Info to MaterialTheme.colorScheme.onSurfaceVariant
        MetricPanelKind.ERROR -> Icons.Default.ErrorOutline to MaterialTheme.colorScheme.error
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .widthIn(max = 560.dp)
            .heightIn(min = 196.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            contentColor = MaterialTheme.colorScheme.onSurface
        )
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 28.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = accent
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            if (actionLabel != null && onAction != null) {
                Button(onClick = onAction) {
                    Text(actionLabel)
                }
            }
        }
    }
}

@Composable
fun HistoryStatusPanel(
    reason: NoDataReason?,
    emptyBody: String,
    modifier: Modifier = Modifier
) {
    val (kind, title, body) = when (reason) {
        NoDataReason.COLLECTING -> Triple(
            MetricPanelKind.COLLECTING,
            stringResource(R.string.metric_state_collecting_title),
            stringResource(R.string.metric_state_collecting_body)
        )
        NoDataReason.SOURCE_UNAVAILABLE -> Triple(
            MetricPanelKind.UNAVAILABLE,
            stringResource(R.string.metric_state_unavailable_title),
            stringResource(R.string.metric_state_unavailable_body)
        )
        NoDataReason.INSUFFICIENT_COVERAGE -> Triple(
            MetricPanelKind.EMPTY,
            stringResource(R.string.metric_state_insufficient_title),
            stringResource(R.string.metric_state_insufficient_body)
        )
        NoDataReason.FILTER_EMPTY -> Triple(
            MetricPanelKind.EMPTY,
            stringResource(R.string.metric_state_filter_empty_title),
            stringResource(R.string.metric_state_filter_empty_body)
        )
        else -> Triple(
            MetricPanelKind.EMPTY,
            stringResource(R.string.metric_state_empty_title),
            emptyBody
        )
    }
    MetricStatusPanel(
        kind = kind,
        title = title,
        body = body,
        modifier = modifier
    )
}

@Composable
fun CachedHistoryBanner(
    title: String,
    body: String,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(end = 8.dp)
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall
                )
            }
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun MetricValueCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    supporting: String? = null
) {
    Card(
        modifier = modifier.heightIn(min = 88.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(text = value, style = MaterialTheme.typography.titleLarge)
            supporting?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
