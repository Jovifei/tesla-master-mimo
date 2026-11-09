package com.matelink.ui.theme

/** Presentation-only preference. Unknown/stale values fail back to the neutral white default. */
enum class AppearancePalette(val storageKey: String) {
    NEUTRAL("neutral"), CREAM("cream");

    companion object {
        fun fromStorage(value: String?): AppearancePalette =
            entries.firstOrNull { it.storageKey == value } ?: NEUTRAL
    }
}
