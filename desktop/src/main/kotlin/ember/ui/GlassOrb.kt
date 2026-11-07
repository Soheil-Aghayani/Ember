package ember.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.soheil.ember.data.model.ConnectionState
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.launch

/**
 * The orb you tap to connect, drawn in code: a glass sphere with two orbit rings and glowing dots on them.
 * Off it is slate grey and nearly still; while connecting it warms up, the dots race round the rings and a bright arc
 * runs round the sphere; once connected it is fully lit, drifts slowly, and one ripple leaves it; disconnecting drains
 * the colour again. There is no glow outside the sphere, only the thin rings.
 */
@Composable
internal fun GlassOrb(
    state: ConnectionState,
    label: String,
    modifier: Modifier = Modifier,
    ringColor: Color = Color.White,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
) {
    val lit by animateFloatAsState(
        when (state) { ConnectionState.CONNECTED -> 1f; ConnectionState.CONNECTING -> 0.55f; ConnectionState.DISCONNECTED -> 0f },
        tween(if (state == ConnectionState.DISCONNECTED) 1100 else 900, easing = FastOutSlowInEasing), label = "lit",
    )
    val speed by animateFloatAsState(
        when (state) { ConnectionState.CONNECTING -> 3.2f; ConnectionState.CONNECTED -> 0.45f; ConnectionState.DISCONNECTED -> 0.12f },
        tween(800), label = "speed",
    )
    val loader by animateFloatAsState(if (state == ConnectionState.CONNECTING) 1f else 0f, tween(300), label = "loader")
    val speedNow by rememberUpdatedState(speed)
    var angle by remember { mutableFloatStateOf(0f) }
    var clock by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (last != 0L) {
                    val dt = ((now - last) / 1e9f).coerceAtMost(0.1f)
                    angle = (angle + dt * speedNow) % TWO_PI
                    clock += dt
                }
                last = now
            }
        }
    }
    val ripple = remember { Animatable(1f) }
    val pop = remember { Animatable(1f) }
    LaunchedEffect(state) {
        when (state) {
            ConnectionState.CONNECTED -> {
                pop.snapTo(0.94f)
                ripple.snapTo(0f)
                launch { ripple.animateTo(1f, tween(1200, easing = FastOutSlowInEasing)) }
                pop.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 300f))
            }
            ConnectionState.DISCONNECTED -> {
                ripple.snapTo(1f)
                pop.animateTo(0.965f, tween(160))
                pop.animateTo(1f, tween(500))
            }
            ConnectionState.CONNECTING -> ripple.snapTo(1f)
        }
    }
    val press = remember { MutableInteractionSource() }
    val pressed by press.collectIsPressedAsState()
    val pressScale by animateFloatAsState(if (pressed) 0.97f else 1f, tween(120), label = "press")

    Box(
        modifier
            .scale(pop.value * pressScale)
            .clip(CircleShape)
            .clickable(interactionSource = press, indication = null, role = Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) { drawOrb(lit, angle, clock, loader, ripple.value, ringColor) }
        icon()
    }
}

private const val TWO_PI = (2 * PI).toFloat()

private val OffStops = arrayOf(
    0f to Color(0xFF7A8292), 0.22f to Color(0xFF4A505C), 0.42f to Color(0xFF2B303B),
    0.60f to Color(0xFF222630), 0.78f to Color(0xFF3A2E2C), 1f to Color(0xFF8A4A2A),
)
private val OnStops = arrayOf(
    0f to Color(0xFF4E1CD6), 0.22f to Color(0xFF8222D6), 0.42f to Color(0xFFCE2496),
    0.60f to Color(0xFFF03446), 0.78f to Color(0xFFFF801C), 1f to Color(0xFFFFCD1E),
)

private class Ring(val tilt: Float, val a: Float, val b: Float, val dots: List<Float>, val direction: Float)

private val Rings = listOf(
    Ring(tilt = -0.42f, a = 0.47f, b = 0.13f, dots = listOf(0f, 3.4f), direction = 1f),
    Ring(tilt = 0.14f, a = 0.46f, b = 0.10f, dots = listOf(1.7f, 4.9f), direction = -0.7f),
)

private fun DrawScope.ringPoint(ring: Ring, theta: Float): Offset {
    val x = ring.a * size.minDimension * cos(theta)
    val y = ring.b * size.minDimension * sin(theta)
    val c = cos(ring.tilt)
    val s = sin(ring.tilt)
    return Offset(center.x + x * c - y * s, center.y + x * s + y * c)
}

/** One ring: the whole loop (or only its near half), a faint wide line under a thin bright one. */
private fun DrawScope.drawRing(ring: Ring, lit: Float, frontOnly: Boolean, ink: Color) {
    val path = Path()
    val steps = 72
    var open = false
    for (i in 0..steps) {
        val theta = TWO_PI * i / steps
        val near = sin(theta) >= 0f
        if (frontOnly && !near) { open = false; continue }
        val p = ringPoint(ring, theta)
        if (!open) { path.moveTo(p.x, p.y); open = true } else path.lineTo(p.x, p.y)
    }
    val core = 0.30f + 0.60f * lit
    drawPath(path, ink.copy(alpha = 0.10f * lit), style = Stroke(5.dp.toPx(), cap = StrokeCap.Round))
    drawPath(path, ink.copy(alpha = if (frontOnly) core else core * 0.65f), style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round))
}

private fun DrawScope.drawDots(ring: Ring, lit: Float, angle: Float, front: Boolean, ink: Color) {
    for (offset in ring.dots) {
        val theta = (offset + angle * ring.direction).mod(TWO_PI)
        if ((sin(theta) >= 0f) != front) continue
        val p = ringPoint(ring, theta)
        val alpha = 0.45f + 0.55f * lit
        drawCircle(ink.copy(alpha = 0.18f * lit + 0.04f), 6.dp.toPx(), p)
        drawCircle(ink.copy(alpha = alpha), 2.6.dp.toPx(), p)
    }
}

private fun DrawScope.drawOrb(lit: Float, angle: Float, clock: Float, loader: Float, ripple: Float, ink: Color) {
    val s = size.minDimension
    // drifts a little when lit, and shivers while it is still warming up
    val shiver = if (lit in 0.05f..0.95f) 0.012f * sin(clock * 9f) else 0f
    val r = s * 0.34f * (1f + 0.02f * lit * sin(clock * 1.4f) + shiver)
    val c = center

    for (ring in Rings) { drawRing(ring, lit, frontOnly = false, ink); drawDots(ring, lit, angle, front = false, ink) }

    // the sphere
    val stops = Array(OffStops.size) { OffStops[it].first to lerp(OffStops[it].second, OnStops[it].second, lit) }
    drawCircle(Brush.verticalGradient(colorStops = stops, startY = c.y - r, endY = c.y + r), r, c)
    drawCircle(
        Brush.radialGradient(listOf(Color.White.copy(alpha = 0.10f + 0.30f * lit), Color.Transparent), center = c + Offset(-0.35f * r, -0.45f * r), radius = 0.62f * r),
        r, c,
    )
    drawCircle(
        Brush.radialGradient(0f to Color.Transparent, 0.78f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.30f), center = c + Offset(0f, 0.10f * r), radius = r * 1.05f),
        r, c,
    )
    drawCircle(
        Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.22f + 0.28f * lit), Color.Transparent), startY = c.y - r, endY = c.y),
        r, c, style = Stroke(1.5.dp.toPx()),
    )

    for (ring in Rings) { drawRing(ring, lit, frontOnly = true, ink); drawDots(ring, lit, angle, front = true, ink) }

    if (loader > 0.01f) {
        val arc = r + 7.dp.toPx()
        drawArc(
            color = ink.copy(alpha = 0.85f * loader), startAngle = angle * 57.2958f * 0.9f, sweepAngle = 80f, useCenter = false,
            topLeft = Offset(c.x - arc, c.y - arc), size = Size(arc * 2, arc * 2),
            style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round),
        )
    }
    if (ripple < 1f) {
        drawCircle(Color(0xFFFFB27A).copy(alpha = 0.55f * (1f - ripple)), r * (1.02f + 0.42f * ripple), c, style = Stroke(2.5.dp.toPx()))
    }
}
