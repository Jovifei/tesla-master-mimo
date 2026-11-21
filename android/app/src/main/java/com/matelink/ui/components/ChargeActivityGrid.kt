package com.matelink.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.matelink.R
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/** null = unknown; a real zero remains zero. No inferred empty calendar days. */
data class ChargeActivityCell(
    val label: String, val value: Double?, val display: String,
    val key: String = label, val precision: Int = 1, val approximate: Boolean = false,
    val date: java.time.LocalDate? = null
)
internal fun chargeCellNumber(cell: ChargeActivityCell): String {
    val value = cell.value?.takeIf { it.isFinite() && it >= 0 } ?: return "—"
    return (if (cell.approximate) "≈" else "") + formatTelemetryNumber(value, cell.precision.coerceIn(0, 2))
}
internal fun chargeActivityIntensity(value: Double?, maximum: Double): Float {
    if (value == null || !value.isFinite() || value < 0 || !maximum.isFinite() || maximum <= 0) return 0f
    if (value == 0.0) return 0f
    return (0.12 + 0.76 * (value / maximum).coerceIn(0.0, 1.0)).toFloat()
}

/**
 * Compact multi-month heatmap. One column = one ISO week, seven rows = Mon..Sun,
 * twelve small cells fit across a phone; up to six months are shown and the strip
 * scrolls horizontally. Empty dates stay blank rather than asserting zero.
 */
@Composable
fun ChargeActivityGrid(cells: List<ChargeActivityCell>, modifier: Modifier = Modifier, scopeKey: String = "") {
    if (cells.isEmpty()) return
    var selected by remember(scopeKey, cells.map { it.key }) { mutableStateOf<String?>(null) }
    val selection = cells.firstOrNull { it.key == selected }
    val dated = cells.filter { it.date != null }.sortedBy { it.date }
    val accent = MaterialTheme.colorScheme.primary
    val empty = MaterialTheme.colorScheme.surfaceVariant
    val cell = 12.dp
    val gap = 2.dp

    Column(modifier.fillMaxWidth().testTag("charge_activity_grid"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.weight(1f))
            Text("0", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(4.dp))
            listOf(0f, .34f, .67f, 1f).forEach { level ->
                Box(Modifier.padding(horizontal = 1.dp).size(9.dp)
                    .background(lerp(empty, accent, level * .8f), MaterialTheme.shapes.extraSmall))
            }
            Spacer(Modifier.width(4.dp))
            Text("60+", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        if (dated.size >= 2) {
            val last = dated.last().date!!
            val earliest = maxOf(dated.first().date!!, last.minusMonths(3))
            val startMonday = earliest.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            val endSunday = last.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY))
            val weeks = ((endSunday.toEpochDay() - startMonday.toEpochDay()) / 7).toInt() + 1
            val byDate = dated.associateBy { it.date }
            val monthFormatter = DateTimeFormatter.ofPattern("MMM", Locale.getDefault())
            val scroll = rememberScrollState()
            Column(Modifier.horizontalScroll(scroll)) {
                Row {
                    repeat(weeks) { w ->
                        val weekStart = startMonday.plusWeeks(w.toLong())
                        val label = if (w == 0 || weekStart.month != startMonday.plusWeeks(w - 1L).month)
                            weekStart.format(monthFormatter) else ""
                        Box(Modifier.width(cell + gap).height(12.dp)) {
                            Text(label, style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    repeat(weeks) { w ->
                        Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                            for (day in 0..6) {
                                val date: LocalDate = startMonday.plusWeeks(w.toLong()).plusDays(day.toLong())
                                val data = byDate[date]
                                if (data == null || date.isBefore(earliest) || date.isAfter(last)) {
                                    Spacer(Modifier.size(cell))
                                } else {
                                    val intensity = chargeEnergyShade(data.value) * .8f
                                    val background = lerp(empty, accent, intensity)
                                    Surface(
                                        onClick = { selected = data.key },
                                        modifier = Modifier.size(cell)
                                            .testTag("charge_cell_${data.key}")
                                            .semantics {
                                                contentDescription = "${date} · ${data.display}"
                                                this.selected = selected == data.key
                                            },
                                        shape = MaterialTheme.shapes.extraSmall,
                                        color = background,
                                        border = BorderStroke(
                                            if (selected == data.key) 1.5.dp else .5.dp,
                                            if (selected == data.key) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.outlineVariant
                                        )
                                    ) {}
                                }
                            }
                        }
                    }
                }
            }
        } else {
            // Weekly/monthly granularity has no daily dates: show compact labelled chips.
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                cells.forEach { data ->
                    Surface(
                        onClick = { selected = data.key },
                        shape = MaterialTheme.shapes.small,
                        color = if (selected == data.key) MaterialTheme.colorScheme.secondaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant,
                        border = BorderStroke(.5.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(data.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(chargeCellNumber(data), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        selection?.let {
            Text(
                "${it.date?.toString() ?: it.label} · ${it.display}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("charge_cell_selection")
            )
        }
    }
}