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
    val key: String = label, val precision: Int = 1, val approximate: Boolean = false
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
    val maximum = cells.mapNotNull { it.value?.takeIf { n -> n.isFinite() && n >= 0 } }.maxOrNull() ?: 0.0
    val selection = cells.firstOrNull { it.key == selected }
    Column(modifier.fillMaxWidth().testTag("charge_activity_grid"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.ui_charge_cells_hint), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        BoxWithConstraints {
            val scale = LocalDensity.current.fontScale
            val columns = (maxWidth.value / (if (scale > 1.3f) 76 else 52)).toInt().coerceIn(2, 7)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                shown.chunked(columns).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { cell ->
                            val intensity = chargeActivityIntensity(cell.value, maximum)
                            val background = androidx.compose.ui.graphics.lerp(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.primary, intensity)
                            // Pick the stronger actual contrast, not a threshold on chart intensity.
                            val foreground = if (background.luminance() > 0.179f) Color.Black else Color.White
                            Surface(onClick = { selected = cell.key }, modifier = Modifier.weight(1f).heightIn(min = 64.dp)
                                .testTag("charge_cell_${cell.key}").semantics { contentDescription = "${cell.label}, ${cell.display}"; this.selected = selected == cell.key },
                                shape = MaterialTheme.shapes.small,
                                color = background,
                                border = BorderStroke(if (selected == cell.key) 1.5.dp else .5.dp,
                                    if (selected == cell.key) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)) {
                                Column(Modifier.padding(horizontal = 2.dp, vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(cell.label, style = MaterialTheme.typography.labelSmall, color = foreground, textAlign = TextAlign.Center)
                                    Text(chargeCellNumber(cell),
                                        style = MaterialTheme.typography.labelMedium, color = foreground, textAlign = TextAlign.Center)
                                }
                            }
                        }
                        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
        Text(selection?.let { "${it.label} · ${it.display}" } ?: stringResource(R.string.ui_charge_select_cell),
            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("charge_cell_selection"))
        if (maxPage > 0) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { page--; selected = null }, enabled = page > 0) { Text(stringResource(R.string.ui_day_older)) }
            Text("${page + 1} / ${maxPage + 1}", Modifier.align(Alignment.CenterVertically))
            TextButton(onClick = { page++; selected = null }, enabled = page < maxPage) { Text(stringResource(R.string.ui_day_newer)) }
        }
    }
}
