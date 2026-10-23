package ember.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.sp

// Values mirror design/tokens.json. Edit the tokens first, then mirror here.

val EmberFontFamily = FontFamily(
    Font("fonts/inter_regular.ttf", FontWeight.Normal),
    Font("fonts/inter_medium.ttf", FontWeight.Medium),
    Font("fonts/inter_semibold.ttf", FontWeight.SemiBold),
    Font("fonts/inter_bold.ttf", FontWeight.Bold),
)

private fun style(weight: FontWeight, size: Int, lineHeight: Int) =
    TextStyle(fontFamily = EmberFontFamily, fontWeight = weight, fontSize = size.sp, lineHeight = lineHeight.sp)

private val EmberTypography = Typography(
    headlineLarge = style(FontWeight.Bold, 28, 34),
    headlineMedium = style(FontWeight.SemiBold, 22, 28),
    headlineSmall = style(FontWeight.SemiBold, 20, 25),
    titleLarge = style(FontWeight.SemiBold, 22, 28),
    titleMedium = style(FontWeight.SemiBold, 17, 22),
    titleSmall = style(FontWeight.Medium, 16, 21),
    bodyLarge = style(FontWeight.Normal, 17, 22),
    bodyMedium = style(FontWeight.Normal, 15, 20),
    bodySmall = style(FontWeight.Normal, 13, 18),
    labelLarge = style(FontWeight.Medium, 16, 21),
    labelMedium = style(FontWeight.Medium, 13, 18),
    labelSmall = style(FontWeight.Medium, 12, 16),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFFC4400A), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFBE6DA), onPrimaryContainer = Color(0xFF5A1C00),
    secondary = Color(0xFF6A3BD1), onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFECE5FA), onSecondaryContainer = Color(0xFF2A1566),
    error = Color(0xFFBA1A1A),
    background = Color(0xFFFAF7F2), onBackground = Color(0xFF1F1B16),
    surface = Color(0xFFFFFFFF), onSurface = Color(0xFF1F1B16),
    surfaceVariant = Color(0xFFF1ECE3), onSurfaceVariant = Color(0xFF6B645B),
    outline = Color(0xFF8A8277), outlineVariant = Color(0xFFE4DDD1),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFF7139), onPrimary = Color(0xFF1A1714),
    primaryContainer = Color(0xFF3A2218), onPrimaryContainer = Color(0xFFFFD9C7),
    secondary = Color(0xFFB79CFF), onSecondary = Color(0xFF2A1566),
    secondaryContainer = Color(0xFF2C2540), onSecondaryContainer = Color(0xFFE6DCFF),
    error = Color(0xFFFFB4AB),
    background = Color(0xFF1A1714), onBackground = Color(0xFFF3EEE6),
    surface = Color(0xFF24201C), onSurface = Color(0xFFF3EEE6),
    surfaceVariant = Color(0xFF3A342D), onSurfaceVariant = Color(0xFFA39B90),
    outline = Color(0xFF80786C), outlineVariant = Color(0xFF3A342D),
)

data class EmberStatusColors(val connected: Color, val connecting: Color)

val LocalEmberStatusColors = staticCompositionLocalOf { EmberStatusColors(Color(0xFF1E7A3C), Color(0xFF8A5A00)) }

@Composable
fun EmberTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val status = if (darkTheme) EmberStatusColors(Color(0xFF5FD17F), Color(0xFFFFC46B))
    else EmberStatusColors(Color(0xFF1E7A3C), Color(0xFF8A5A00))
    CompositionLocalProvider(LocalEmberStatusColors provides status) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = EmberTypography,
            content = content,
        )
    }
}
