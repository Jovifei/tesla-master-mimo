package com.matelink.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.TextUnit
import com.matelink.ui.theme.MetricMono

/** History rows stay compact; enlarged text can scroll without losing values or units. */
@Composable
fun CompactHistoryMetricStrip(
    metrics: List<TelemetryMetricSpec>, modifier: Modifier = Modifier,
    numberFontSize: TextUnit = 18.sp,
    clickableMetricIndex: Int? = null, onMetricClick: (() -> Unit)? = null,
    metricClickLabel: String? = null
) {
    if (metrics.isEmpty()) return
    val displayMetrics = metrics.map { metric ->
        if (metric.unit != null) metric else {
            val (number, unit) = telemetryMetricParts(metric.value)
            metric.copy(value = number, unit = unit)
        }
    }
    val measure = rememberTextMeasurer()
    val density = LocalDensity.current
    val numberStyle = MaterialTheme.typography.titleSmall.copy(
        fontFamily = MetricMono, fontWeight = FontWeight.SemiBold,
        fontSize = numberFontSize, lineHeight = numberFontSize * 1.25f
    )
    val captionStyle = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 12.sp)
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val cellWidths = displayMetrics.mapIndexed { index, metric ->
            maxOf(maxWidth / displayMetrics.size, maxOf(
                with(density) { measure.measure(metric.value, numberStyle).size.width.toDp() },
                with(density) { measure.measure(metric.unit.orEmpty(), captionStyle).size.width.toDp() },
                with(density) { measure.measure(metric.label, captionStyle).size.width.toDp() } +
                    if (index == clickableMetricIndex && onMetricClick != null) 32.dp else 16.dp
            ) + 6.dp)
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("history-metric-row")) {
            displayMetrics.forEachIndexed { index, metric ->
                val editable = index == clickableMetricIndex && onMetricClick != null
                val clickModifier = if (editable) Modifier.clickable(
                    onClickLabel = metricClickLabel, role = Role.Button,
                    onClick = { onMetricClick?.invoke() }
                ) else Modifier
                Column(
                    Modifier.width(cellWidths[index]).sizeIn(minHeight = 48.dp)
                        .then(clickModifier).padding(horizontal = 3.dp, vertical = 2.dp)
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
                        if (editable) {
                            Spacer(Modifier.width(3.dp))
                            Icon(Icons.Default.Edit, contentDescription = null,
                                tint = metric.tint, modifier = Modifier.size(12.dp))
                        }
                    }
                    Text(metric.value, Modifier.fillMaxWidth().testTag("history-metric-value-$index"),
                        style = numberStyle, maxLines = 1, textAlign = TextAlign.Center)
                    metric.unit?.takeIf(String::isNotBlank)?.let {
                        Text(it, modifier = Modifier.fillMaxWidth(), style = captionStyle, maxLines = 1,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
