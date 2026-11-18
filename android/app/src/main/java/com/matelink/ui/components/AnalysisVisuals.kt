package com.matelink.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/** Semantic colors used by analysis pages; hue carries meaning, not decoration. */
object AnalysisColors {
    val Battery = Color(0xFF10B981)
    val Energy = Color(0xFF14B8A6)
    val Cost = Color(0xFFF59E0B)
    val Mileage = Color(0xFF3B82F6)
    val Range = Color(0xFF6366F1)
    val Standby = Color(0xFF8B5CF6)
    val Annual = Color(0xFF0EA5E9)
    val Warning = Color(0xFFF97316)
    val Unknown = Color(0xFF94A3B8)
}

/** A light, theme-aware accent wash behind an analysis card. */
@Composable
fun analysisSurfaceBrush(accent: Color): Brush = Brush.verticalGradient(
    colors = listOf(
        accent.copy(alpha = 0.16f),
        accent.copy(alpha = 0.05f),
        MaterialTheme.colorScheme.surface
    )
)