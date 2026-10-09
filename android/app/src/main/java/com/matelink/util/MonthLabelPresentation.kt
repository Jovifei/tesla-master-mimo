package com.matelink.util

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Format a monthly aggregate without inventing a day; retain year context across years. */
fun LocalDate.formatMonthYear(
    locale: Locale = Locale.getDefault(),
    includeYear: Boolean = true
): String {
    val month = this.month.getDisplayName(java.time.format.TextStyle.FULL, locale)
    return if (locale.language == "zh") {
        if (includeYear) "${year}年$month" else month
    } else {
        this.format(DateTimeFormatter.ofPattern(if (includeYear) "MMM yy" else "MMM", locale))
    }
}
