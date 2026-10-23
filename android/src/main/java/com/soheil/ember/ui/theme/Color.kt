package com.soheil.ember.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// Generated from design/tokens.json. Edit tokens first, then mirror here.

val md_theme_light_primary = Color(0xFFC4400A)
val md_theme_light_onPrimary = Color(0xFFFFFFFF)
val md_theme_light_primaryContainer = Color(0xFFFBE6DA)
val md_theme_light_onPrimaryContainer = Color(0xFF5A1C00)
val md_theme_light_inversePrimary = Color(0xFFFF8A55)
val md_theme_light_secondary = Color(0xFF6A3BD1)
val md_theme_light_onSecondary = Color(0xFFFFFFFF)
val md_theme_light_secondaryContainer = Color(0xFFECE5FA)
val md_theme_light_onSecondaryContainer = Color(0xFF2A1566)
val md_theme_light_tertiary = Color(0xFF1E7A3C)
val md_theme_light_onTertiary = Color(0xFFFFFFFF)
val md_theme_light_tertiaryContainer = Color(0xFFD8F0DE)
val md_theme_light_onTertiaryContainer = Color(0xFF08331A)
val md_theme_light_error = Color(0xFFBA1A1A)
val md_theme_light_onError = Color(0xFFFFFFFF)
val md_theme_light_errorContainer = Color(0xFFFFDAD6)
val md_theme_light_onErrorContainer = Color(0xFF410002)
val md_theme_light_background = Color(0xFFFAF7F2)
val md_theme_light_onBackground = Color(0xFF1F1B16)
val md_theme_light_surface = Color(0xFFFAF7F2)
val md_theme_light_onSurface = Color(0xFF1F1B16)
val md_theme_light_surfaceVariant = Color(0xFFF1ECE3)
val md_theme_light_onSurfaceVariant = Color(0xFF6B645B)
val md_theme_light_surfaceContainerLowest = Color(0xFFFFFFFF)
val md_theme_light_surfaceContainerLow = Color(0xFFF6F2EB)
val md_theme_light_surfaceContainer = Color(0xFFF3EEE6)
val md_theme_light_surfaceContainerHigh = Color(0xFFFFFFFF)
val md_theme_light_surfaceContainerHighest = Color(0xFFEDE6DA)
val md_theme_light_surfaceBright = Color(0xFFFAF7F2)
val md_theme_light_surfaceDim = Color(0xFFE8E1D5)
val md_theme_light_outline = Color(0xFF8A8277)
val md_theme_light_outlineVariant = Color(0xFFE4DDD1)
val md_theme_light_inverseSurface = Color(0xFF2B2621)
val md_theme_light_inverseOnSurface = Color(0xFFF3EEE6)
val md_theme_light_scrim = Color(0xFF000000)
val md_theme_light_surfaceTint = md_theme_light_primary

val md_theme_dark_primary = Color(0xFFFF7139)
val md_theme_dark_onPrimary = Color(0xFF1A1714)
val md_theme_dark_primaryContainer = Color(0xFF3A2218)
val md_theme_dark_onPrimaryContainer = Color(0xFFFFD9C7)
val md_theme_dark_inversePrimary = Color(0xFFC4400A)
val md_theme_dark_secondary = Color(0xFFB79CFF)
val md_theme_dark_onSecondary = Color(0xFF2A1566)
val md_theme_dark_secondaryContainer = Color(0xFF2C2540)
val md_theme_dark_onSecondaryContainer = Color(0xFFE6DCFF)
val md_theme_dark_tertiary = Color(0xFF5FD17F)
val md_theme_dark_onTertiary = Color(0xFF08331A)
val md_theme_dark_tertiaryContainer = Color(0xFF17391F)
val md_theme_dark_onTertiaryContainer = Color(0xFFC5F0D2)
val md_theme_dark_error = Color(0xFFFFB4AB)
val md_theme_dark_onError = Color(0xFF690005)
val md_theme_dark_errorContainer = Color(0xFF93000A)
val md_theme_dark_onErrorContainer = Color(0xFFFFDAD6)
val md_theme_dark_background = Color(0xFF1A1714)
val md_theme_dark_onBackground = Color(0xFFF3EEE6)
val md_theme_dark_surface = Color(0xFF1A1714)
val md_theme_dark_onSurface = Color(0xFFF3EEE6)
val md_theme_dark_surfaceVariant = Color(0xFF3A342D)
val md_theme_dark_onSurfaceVariant = Color(0xFFA39B90)
val md_theme_dark_surfaceContainerLowest = Color(0xFF14110F)
val md_theme_dark_surfaceContainerLow = Color(0xFF1F1B17)
val md_theme_dark_surfaceContainer = Color(0xFF24201C)
val md_theme_dark_surfaceContainerHigh = Color(0xFF2C2722)
val md_theme_dark_surfaceContainerHighest = Color(0xFF36302A)
val md_theme_dark_surfaceBright = Color(0xFF3E3731)
val md_theme_dark_surfaceDim = Color(0xFF1A1714)
val md_theme_dark_outline = Color(0xFF80786C)
val md_theme_dark_outlineVariant = Color(0xFF3A342D)
val md_theme_dark_inverseSurface = Color(0xFFF3EEE6)
val md_theme_dark_inverseOnSurface = Color(0xFF2B2621)
val md_theme_dark_scrim = Color(0xFF000000)
val md_theme_dark_surfaceTint = md_theme_dark_primary

data class EmberStatusColors(
    val connected: Color,
    val connecting: Color,
    val disconnected: Color,
)

val LightStatusColors = EmberStatusColors(
    connected = Color(0xFF1E7A3C),
    connecting = Color(0xFF8A5A00),
    disconnected = md_theme_light_onSurfaceVariant,
)

val DarkStatusColors = EmberStatusColors(
    connected = Color(0xFF5FD17F),
    connecting = Color(0xFFFFC46B),
    disconnected = md_theme_dark_onSurfaceVariant,
)

val LocalEmberStatusColors = staticCompositionLocalOf { LightStatusColors }
