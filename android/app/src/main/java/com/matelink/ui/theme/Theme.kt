package com.matelink.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val MateLinkShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

private val DarkColorScheme = darkColorScheme(
    primary = PrimaryDark,
    onPrimary = OnPrimaryDark,
    primaryContainer = PrimaryContainerDark,
    onPrimaryContainer = OnPrimaryContainerDark,
    secondary = SecondaryDark,
    onSecondary = OnSecondaryDark,
    secondaryContainer = SecondaryContainerDark,
    onSecondaryContainer = OnSecondaryContainerDark,
    tertiary = TertiaryDark,
    onTertiary = OnTertiaryDark,
    background = BackgroundDark,
    onBackground = OnBackgroundDark,
    surface = SurfaceDark,
    onSurface = OnSurfaceDark,
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = OnSurfaceVariantDark,
    outline = OutlineDark,
    outlineVariant = OutlineVariantDark,
    surfaceContainer = SurfaceContainerDark,
    surfaceContainerHigh = SurfaceContainerHighDark,
    surfaceContainerHighest = SurfaceContainerHighestDark,
    error = ErrorDark,
    onError = OnErrorDark
)

private val LightColorScheme = lightColorScheme(
    primary = PrimaryLight,
    onPrimary = OnPrimaryLight,
    primaryContainer = PrimaryContainerLight,
    onPrimaryContainer = OnPrimaryContainerLight,
    secondary = SecondaryLight,
    onSecondary = OnSecondaryLight,
    secondaryContainer = SecondaryContainerLight,
    onSecondaryContainer = OnSecondaryContainerLight,
    tertiary = TertiaryLight,
    onTertiary = OnTertiaryLight,
    background = BackgroundLight,
    onBackground = OnBackgroundLight,
    surface = SurfaceLight,
    onSurface = OnSurfaceLight,
    surfaceVariant = SurfaceVariantLight,
    onSurfaceVariant = OnSurfaceVariantLight,
    outline = OutlineLight,
    outlineVariant = OutlineVariantLight,
    surfaceContainer = SurfaceContainerLight,
    surfaceContainerHigh = SurfaceContainerHighLight,
    surfaceContainerHighest = SurfaceContainerHighestLight,
    error = ErrorLight,
    onError = OnErrorLight
)

@Composable
fun MateLinkTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Keep a stable neutral palette instead of device-provided accents.
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val appearance = rememberAppearancePreferences()
    val light = if (appearance.palette == AppearancePalette.CREAM) {
        LightColorScheme.copy(
            background = Color(0xFFF3F2EE), surface = Color(0xFFFFFEFA),
            surfaceVariant = Color(0xFFF5F3ED), surfaceContainer = Color(0xFFFFFEFA),
            surfaceContainerHigh = Color(0xFFF3F0E8), surfaceContainerHighest = Color(0xFFEBE7DE),
            outlineVariant = Color(0xFFE4E0D6)
        )
    } else LightColorScheme
    val colorScheme = if (darkTheme) DarkColorScheme else light
    CompositionLocalProvider(LocalAppearance provides appearance) {
        MaterialTheme(colorScheme = colorScheme, typography = Typography, shapes = MateLinkShapes, content = content)
    }
}

/**
 * Theme-aware Swiss "Precision Minimalist" palette for the shell pieces
 * (bottom navigation bar, More hub, About). Returns the light (pure-white)
 * accents in light mode and the [SwissWhiteDark] / [SwissInkDark] / ...
 * counterparts in dark mode so the shell does not force pure white when the
 * system is in dark theme.
 */
data class SwissPalette(
    val surface: Color,
    val ink: Color,
    val outline: Color,
    val subtle: Color,
    val muted: Color
)

@Composable
fun swissPalette(darkTheme: Boolean = isSystemInDarkTheme()): SwissPalette {
    // Shell and content consume one theme, including the user's milk-white choice.
    val colors = MaterialTheme.colorScheme
    return SwissPalette(colors.surface, colors.onSurface, colors.outlineVariant,
        colors.surfaceVariant, colors.onSurfaceVariant)
}
