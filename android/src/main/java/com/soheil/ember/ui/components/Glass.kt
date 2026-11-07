package com.soheil.ember.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp

private val GlassShape = RoundedCornerShape(16.dp)

/** True when the theme is dark; the glass pieces tint themselves from this. */
@Composable
private fun isDarkGlass() = MaterialTheme.colorScheme.background.luminance() < 0.5f

/**
 * The backdrop the glass cards float over: the theme's background with soft orange and violet light in two corners.
 * Everything translucent in the app takes its colour from this, so changing it changes the whole look.
 */
@Composable
fun GlassBackground(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val dark = isDarkGlass()
    val base = MaterialTheme.colorScheme.background
    val warm = if (dark) Color(0xFFFF7139).copy(alpha = 0.30f) else Color(0xFFFF9A63).copy(alpha = 0.38f)
    val cool = if (dark) Color(0xFF7A55E0).copy(alpha = 0.28f) else Color(0xFFB79CFF).copy(alpha = 0.40f)
    Box(
        modifier
            .fillMaxSize()
            .background(base)
            .drawBehind {
                val reach = size.maxDimension * 0.75f
                drawRect(Brush.radialGradient(listOf(warm, Color.Transparent), center = Offset(size.width * 0.05f, size.height * 0.95f), radius = reach))
                drawRect(Brush.radialGradient(listOf(cool, Color.Transparent), center = Offset(size.width * 0.95f, size.height * 0.05f), radius = reach))
            },
    ) { content() }
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
