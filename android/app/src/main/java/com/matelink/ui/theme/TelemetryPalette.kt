package com.matelink.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable

/** Telemetry presentation uses a consistent theme, independent of vehicle paint. */
@Composable
fun telemetryPalette(): CarColorPalette {
    val colors = MaterialTheme.colorScheme
    return CarColorPalette(
        surface = colors.surface,
        accent = colors.primary,
        accentDim = colors.primary.copy(alpha = 0.22f),
        onSurface = colors.onSurface,
        onSurfaceVariant = colors.onSurfaceVariant,
        progressTrack = colors.outlineVariant,
        acColor = StatusSuccess,
        dcColor = StatusWarning
    )
}
