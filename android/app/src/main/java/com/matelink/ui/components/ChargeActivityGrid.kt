package com.matelink.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.matelink.R
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale

data class ChargeActivityCell(
    val date: LocalDate,
    val value: Double?,
    val display: String,
    val hasRecord: Boolean = true,
    val isPartial: Boolean = false
)

internal const val FULL_CHARGE_HEATMAP_KWH = 60.0

internal fun chargeActivityIntensity(value: Double?, maximum: Double = FULL_CHARGE_HEATMAP_KWH): Float {
    if (value == null || !value.isFinite() || value <= 0.0 || !maximum.isFinite() || maximum <= 0.0) return 0f
    return (0.12 + 0.76 * (value / maximum).coerceAtMost(1.0)).toFloat()
}

internal fun chargeActivityWeekStart(date: LocalDate): LocalDate =
    date.minusDays((date.dayOfWeek.value - DayOfWeek.MONDAY.value).toLong())

internal fun recentChargeCalendar(end: LocalDate): ClosedRange<LocalDate> =
    YearMonth.from(end).minusMonths(4).atDay(1)..YearMonth.from(end).atEndOfMonth()

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ChargeActivityGrid(
    cells: List<ChargeActivityCell>,
    modifier: Modifier = Modifier,
    endDate: LocalDate = cells.maxOfOrNull { it.date } ?: LocalDate.now()
) {
    val locale = Locale.getDefault()
    val dateFormat = remember(locale) { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale) }
    val recent = recentChargeCalendar(endDate)
    val calendarStart = minOf(cells.minOfOrNull { it.date } ?: recent.start, recent.start)
    val firstWeek = chargeActivityWeekStart(calendarStart)
    val lastWeek = chargeActivityWeekStart(recent.endInclusive)
    val recentWeekCount = ChronoUnit.WEEKS.between(chargeActivityWeekStart(recent.start), lastWeek).toInt() + 1
    val weeks = remember(firstWeek, lastWeek) {
        generateSequence(lastWeek) { week -> week.takeIf { it > firstWeek }?.minusWeeks(1) }.toList()
    }
    val byDate = remember(cells) { cells.associateBy { it.date } }
    var selectedDate by remember(calendarStart, endDate) { mutableStateOf<LocalDate?>(null) }
    val colors = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.extraSmall
    val emptyCell = lerp(colors.surface, colors.onSurface, 0.11f)
    val outOfRangeCell = lerp(colors.surface, colors.onSurface, 0.075f)
    val gap = 2.dp
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    val monthHeight = (18 * fontScale).dp
    val dayRailWidth = (18 * fontScale).dp
    val outsideWindow = stringResource(R.string.charge_heatmap_outside_range)

    Column(modifier.fillMaxWidth().testTag("charge_activity_grid"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
        val square = with(LocalDensity.current) {
            val room = maxWidth.roundToPx() - dayRailWidth.roundToPx() - 4.dp.roundToPx()
            // Quantize before the week layout so repeated pixel rounding cannot crop the first column.
            ((room - gap.roundToPx() * (recentWeekCount - 1)) / recentWeekCount)
                .coerceAtLeast(5.dp.roundToPx()).toDp()
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Column(
                modifier = Modifier.width(dayRailWidth),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(gap)
            ) {
                Spacer(Modifier.height(monthHeight))
                DayOfWeek.values().forEach { day ->
                    Box(Modifier.height(square), contentAlignment = Alignment.CenterEnd) {
                        if (day in listOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY, DayOfWeek.SUNDAY)) {
                            Text(day.getDisplayName(TextStyle.NARROW_STANDALONE, locale),
                                style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant,
                                maxLines = 1, modifier = Modifier.wrapContentHeight(unbounded = true))
                        }
                    }
                }
            }
            LazyRow(
                modifier = Modifier.weight(1f),
                reverseLayout = true,
                horizontalArrangement = Arrangement.spacedBy(gap)
            ) {
                items(weeks, key = { it.toEpochDay() }) { week ->
                    val month = (0L..6L).map(week::plusDays)
                        .firstOrNull { it.dayOfMonth == 1 && it in calendarStart..recent.endInclusive }?.month
                        ?: calendarStart.month.takeIf { week == firstWeek }
                    Column(Modifier.width(square), verticalArrangement = Arrangement.spacedBy(gap)) {
                        Box(Modifier.fillMaxWidth().height(monthHeight), contentAlignment = Alignment.CenterStart) {
                            month?.let {
                                Text(it.getDisplayName(TextStyle.SHORT_STANDALONE, locale),
                                    style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant,
                                    maxLines = 1, modifier = Modifier.wrapContentWidth(Alignment.Start, unbounded = true))
                            }
                        }
                        repeat(7) { offset ->
                            val date = week.plusDays(offset.toLong())
                            val cell = byDate[date]
                            if (date < calendarStart || date > recent.endInclusive) {
                                Spacer(Modifier.size(square))
                            } else {
                                val isSelected = selectedDate == date
                                val label = date.format(dateFormat) + " · " + (cell?.display ?: outsideWindow)
                                val fill = if (cell == null) outOfRangeCell
                                    else lerp(emptyCell, colors.primary, chargeActivityIntensity(cell.value))
                                val edge = when {
                                    isSelected -> colors.primary
                                    cell?.value == 0.0 -> colors.outline
                                    cell == null -> colors.outlineVariant.copy(alpha = 0.3f)
                                    else -> colors.outlineVariant
                                }
                                Box(
                                    Modifier.size(square)
                                        .clip(shape)
                                        .background(fill)
                                        .border(if (isSelected) 2.dp else 0.5.dp, edge, shape)
                                        .testTag("charge_cell_" + date)
                                        .clickable(role = Role.Button) { selectedDate = date }
                                        .semantics {
                                            contentDescription = label
                                            selected = isSelected
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    val marker = when {
                                        cell == null -> ""
                                        cell.value == null && cell.hasRecord -> "—"
                                        cell.value == 0.0 && cell.isPartial -> "≈0"
                                        cell.value == 0.0 -> "0"
                                        else -> ""
                                    }
                                    if (marker.isNotEmpty()) {
                                        Text(marker, style = MaterialTheme.typography.labelSmall,
                                            color = colors.onSurfaceVariant, textAlign = TextAlign.Center,
                                            modifier = Modifier.wrapContentSize(unbounded = true))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        }
        Row(
            Modifier.align(Alignment.End),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(stringResource(R.string.ui_charge_heatmap_less), style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant)
            listOf(15.0, 30.0, 45.0, FULL_CHARGE_HEATMAP_KWH).forEach { level ->
                Box(Modifier.size(10.dp).clip(MaterialTheme.shapes.extraSmall)
                    .background(lerp(emptyCell, colors.primary, chargeActivityIntensity(level))))
            }
            Text(stringResource(R.string.ui_charge_heatmap_more), style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant)
        }
        Text(selectedDate?.let { it.format(dateFormat) + " · " + (byDate[it]?.display ?: outsideWindow) }
            ?: stringResource(R.string.ui_charge_select_cell),
            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("charge_cell_selection"))
    }
}
