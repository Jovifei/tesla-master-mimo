package com.matelink.ui.components

import android.animation.ValueAnimator
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.matelink.R
import com.matelink.ui.theme.LocalAppearance

/** Seven exposed date cards, with reversible selection. Only the header consumes vertical drags. */
@Composable
fun <T> VerticalDayStack(
    groups: List<HistoryDateGroup<T>>, scopeKey: String, modifier: Modifier = Modifier,
    content: @Composable (HistoryDateGroup<T>) -> Unit
) {
    if (groups.isEmpty()) return
    var selectedKey by rememberSaveable(scopeKey) { mutableStateOf<String?>(null) }
    val index = groups.indexOfFirst { it.dateKey == selectedKey }.takeIf { it >= 0 } ?: 0
    val current = groups[index]
    LaunchedEffect(scopeKey, groups.map { it.dateKey }) {
        if (selectedKey != null && groups.none { it.dateKey == selectedKey }) selectedKey = null
    }
    val reduce = LocalAppearance.current.reducedMotion || !ValueAnimator.areAnimatorsEnabled()
    val reveal = remember { Animatable(0f) }
    val threshold = with(LocalDensity.current) { 48.dp.toPx() }
    val travel = with(LocalDensity.current) { 28.dp.toPx() }
    var drag by remember { mutableFloatStateOf(0f) }
    var direction by remember { mutableIntStateOf(1) }
    fun select(target: Int) {
        val bounded = target.coerceIn(0, groups.lastIndex)
        if (bounded != index) { direction = if (bounded > index) -1 else 1; selectedKey = groups[bounded].dateKey }
    }
    fun toggle(target: Int) { select(if (target == index) 0 else target) }
    LaunchedEffect(current.dateKey, reduce) {
        reveal.snapTo(if (reduce) 0f else direction * travel)
        if (!reduce) reveal.animateTo(0f, tween(260))
    }
    val newer = stringResource(R.string.ui_day_newer)
    val older = stringResource(R.string.ui_day_older)
    val hint = stringResource(R.string.ui_day_swipe_hint)
    val pageStart = (index / 7) * 7
    val pageEnd = minOf(pageStart + 7, groups.size)
    val rear = (pageStart until pageEnd).filter { it != 0 }
    val layerHeight = (44f * LocalDensity.current.fontScale.coerceAtLeast(1f)).dp
    val shape = RoundedCornerShape(16.dp)
    Column(modifier.fillMaxWidth().testTag("vertical_day_stack")) {
        Box(Modifier.fillMaxWidth().animateContentSize(if (reduce) snap() else tween(260))) {
            // Farther dates are drawn first. Every date in the selected week remains reachable.
            rear.reversed().forEachIndexed { position, target ->
                val group = groups[target]
                Surface(onClick = { toggle(target) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = ((rear.size - position).coerceAtMost(6) * 3).dp)
                        .padding(top = layerHeight * position).height(layerHeight + 24.dp)
                        .zIndex(position.toFloat()).testTag("day_stack_rear_$target")
                        .semantics { selected = target == index },
                    color = if (target == index) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainer,
                    shape = shape, border = BorderStroke(.5.dp, MaterialTheme.colorScheme.outlineVariant)) {
                    Text(dayStackDateLabel(group.date), Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Surface(Modifier.fillMaxWidth().padding(top = layerHeight * rear.size).zIndex(8f)
                .graphicsLayer { translationY = reveal.value + if (reduce) 0f else (drag * .12f).coerceIn(-12f, 12f) },
                color = MaterialTheme.colorScheme.surface, shape = shape,
                border = BorderStroke(.5.dp, MaterialTheme.colorScheme.outlineVariant), shadowElevation = if (index == 0) 0.dp else 4.dp) {
                Column {
                    Column(Modifier.fillMaxWidth().heightIn(min = 96.dp).testTag("day_stack_gesture")
                        .semantics {
                            contentDescription = hint
                            customActions = listOf(
                                CustomAccessibilityAction(newer) { if (index > 0) { select(index - 1); true } else false },
                                CustomAccessibilityAction(older) { if (index < groups.lastIndex) { select(index + 1); true } else false })
                        }
                        .pointerInput(scopeKey, current.dateKey, groups.map { it.dateKey }) {
                            detectVerticalDragGestures(onDragStart = { drag = 0f }, onDragCancel = { drag = 0f },
                                onVerticalDrag = { change, amount -> change.consume(); drag += amount },
                                onDragEnd = { select(dayStackTarget(index, groups.size, drag, threshold)); drag = 0f })
                        }.padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(dayStackDateLabel(current.date), Modifier.weight(1f).testTag("day_stack_active_date")
                                .semantics { liveRegion = LiveRegionMode.Polite },
                                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            IconButton(onClick = { select(index - 1) }, enabled = index > 0, modifier = Modifier.testTag("day_stack_newer")) { Icon(Icons.Default.KeyboardArrowUp, newer) }
                            IconButton(onClick = { select(index + 1) }, enabled = index < groups.lastIndex, modifier = Modifier.testTag("day_stack_older")) { Icon(Icons.Default.KeyboardArrowDown, older) }
                        }
                        Text(hint, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    key(scopeKey, current.dateKey) {
                        if (current.items.isEmpty()) Text(stringResource(R.string.ui_day_no_returned_records),
                            Modifier.padding(20.dp), style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        else content(current)
                    }
                    if (index != 0) TextButton(onClick = { select(0) }, modifier = Modifier.fillMaxWidth().testTag("day_stack_close")) {
                        Text(stringResource(R.string.ui_day_collapse, dayStackDateLabel(groups[0].date)))
                    }
                }
            }
        }
    }
}

@Composable
private fun dayStackDateLabel(date: java.time.LocalDate?): String {
    if (date == null) return stringResource(R.string.ui_day_unknown)
    val today = java.time.LocalDate.now()
    val relative = when (date) {
        today -> stringResource(R.string.ui_day_today)
        today.minusDays(1) -> stringResource(R.string.ui_day_yesterday)
        today.minusDays(2) -> stringResource(R.string.ui_day_before_yesterday)
        else -> ""
    }
    return listOf(relative, date.toString()).filter { it.isNotBlank() }.joinToString(" · ")
}
