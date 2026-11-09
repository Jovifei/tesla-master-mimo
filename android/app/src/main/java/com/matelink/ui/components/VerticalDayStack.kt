package com.matelink.ui.components

import android.animation.ValueAnimator
import androidx.compose.animation.core.Animatable
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

/** Only the header captures vertical gestures. The body remains an ordinary parent-scrolled list. */
@Composable
fun <T> VerticalDayStack(
    groups: List<HistoryDateGroup<T>>,
    scopeKey: String,
    modifier: Modifier = Modifier,
    content: @Composable (HistoryDateGroup<T>) -> Unit
) {
    if (groups.isEmpty()) return
    var selectedKey by rememberSaveable(scopeKey) { mutableStateOf<String?>(null) }
    val index = groups.indexOfFirst { it.dateKey == selectedKey }.takeIf { it >= 0 } ?: 0
    val current = groups[index]
    val reduce = LocalAppearance.current.reducedMotion || !ValueAnimator.areAnimatorsEnabled()
    val reveal = remember { Animatable(0f) }
    val threshold = with(LocalDensity.current) { 48.dp.toPx() }
    var drag by remember { mutableFloatStateOf(0f) }
    var direction by remember { mutableIntStateOf(1) }
    fun select(target: Int) {
        val bounded = target.coerceIn(0, groups.lastIndex)
        if (bounded != index) { direction = if (bounded > index) -1 else 1; selectedKey = groups[bounded].dateKey }
    }
    LaunchedEffect(current.dateKey, reduce) {
        reveal.snapTo(if (reduce) 0f else direction * 10f)
        if (!reduce) reveal.animateTo(0f, tween(220))
    }
    val newer = stringResource(R.string.ui_day_newer)
    val older = stringResource(R.string.ui_day_older)
    val hint = stringResource(R.string.ui_day_swipe_hint)
    val rearCount = minOf(2, groups.lastIndex - index)
    val layerHeight = (48f * LocalDensity.current.fontScale.coerceAtLeast(1f)).dp
    val shape = RoundedCornerShape(16.dp)
    Column(modifier.fillMaxWidth().testTag("vertical_day_stack")) {
        Box(Modifier.fillMaxWidth()) {
            // Back first, then middle, then the foreground. Only old date strips remain exposed.
            for (depth in rearCount downTo 1) {
                val offset = layerHeight * (rearCount - depth)
                val rear = groups[index + depth]
                Surface(onClick = { select(index + depth) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = (depth * 6).dp).padding(top = offset)
                        .height(layerHeight + 36.dp).zIndex((3 - depth).toFloat()).testTag("day_stack_rear_$depth"),
                    color = if (depth == 1) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainerHighest,
                    shape = shape, border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant)) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopStart) {
                        Text(dayStackDateLabel(rear.date), Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Surface(Modifier.fillMaxWidth().padding(top = layerHeight * rearCount).zIndex(3f)
                .graphicsLayer { translationY = reveal.value + if (reduce) 0f else (drag * 0.12f).coerceIn(-12f, 12f) },
                color = MaterialTheme.colorScheme.surface, shape = shape,
                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant)) {
                Column {
                    Column(Modifier.fillMaxWidth().testTag("day_stack_gesture")
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
                            Text(dayStackDateLabel(current.date), Modifier.weight(1f).testTag("day_stack_active_date"),
                                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            IconButton(onClick = { select(index - 1) }, enabled = index > 0, modifier = Modifier.testTag("day_stack_newer")) {
                                Icon(Icons.Default.KeyboardArrowUp, newer)
                            }
                            IconButton(onClick = { select(index + 1) }, enabled = index < groups.lastIndex, modifier = Modifier.testTag("day_stack_older")) {
                                Icon(Icons.Default.KeyboardArrowDown, older)
                            }
                        }
                        Text(hint, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    // Callback is from the current record set; no cached record is used to switch dates.
                    key(scopeKey, current.dateKey) { content(current) }
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
