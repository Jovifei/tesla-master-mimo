package com.matelink.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.matelink.ui.theme.MetricMono

/** History rows stay compact; enlarged text can scroll without losing values or units. */
@Composable
fun CompactHistoryMetricStrip(metrics: List<TelemetryMetricSpec>, modifier: Modifier = Modifier) {
    if (metrics.isEmpty()) return
    val measure = rememberTextMeasurer()
    val density = LocalDensity.current
    val numberStyle = MaterialTheme.typography.titleSmall.copy(
        fontFamily = MetricMono, fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp, lineHeight = 20.sp
    )
    val captionStyle = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 12.sp)
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val neededWidth = metrics.maxOf { metric ->
            maxOf(
                with(density) { measure.measure(metric.value, numberStyle).size.width.toDp() },
                with(density) { measure.measure(metric.unit.orEmpty(), captionStyle).size.width.toDp() },
                with(density) { measure.measure(metric.label, captionStyle).size.width.toDp() } + 16.dp
            ) + 8.dp
        }
        val cellWidth = maxOf(maxWidth / metrics.size, neededWidth)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("history-metric-row")) {
            metrics.forEachIndexed { index, metric ->
                Column(
                    Modifier.width(cellWidth).padding(horizontal = 4.dp, vertical = 2.dp)
                        .semantics(mergeDescendants = true) {
                            contentDescription = "${metric.label}: ${metric.value} ${metric.unit.orEmpty()}"
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(metric.icon, contentDescription = null, tint = metric.tint, modifier = Modifier.size(12.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(metric.label, style = captionStyle, maxLines = 1,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(metric.value, Modifier.testTag("history-metric-value-$index"),
                        style = numberStyle, maxLines = 1)
                    metric.unit?.takeIf(String::isNotBlank)?.let {
                        Text(it, style = captionStyle, maxLines = 1,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
