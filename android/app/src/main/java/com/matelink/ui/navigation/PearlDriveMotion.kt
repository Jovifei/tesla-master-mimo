package com.matelink.ui.navigation

internal enum class TopLevelIconKey {
    VEHICLE,
    ROUTE,
    ENERGY,
    ANALYSIS
}

internal object PearlDriveMotion {
    const val NavigationSelectionDurationMillis = 180
    const val RefreshDurationMillis = 450
    const val ValueTransitionDurationMillis = 220

    fun iconScale(selected: Boolean): Float = if (selected) 1.06f else 1f
}
