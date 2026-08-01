package com.matelink.ui.screens.reports

/** Returns report years without hiding the current or immediately previous year. */
fun availableReportYears(currentYear: Int, historicalYears: Iterable<Int>): List<Int> =
    (historicalYears + listOf(currentYear, currentYear - 1))
        .filter { it > 0 }
        .distinct()
        .sortedDescending()
