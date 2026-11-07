package ember.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource

@Composable
private fun isDarkGlass() = MaterialTheme.colorScheme.background.luminance() < 0.5f

/**
 * The backdrop the glass panels float over: a soft picture (dark or light, from design/) behind everything.
 * Everything translucent in the app takes its colour from this, so changing it changes the whole look.
 */
@Composable
internal fun GlassBackground(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val dark = isDarkGlass()
    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Image(
            painterResource(if (dark) "art/bg-dark.webp" else "art/bg-light.webp"), contentDescription = null,
            modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
        )
        content()
    }
}

/** The tint of a glass panel: white, lighter at the top. */
@Composable
internal fun glassFill(): Brush {
    val dark = isDarkGlass()
    return Brush.verticalGradient(listOf(Color.White.copy(alpha = if (dark) 0.12f else 0.72f), Color.White.copy(alpha = if (dark) 0.05f else 0.46f)))
}

/** The thin light edge of a glass panel. */
@Composable
internal fun glassEdge(): Brush {
    val dark = isDarkGlass()
    return Brush.verticalGradient(listOf(Color.White.copy(alpha = if (dark) 0.28f else 0.9f), Color.White.copy(alpha = if (dark) 0.06f else 0.35f)))
}
