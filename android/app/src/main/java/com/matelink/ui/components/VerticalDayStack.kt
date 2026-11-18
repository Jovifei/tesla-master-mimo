package com.matelink.ui.components

import android.animation.ValueAnimator
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.matelink.R
import com.matelink.ui.theme.LocalAppearance

/**
 * Seven calendar days in a bounded diagonal fan. The fan is presentation only:
 * the selected day keeps its real items, empty days stay explicit, and every
 * exposed card edge is tappable for accessibility as well as pointer input.
 */
@Composable
fun <T> VerticalDayStack(
    groups: List<HistoryDateGroup<T>>, scopeKey: String, modifier: Modifier = Modifier,
    content: @Composable (HistoryDateGroup<T>) -> Unit
) {
    if (groups.isEmpty()) return
    var selectedKey by rememberSaveable(scopeKey) { mutableStateOf<String?>(null) }
    val index = groups.indexOfFirst { it.dateKey == selectedKey }.takeIf { it >= 0 }
        ?: groups.indexOfFirst { it.items.isNotEmpty() }.takeIf { it >= 0 } ?: 0
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
    LaunchedEffect(scopeKey) { drag = 0f }
    fun select(target: Int) {
        val bounded = target.coerceIn(0, groups.lastIndex)
        if (bounded != index) {
            direction = if (bounded > index) -1 else 1
            selectedKey = groups[bounded].dateKey
        }
    }
    fun toggle(target: Int) { select(if (target == index) 0 else target) }
    LaunchedEffect(current.dateKey, reduce) {
        reveal.snapTo(if (reduce) 0f else direction * travel)
        if (!reduce) reveal.animateTo(0f, tween(260))
    }
    val newer = stringResource(R.string.ui_day_newer)
    val older = stringResource(R.string.ui_day_older)
    val swipeHint = stringResource(R.string.ui_day_swipe_horizontal)
    val pageStart = (index / 7) * 7
    val pageEnd = minOf(pageStart + 7, groups.size)
    val page = (pageStart until pageEnd).map { groups[it] }
    val shape = RoundedCornerShape(16.dp)
    val weekdayFormatter = java.time.format.DateTimeFormatter.ofPattern("EEE", java.util.Locale.getDefault())
    val monthFormatter = java.time.format.DateTimeFormatter.ofPattern("MMM", java.util.Locale.getDefault())

    Column(modifier.fillMaxWidth().testTag("vertical_day_stack")) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(132.dp)
                .testTag("day_stack_gesture")
                .pointerInput(scopeKey, current.dateKey, groups.map { it.dateKey }) {
                    detectHorizontalDragGestures(
                        onDragStart = { drag = 0f },
                        onDragCancel = { drag = 0f },
                        onHorizontalDrag = { change, amount -> change.consume(); drag += amount },
                        onDragEnd = {
                            select(dayStackTarget(index, groups.size, drag, threshold))
                            drag = 0f
                        }
                    )
                }
                .semantics {
                    contentDescription = swipeHint
                    customActions = listOf(
                        CustomAccessibilityAction(newer) { if (index < groups.lastIndex) { select(index + 1); true } else false },
                        CustomAccessibilityAction(older) { if (index > 0) { select(index - 1); true } else false }
                    )
                }
        ) {
            page.forEachIndexed { pageIndex, group ->
                val target = pageStart + pageIndex
                val selected = target == index
                // A horizontal fan of seven day cards: every exposed left edge
                // carries the date/count, and the selected card is lifted to the front.
                val date = group.date
                // Two lines are enough: relative day/weekday + count on the first,
                // the day number (with its month) large on the second.
                val title = when {
                    date == null -> stringResource(R.string.ui_day_unknown)
                    date == java.time.LocalDate.now() -> stringResource(R.string.ui_day_today)
                    date == java.time.LocalDate.now().minusDays(1) -> stringResource(R.string.ui_day_yesterday)
                    date == java.time.LocalDate.now().minusDays(2) -> stringResource(R.string.ui_day_before_yesterday)
                    else -> date.format(weekdayFormatter)
                }
                val countText = if (group.items.isEmpty()) stringResource(R.string.ui_day_no_activity_short)
                else stringResource(R.string.ui_day_items_count, group.items.size)
                val baseX = pageIndex * 30 + if (pageIndex > index) 78 else 0
                val x by androidx.compose.animation.core.animateDpAsState(
                    targetValue = baseX.dp,
                    animationSpec = androidx.compose.animation.core.tween(220),
                    label = "dayCardX"
                )
                Surface(
                    modifier = Modifier
                        .width(100.dp)
                        .height(96.dp)
                        .offset(x = x, y = (pageIndex * 4).dp)
                        .zIndex(if (selected) 20f else pageIndex.toFloat())
                        .graphicsLayer {
                            rotationZ = (pageIndex - 3) * 2.6f
                            if (selected) {
                                scaleX = 1.03f
                                scaleY = 1.03f
                            }
                        }
                        .clickable { toggle(target) }
                        .testTag("day_stack_rear_$target")
                        .semantics { this.selected = selected },
                    shape = RoundedCornerShape(14.dp),
                    color = if (selected) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceContainer,
                    border = BorderStroke(
                        if (selected) 1.5.dp else .5.dp,
                        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                    ),
                    shadowElevation = if (selected) 6.dp else 1.dp
                ) {
                    Column(
                        Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                title,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            Spacer(Modifier.width(3.dp))
                            Text(
                                countText,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(
                                date?.dayOfMonth?.toString() ?: "—",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                                else MaterialTheme.colorScheme.onSurface
                            )
                            if (date != null) {
                                Spacer(Modifier.width(3.dp))
                                Text(
                                    date.format(monthFormatter),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    modifier = Modifier.padding(bottom = 3.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    translationX = reveal.value + if (reduce) 0f else (drag * .08f).coerceIn(-8f, 8f)
                },
            color = MaterialTheme.colorScheme.surface,
            shape = shape,
            border = BorderStroke(.5.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        dayStackDateLabel(current.date),
                        modifier = Modifier.weight(1f).testTag("day_stack_active_date")
                            .semantics { liveRegion = LiveRegionMode.Polite },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    IconButton(
                        onClick = { select(index - 1) },
                        enabled = index > 0,
                        modifier = Modifier.testTag("day_stack_newer")
                    ) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, newer) }
                    IconButton(
                        onClick = { select(index + 1) },
                        enabled = index < groups.lastIndex,
                        modifier = Modifier.testTag("day_stack_older")
                    ) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, older) }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                key(scopeKey, current.dateKey) {
                    if (current.items.isEmpty()) {
                        Text(
                            stringResource(R.string.ui_day_no_returned_records),
                            Modifier.padding(20.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else content(current)
                }
                if (index != 0) {
                    TextButton(onClick = { select(0) }, modifier = Modifier.fillMaxWidth().testTag("day_stack_close")) {
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