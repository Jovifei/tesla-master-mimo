package com.matelink.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.matelink.R
import com.matelink.ui.theme.AppleTokens
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

data class HistoryDateGroup<T>(
    val date: LocalDate?,
    val dateKey: String,
    val items: List<T>
)

@Composable
fun formatGroupDateHeader(date: LocalDate?, today: LocalDate = LocalDate.now()): String {
    if (date == null) return stringResource(R.string.unknown)
    return when {
        date == today -> stringResource(R.string.filter_today)
        date == today.minusDays(1) -> stringResource(R.string.sentry_history_yesterday)
        date.year == today.year -> {
            val dayOfWeek = date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
            val pattern = if (Locale.getDefault().language == "zh") "M月d日" else "MMM d"
            val monthDay = date.format(DateTimeFormatter.ofPattern(pattern, Locale.getDefault()))
            "$monthDay · $dayOfWeek"
        }
        else -> {
            val dayOfWeek = date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
            val pattern = if (Locale.getDefault().language == "zh") "yyyy年M月d日" else "MMM d, yyyy"
            val fullDate = date.format(DateTimeFormatter.ofPattern(pattern, Locale.getDefault()))
            "$fullDate · $dayOfWeek"
        }
    }
}

@Composable
fun AppleDateGroupHeader(
    date: LocalDate?,
    modifier: Modifier = Modifier,
    today: LocalDate = LocalDate.now()
) {
    Text(
        text = formatGroupDateHeader(date, today),
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(
            start = AppleTokens.groupHeaderStart,
            top = AppleTokens.groupHeaderTop,
            bottom = AppleTokens.groupHeaderBottom
        )
    )
}
