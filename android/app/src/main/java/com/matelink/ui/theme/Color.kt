package com.matelink.ui.theme

import androidx.compose.ui.graphics.Color

// Neutral base colors aligned to Stitch's restrained white theme.
val NeutralPrimary = Color(0xFF171717)
val NeutralDark = Color(0xFF0F1115)

// Status colors (semantic - always fixed)
val StatusSuccess = Color(0xFF059669)
val StatusWarning = Color(0xFFF59E0B)
val StatusError = Color(0xFFDC2626)

// Light theme colors - white surface, near-black type, restrained grey dividers.
val PrimaryLight = Color(0xFF171717)
val OnPrimaryLight = Color(0xFFFFFFFF)
val PrimaryContainerLight = Color(0xFFF3F4F6)
val OnPrimaryContainerLight = Color(0xFF171717)
val SecondaryLight = Color(0xFF2C3138)
val OnSecondaryLight = Color(0xFFFFFFFF)
val SecondaryContainerLight = Color(0xFFF5F5F5)
val OnSecondaryContainerLight = Color(0xFF1F2329)
val TertiaryLight = Color(0xFF5F6670)
val OnTertiaryLight = Color(0xFFFFFFFF)
val BackgroundLight = Color(0xFFFFFFFF)
val OnBackgroundLight = Color(0xFF111315)
val SurfaceLight = Color(0xFFFFFFFF)
val OnSurfaceLight = Color(0xFF111315)
val SurfaceVariantLight = Color(0xFFF7F7F8)
val OnSurfaceVariantLight = Color(0xFF5F6368)
val OutlineLight = Color(0xFFE5E5E5)
val OutlineVariantLight = Color(0xFFE9EDF2)
val SurfaceContainerLight = Color(0xFFF9F9FA)
val SurfaceContainerHighLight = Color(0xFFF4F5F6)
val SurfaceContainerHighestLight = Color(0xFFEDEFF2)
val ErrorLight = StatusError
val OnErrorLight = Color.White

// Dark theme stays neutral and layered instead of mirroring a bright shell.
val PrimaryDark = Color(0xFFE8EAED)
val OnPrimaryDark = Color(0xFF111315)
val PrimaryContainerDark = Color(0xFF252A31)
val OnPrimaryContainerDark = Color(0xFFF3F4F6)
val SecondaryDark = Color(0xFFCDD2D8)
val OnSecondaryDark = Color(0xFF1A1D22)
val SecondaryContainerDark = Color(0xFF2B3038)
val OnSecondaryContainerDark = Color(0xFFE6E9ED)
val TertiaryDark = Color(0xFFAEB5BE)
val OnTertiaryDark = Color(0xFF171A1F)
val BackgroundDark = Color(0xFF111315)
val OnBackgroundDark = Color(0xFFF5F6F7)
val SurfaceDark = Color(0xFF15181C)
val OnSurfaceDark = Color(0xFFF5F6F7)
val SurfaceVariantDark = Color(0xFF242A31)
val OnSurfaceVariantDark = Color(0xFFB7BDC6)
val OutlineDark = Color(0xFF505862)
val OutlineVariantDark = Color(0xFF323840)
val SurfaceContainerDark = Color(0xFF191D22)
val SurfaceContainerHighDark = Color(0xFF20252B)
val SurfaceContainerHighestDark = Color(0xFF282D34)
val ErrorDark = Color(0xFFFFB4AB)
val OnErrorDark = Color(0xFF690005)

// Other colors
val BoundaryColor = Color(0xFF059669)

// Stitch "Precision Minimalist" (white-minimal Swiss) shell accents.
// Scoped to shell-level pieces (bottom navigation bar, More hub, About) so the
// entry shell reads closer to the Stitch baseline without re-theming every
// existing screen. These intentionally sit alongside (not inside) the global
// light/dark color scheme.
val SwissWhite = Color(0xFFFFFFFF)      // pure white surface
val SwissInk = Color(0xFF171717)        // near-black primary / selected state
val SwissOutline = Color(0xFFE5E5E5)     // 1px hairline divider / border
val SwissSubtle = Color(0xFFF5F5F5)      // section grouping fill / selected chip
val SwissMuted = Color(0xFF737373)       // secondary text / unselected tab

// Dark-mode counterparts for the Swiss shell accents. Resolved by [swissPalette]
// so the shell pieces adapt to dark theme instead of forcing pure white. They
// reuse the global dark scheme values to stay visually consistent with the rest
// of the app in dark mode.
val SwissWhiteDark = SurfaceDark
val SwissInkDark = OnSurfaceDark
val SwissOutlineDark = OutlineVariantDark
val SwissSubtleDark = SurfaceContainerHighDark
val SwissMutedDark = OnSurfaceVariantDark
