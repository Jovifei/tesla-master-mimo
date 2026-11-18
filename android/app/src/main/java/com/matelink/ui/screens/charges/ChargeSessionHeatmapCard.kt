package com.matelink.ui.screens.charges

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.matelink.R
import com.matelink.data.api.models.ChargeData
import com.matelink.ui.theme.CarColorPalette
import com.matelink.util.parseIsoInstant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private data class ChargeSessionPoint(
    val chargeId: Int,
    val at: java.time.Instant,
    val day: LocalDate,
    val kwh: Double
)

private data class ChargePlacement(
    val point: ChargeSessionPoint,
    val day: Int,
    val minute: Int,
    val order: Int,
    val total: Int
)

private fun chargeSessionPoints(
    charges: List<ChargeData>,
    from: LocalDate,
    to: LocalDate,
    zone: ZoneId
): List<ChargeSessionPoint> = charges.mapNotNull { charge ->
    val at = parseIsoInstant(charge.startDate) ?: return@mapNotNull null
    val day = at.atZone(zone).toLocalDate()
    if (day.isBefore(from) || day.isAfter(to)) return@mapNotNull null
    val kwh = (charge.batteryInputKwh ?: charge.partialBatteryInputKwh)
        ?.takeIf { it.isFinite() && it >= 0.0 } ?: return@mapNotNull null
    ChargeSessionPoint(charge.chargeId, at, day, kwh)
}.sortedBy { it.at }

@Composable
internal fun ChargeSessionHeatmapCard(
    charges: List<ChargeData>,
    palette: CarColorPalette,
    modifier: Modifier = Modifier
) {
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    var weekOffset by remember(charges) { mutableIntStateOf(0) }
    var selected by remember(charges, weekOffset) { mutableStateOf<ChargeSessionPoint?>(null) }
    var showInfo by remember { mutableStateOf(false) }
    val end = today.minusDays(weekOffset * 7L)
    val start = end.minusDays(6)
    val points = remember(charges, weekOffset) { chargeSessionPoints(charges, start, end, zone) }
    val oldest = remember(charges, zone) {
        charges.mapNotNull { charge -> parseIsoInstant(charge.startDate) }
            .minOrNull()?.atZone(zone)?.toLocalDate()
    }
    val hasOlder = oldest != null && oldest.isBefore(start)
    val hasNewer = weekOffset > 0

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = palette.surface)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.charge_heatmap_title),
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
            if (points.isEmpty()) {
                Text(
                    text = stringResource(R.string.charge_heatmap_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                ChargeSessionCanvas(
                    points = points,
                    days = (0..6).map { start.plusDays(it.toLong()) },
                    selected = selected,
                    palette = palette,
                    onSelect = { selected = it },
                    modifier = Modifier.fillMaxWidth().height(168.dp)
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    (0..6).forEach { index ->
                        Text(
                            start.plusDays(index.toLong())
                                .format(DateTimeFormatter.ofPattern("M/d", Locale.getDefault())),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                selected?.let { point ->
                    Text(
                        text = stringResource(
                            R.string.charge_heatmap_selected,
                            point.at.atZone(zone).format(DateTimeFormatter.ofPattern(
                                "M/d HH:mm", Locale.getDefault())),
                            point.kwh
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = palette.accent
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { weekOffset++ }, enabled = hasOlder) {
                        Text(stringResource(R.string.charge_heatmap_older))
                    }
                    TextButton(onClick = { weekOffset = (weekOffset - 1).coerceAtLeast(0) }, enabled = hasNewer) {
                        Text(stringResource(R.string.charge_heatmap_newer))
                    }
                }
            }
        }
    }
    if (showInfo) {
        AlertDialog(
            onDismissRequest = { showInfo = false },
            title = { Text(stringResource(R.string.charge_heatmap_title)) },
            text = { Text(stringResource(R.string.charge_heatmap_info)) },
            confirmButton = {
                TextButton(onClick = { showInfo = false }) { Text(stringResource(android.R.string.ok)) }
            }
        )
    }
}

@Composable
private fun ChargeSessionCanvas(
    points: List<ChargeSessionPoint>,
    days: List<LocalDate>,
    selected: ChargeSessionPoint?,
    palette: CarColorPalette,
    onSelect: (ChargeSessionPoint?) -> Unit,
    modifier: Modifier
) {
    val grid = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val baseDotColor = MaterialTheme.colorScheme.surfaceVariant
    val positions = remember(points, days) {
        val byDay = points.groupBy { it.day }
        points.map { point ->
            val dayIndex = days.indexOf(point.day).coerceAtLeast(0)
            val minute = point.at.atZone(ZoneId.systemDefault()).let {
                it.hour * 60 + it.minute
            }
            val sameDay = byDay[point.day].orEmpty().sortedBy { it.at }
            ChargePlacement(point, dayIndex, minute, sameDay.indexOf(point), sameDay.size)
        }
    }
    val maximum = positions.maxOfOrNull { it.point.kwh }?.coerceAtLeast(1.0) ?: 1.0
    Canvas(modifier.pointerInput(positions) {
        detectTapGestures { offset ->
            val left = 28.dp.toPx()
            val top = 12.dp.toPx()
            val bottom = 12.dp.toPx()
            val plotWidth = (size.width - left).coerceAtLeast(1f)
            val plotHeight = (size.height - top - bottom).coerceAtLeast(1f)
            val column = plotWidth / days.size
            val nearest = positions.minByOrNull { placement ->
                val x = left + column * (placement.day + 0.5f) +
                    (placement.order - (placement.total - 1) / 2f) * 8.dp.toPx()
                val y = top + plotHeight * (1f - placement.minute / 1440f)
                kotlin.math.hypot(offset.x - x, offset.y - y)
            }
            onSelect(nearest?.point)
        }
    }) {
        val left = 28.dp.toPx()
        val top = 12.dp.toPx()
        val bottom = 12.dp.toPx()
        val plotWidth = (size.width - left).coerceAtLeast(1f)
        val plotHeight = (size.height - top - bottom).coerceAtLeast(1f)
        val column = plotWidth / days.size
        // Hour guides: 00 / 06 / 12 / 18 / 24.
        val paint = Paint().apply {
            color = labelColor.copy(alpha = 0.75f).toArgb()
            textSize = 22f
            isAntiAlias = true
        }
        listOf(0, 6, 12, 18, 24).forEach { hour ->
            val y = top + plotHeight * (1f - hour / 24f)
            drawLine(grid, Offset(left, y), Offset(size.width, y), strokeWidth = 0.6.dp.toPx())
            drawContext.canvas.nativeCanvas.drawText("$hour", 2f, y + 7f, paint)
        }
        days.indices.forEach { index ->
            val x = left + column * (index + 0.5f)
            drawLine(grid, Offset(left + column * index, top), Offset(left + column * index, top + plotHeight),
                strokeWidth = 0.5.dp.toPx())
        }
        positions.forEach { placement ->
            val point = placement.point
            val x = left + column * (placement.day + 0.5f) +
                (placement.order - (placement.total - 1) / 2f) * 8.dp.toPx()
            val y = top + plotHeight * (1f - placement.minute / 1440f)
            val ratio = (point.kwh / maximum).coerceIn(0.0, 1.0)
            val color = lerp(baseDotColor, palette.accent, (0.18 + 0.82 * ratio).toFloat())
            val radius = (4.0 + 5.0 * ratio).dp.toPx()
            drawCircle(color, radius, Offset(x, y))
            if (selected?.chargeId == point.chargeId) {
                drawCircle(palette.onSurface, radius + 2.dp.toPx(), Offset(x, y), style = Stroke(1.5.dp.toPx()))
            }
        }
    }
}