package com.matelink.ui.screens.charges

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.matelink.R
import com.matelink.data.local.entity.SocSample
import com.matelink.ui.components.ChartData
import com.matelink.ui.components.drawYAxisLabels
import com.matelink.ui.theme.CarColorPalette
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal data class SocChartPoint(val at: Instant, val soc: Int)

/** Fixed recent-seven-calendar-day window, including days with no samples. */
internal fun sevenDaySocPoints(
    samples: List<SocSample>,
    now: Instant = Instant.now(),
    zone: ZoneId = ZoneId.systemDefault()
): List<SocChartPoint> {
    val today = now.atZone(zone).toLocalDate()
    val start = today.minusDays(6).atStartOfDay(zone).toInstant()
    return samples.asSequence()
        .map { SocChartPoint(Instant.ofEpochMilli(it.observedAtEpochMs), it.socPercent) }
        .filter { !it.at.isBefore(start) && !it.at.isAfter(now) }
        .sortedBy { it.at }
        .toList()
}

@Composable
internal fun SocTrendCard(
    samples: List<SocSample>,
    palette: CarColorPalette,
    modifier: Modifier = Modifier
) {
    var showInfo by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val is24Hour = android.text.format.DateFormat.is24HourFormat(context)
    val zone = ZoneId.systemDefault()
    val points = remember(samples) { sevenDaySocPoints(samples, zone = zone) }
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = palette.surface)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.charge_soc_trend_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = palette.onSurface,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { showInfo = true }, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Info, stringResource(R.string.more_information),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                }
            }
            if (points.size < 2) {
                Text(
                    text = stringResource(R.string.charge_soc_trend_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                val selected = remember(points) { mutableIntStateOf(-1) }
                SocTrendCanvas(
                    points = points,
                    selected = selected.intValue,
                    zone = zone,
                    is24Hour = is24Hour,
                    palette = palette,
                    onSelected = { selected.intValue = it },
                    modifier = Modifier.fillMaxWidth().height(132.dp)
                )
                points.getOrNull(selected.intValue)?.let { point ->
                    Text(
                        text = stringResource(
                            R.string.charge_soc_trend_selected,
                            point.at.atZone(zone).format(DateTimeFormatter.ofPattern(
                                if (is24Hour) "M/d HH:mm" else "M/d h:mm a", Locale.getDefault())),
                            point.soc
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = palette.accent
                    )
                }
            }
        }
    }
    if (showInfo) {
        AlertDialog(
            onDismissRequest = { showInfo = false },
            title = { Text(stringResource(R.string.charge_soc_trend_title)) },
            text = { Text(stringResource(R.string.charge_soc_trend_info)) },
            confirmButton = {
                TextButton(onClick = { showInfo = false }) {
                    Text(stringResource(android.R.string.ok))
                }
            }
        )
    }
}

@Composable
private fun SocTrendCanvas(
    points: List<SocChartPoint>,
    selected: Int,
    zone: ZoneId,
    is24Hour: Boolean,
    palette: CarColorPalette,
    onSelected: (Int) -> Unit,
    modifier: Modifier
) {
    val line = palette.accent
    val grid = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val startDay = points.first().at.atZone(zone).toLocalDate().minusDays(0)
    val pointsInWindow = remember(points, zone) {
        val today = Instant.now().atZone(zone).toLocalDate()
        (0..6).map { today.minusDays(6L - it) }
    }
    val domainStart = pointsInWindow.first().atStartOfDay(zone).toInstant().toEpochMilli()
    val domainEnd = pointsInWindow.last().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val span = (domainEnd - domainStart).coerceAtLeast(1L).toDouble()
    val minSoc = points.minOf { it.soc }
    val maxSoc = points.maxOf { it.soc }
    val chart = remember(points) {
        com.matelink.ui.components.prepareChartData(
            points.map { it.soc.toFloat() }, null, { it })
    }
    var canvasWidth by remember { mutableStateOf(0f) }
    Box(modifier = modifier) {
        Canvas(
            Modifier.fillMaxWidth().height(106.dp)
                .pointerInput(points) {
                    detectTapGestures { offset ->
                        val fraction = (offset.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                        val target = points.indices.minByOrNull {
                            kotlin.math.abs(
                                (points[it].at.toEpochMilli() - domainStart).toDouble() / span - fraction
                            )
                        }
                        if (target != null) onSelected(target)
                    }
                }
        ) {
            canvasWidth = size.width
            val padY = 10.dp.toPx()
            val plotHeight = (size.height - padY * 2).coerceAtLeast(1f)
            // Midnight separators and weekday labels.
            pointsInWindow.forEachIndexed { index, day ->
                val x = ((day.atStartOfDay(zone).toInstant().toEpochMilli() - domainStart) / span * size.width).toFloat()
                drawLine(grid, Offset(x, padY), Offset(x, padY + plotHeight), strokeWidth = 0.5.dp.toPx())
            }
            drawYAxisLabels(labelColor, chart, "%", size.height)
            val path = Path()
            points.forEachIndexed { index, point ->
                val x = ((point.at.toEpochMilli() - domainStart) / span * size.width).toFloat()
                val y = padY + (1.0 - (point.soc - minSoc).toDouble() / (maxSoc - minSoc).coerceAtLeast(1)).toFloat() * plotHeight
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                drawCircle(line, radius = 2.4.dp.toPx(), center = Offset(x, y))
                if (index == selected) {
                    drawCircle(line, radius = 5.dp.toPx(), center = Offset(x, y), style = Stroke(1.5.dp.toPx()))
                }
            }
            drawPath(path, line, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
        }
        Row(Modifier.fillMaxWidth().align(Alignment.BottomCenter), horizontalArrangement = Arrangement.SpaceBetween) {
            pointsInWindow.forEach { day ->
                Text(
                    day.format(DateTimeFormatter.ofPattern("M/d", Locale.getDefault())),
                    style = MaterialTheme.typography.labelSmall,
                    color = labelColor
                )
            }
        }
    }
}