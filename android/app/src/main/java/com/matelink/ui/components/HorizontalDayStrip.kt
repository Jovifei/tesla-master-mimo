package com.matelink.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.matelink.R
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * One-line horizontal date strip. Replaces the oversized diagonal fan: the user
 * sees the selected day and the trip list immediately, and swipes sideways only
 * for another day.
 */
@Composable
fun <T> HorizontalDayStrip(
    groups: List<HistoryDateGroup<T>>,
    scopeKey: String,
    modifier: Modifier = Modifier,
    content: @Composable (HistoryDateGroup<T>) -> Unit
) {
    if (groups.isEmpty()) return
    var selectedKey by rememberSaveable(scopeKey) { mutableStateOf<String?>(null) }
    val index = groups.indexOfFirst { it.dateKey == selectedKey }.takeIf { it >= 0 }
        ?: groups.indexOfFirst { it.items.isNotEmpty() }.takeIf { it >= 0 } ?: 0
    val current = groups[index]
    val weekdayFormatter = remember { DateTimeFormatter.ofPattern("EEE", Locale.getDefault()) }
    val chipShape = RoundedCornerShape(10.dp)

    @Composable
    fun label(date: LocalDate?): String = when {
        date == null -> stringResource(R.string.ui_day_unknown)
        date == LocalDate.now() -> stringResource(R.string.ui_day_today)
        date == LocalDate.now().minusDays(1) -> stringResource(R.string.ui_day_yesterday)
        date == LocalDate.now().minusDays(2) -> stringResource(R.string.ui_day_before_yesterday)
        else -> date.format(weekdayFormatter)
    }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        LazyRow(
            modifier = Modifier.fillMaxWidth().testTag("day_strip"),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            itemsIndexed(groups, key = { _, group -> group.dateKey }) { i, group ->
                val selected = i == index
                Surface(
                    onClick = { selectedKey = group.dateKey },
                    modifier = Modifier.width(68.dp).height(56.dp).testTag("day_strip_$i"),
                    shape = chipShape,
                    color = if (selected) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceContainer,
                    border = BorderStroke(
                        if (selected) 1.5.dp else .5.dp,
                        if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outlineVariant
                    )
                ) {
                    Column(
                        Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                label(group.date),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(Modifier.width(3.dp))
                            Text(
                                if (group.items.isEmpty()) "—" else group.items.size.toString(),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            group.date?.let { "${it.monthValue}/${it.dayOfMonth}" } ?: "",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                }
            }
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(.5.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${label(current.date)} · ${current.date ?: stringResource(R.string.ui_day_unknown)}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        onClick = { if (index > 0) selectedKey = groups[index - 1].dateKey },
                        enabled = index > 0
                    ) { Text("‹") }
                    TextButton(
                        onClick = { if (index < groups.lastIndex) selectedKey = groups[index + 1].dateKey },
                        enabled = index < groups.lastIndex
                    ) { Text("›") }
                }
                if (current.items.isEmpty()) {
                    Text(
                        stringResource(R.string.ui_day_no_returned_records),
                        Modifier.padding(horizontal = 12.dp, vertical = 16.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else content(current)
            }
        }
    }
}