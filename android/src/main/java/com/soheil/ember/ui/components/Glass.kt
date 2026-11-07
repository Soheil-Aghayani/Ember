package com.soheil.ember.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import kotlinx.coroutines.delay
import kotlin.math.sin
import kotlin.math.cos
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawBehind
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.soheil.ember.R
import androidx.compose.ui.unit.dp

private val GlassShape = RoundedCornerShape(16.dp)

/** True when the theme is dark; the glass pieces tint themselves from this. */
@Composable
private fun isDarkGlass() = MaterialTheme.colorScheme.background.luminance() < 0.5f

/**
 * The backdrop the glass cards float over: a soft picture (dark or light, from design/) behind everything.
 * Everything translucent in the app takes its colour from this, so changing it changes the whole look.
 */
@Composable
fun GlassBackground(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val dark = isDarkGlass()
    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Image(
            painterResource(if (dark) R.drawable.bg_dark else R.drawable.bg_light), contentDescription = null,
            modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
        )
        LivingLight()
        content()
    }
}

/**
 * Soft blobs of colour that drift slowly behind the glass, so the panels always have something moving to frost. It is
 * redrawn about 25 times a second (the motion is too slow to need more), and only the drawing is repeated, not the layout.
 */
@Composable
private fun LivingLight() {
    val dark = isDarkGlass()
    var phase by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val start = System.nanoTime()
        while (true) {
            phase = ((System.nanoTime() - start) / 1e9 / 70.0 * 2 * Math.PI).toFloat() // one full drift in 70 seconds
            delay(40)
        }
    }
    val violet = if (dark) Color(0xFF7A55E0).copy(alpha = 0.34f) else Color(0xFFB79CFF).copy(alpha = 0.40f)
    val ember = if (dark) Color(0xFFFF7139).copy(alpha = 0.26f) else Color(0xFFFFA070).copy(alpha = 0.38f)
    val rose = if (dark) Color(0xFFE0207F).copy(alpha = 0.16f) else Color(0xFFFF9AC0).copy(alpha = 0.26f)
    Box(
        Modifier.fillMaxSize().drawBehind {
            val reach = size.maxDimension
            fun blob(color: Color, x: Float, y: Float, radius: Float) =
                drawRect(Brush.radialGradient(listOf(color, Color.Transparent), center = Offset(size.width * x, size.height * y), radius = reach * radius))
            val t = phase
            blob(violet, 0.82f + 0.12f * sin(t), 0.16f + 0.10f * cos(t * 2f), 0.62f)
            blob(ember, 0.14f + 0.12f * cos(t * 3f), 0.86f + 0.07f * sin(t * 2f), 0.58f)
            blob(rose, 0.55f + 0.22f * sin(t * 2f + 1f), 0.52f + 0.18f * cos(t * 3f), 0.46f)
        },
    )
}

/** The tint of a glass panel: lighter at the top, with the same white fading down the edge. */
@Composable
fun glassFill(strong: Boolean = false): Brush {
    val dark = isDarkGlass()
    val top = if (dark) 0.12f else 0.72f
    val bottom = if (dark) 0.05f else 0.46f
    val k = if (strong) 1.25f else 1f
    return Brush.verticalGradient(listOf(Color.White.copy(alpha = (top * k).coerceAtMost(1f)), Color.White.copy(alpha = (bottom * k).coerceAtMost(1f))))
}

@Composable
fun glassEdge(): Brush {
    val dark = isDarkGlass()
    return Brush.verticalGradient(listOf(Color.White.copy(alpha = if (dark) 0.28f else 0.9f), Color.White.copy(alpha = if (dark) 0.06f else 0.35f)))
}

/** A frosted-glass card, used in place of Material's Card: translucent, with a thin light edge. */
@Composable
fun Card(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.clip(GlassShape).background(glassFill()).border(1.dp, glassEdge(), GlassShape).then(modifier),
        content = content,
    )
}
