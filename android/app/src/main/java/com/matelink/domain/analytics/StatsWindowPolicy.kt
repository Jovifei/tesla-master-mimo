package com.matelink.domain.analytics

data class StatsWindowAvailability(
    val availableDays: Int,
    val estimated7Day: Boolean,
    val supportedWindows: List<StatsWindow>
)

enum class StatsWindow {
    DAYS_7,
    DAYS_30,
    ALL
}

object StatsWindowPolicy {

    fun resolve(availableDays: Int): StatsWindowAvailability {
        val normalizedDays = availableDays.coerceAtLeast(0)
        val supportedWindows = when {
            normalizedDays < 7 -> listOf(StatsWindow.ALL)
            normalizedDays < 30 -> listOf(StatsWindow.DAYS_7, StatsWindow.ALL)
            else -> listOf(StatsWindow.DAYS_7, StatsWindow.DAYS_30, StatsWindow.ALL)
        }

        return StatsWindowAvailability(
            availableDays = normalizedDays,
            estimated7Day = normalizedDays < 7,
            supportedWindows = supportedWindows
        )
    }
}
