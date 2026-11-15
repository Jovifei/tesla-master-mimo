package com.matelink.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.matelink.R

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

@Composable
fun ChargeActivityGrid(cells: List<ChargeActivityCell>, modifier: Modifier = Modifier, scopeKey: String = "") {
    if (cells.isEmpty()) return
    val size = 28
    var page by remember(scopeKey, cells.map { it.key }) { mutableIntStateOf((cells.size - 1) / size) }
    var selected by remember(scopeKey, cells.map { it.key }) { mutableStateOf<String?>(null) }
    val maxPage = (cells.size - 1) / size
    val shown = cells.drop(page.coerceIn(0, maxPage) * size).take(size)
    val leading = shown.firstOrNull()?.date?.dayOfWeek?.value?.minus(1) ?: 0
    val aligned: List<ChargeActivityCell?> = List(leading) { null } + shown
    val maximum = 60.0
    val selection = cells.firstOrNull { it.key == selected }
    Column(modifier.fillMaxWidth().testTag("charge_activity_grid"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.ui_charge_legend), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        BoxWithConstraints {
            val scale = LocalDensity.current.fontScale
            val columns = 7
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (shown.any { it.date != null }) Row(Modifier.fillMaxWidth()) {
                    listOf("一", "二", "三", "四", "五", "六", "日").forEach {
                        Text(it, Modifier.weight(1f), textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                aligned.chunked(columns).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { cell ->
                            if (cell == null) { Spacer(Modifier.weight(1f)); return@forEach }
                            val intensity = if (cell.date != null) chargeEnergyShade(cell.value) * .80f else chargeActivityIntensity(cell.value, maximum)
                            val background = androidx.compose.ui.graphics.lerp(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.primary, intensity)
                            // Pick the stronger actual contrast, not a threshold on chart intensity.
                            val foreground = if (background.luminance() > 0.179f) Color.Black else Color.White
                            Surface(onClick = { selected = cell.key }, modifier = Modifier.weight(1f).aspectRatio(1f)
                                .testTag("charge_cell_${cell.key}").semantics { contentDescription = "${cell.label}, ${cell.display}"; this.selected = selected == cell.key },
                                shape = MaterialTheme.shapes.small,
                                color = background,
                                border = BorderStroke(if (selected == cell.key) 1.5.dp else .5.dp,
                                    if (selected == cell.key) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)) {
                                Column(Modifier.padding(horizontal = 2.dp, vertical = 3.dp), horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                    Text(cell.date?.dayOfMonth?.toString() ?: cell.label, style = MaterialTheme.typography.labelSmall, color = foreground, textAlign = TextAlign.Center)
                                    if (scale <= 1.3f) Text(chargeCellNumber(cell),
                                        style = MaterialTheme.typography.labelSmall, color = foreground, textAlign = TextAlign.Center)
                                }
                            }
                        }
                        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
        Text(selection?.let { "${it.date?.toString() ?: it.label} · ${it.display}" } ?: stringResource(R.string.ui_charge_select_cell),
            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("charge_cell_selection"))
        if (maxPage > 0) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { page--; selected = null }, enabled = page > 0) { Text(stringResource(R.string.ui_day_older)) }
            Text("${page + 1} / ${maxPage + 1}", Modifier.align(Alignment.CenterVertically))
            TextButton(onClick = { page++; selected = null }, enabled = page < maxPage) { Text(stringResource(R.string.ui_day_newer)) }
        }
    }
}
