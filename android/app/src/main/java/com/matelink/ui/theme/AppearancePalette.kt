package com.matelink.ui.theme

/** Presentation-only preference. Unknown/stale values fail back to the neutral white default. */
enum class AppearancePalette(val storageKey: String) {
    NEUTRAL("neutral"), CREAM("cream");

    companion object {
        fun fromStorage(value: String?): AppearancePalette =
            entries.firstOrNull { it.storageKey == value } ?: NEUTRAL
    }
}

/** Horizontal avoids stealing normal list scrolling. Vertical is an explicit preference. */
enum class DayStackAxis(val storageKey: String) {
    HORIZONTAL("horizontal"), VERTICAL("vertical");
    companion object {
        fun fromStorage(value: String?): DayStackAxis =
            entries.firstOrNull { it.storageKey == value } ?: HORIZONTAL
    }
}
