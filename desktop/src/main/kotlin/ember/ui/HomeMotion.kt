package ember.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.soheil.ember.data.formatBytesPerSecond
import com.soheil.ember.data.model.ConnectionState
import ember.engine.TrafficStats

/**
 * The big glass orb. Idle it is dim and still; while connecting it pulses and ripens to full colour; once connected it
 * breathes slowly, and a single ripple leaves it the moment the connection comes up.
 */
@Composable
internal fun ConnectOrb(
    state: ConnectionState,
    ring: Color,
    popScale: Float,
    label: String,
    onClick: () -> Unit,
) {
    val transition = rememberInfiniteTransition(label = "orb")
    val breathe by transition.animateFloat(
        0f, 1f, infiniteRepeatable(tween(2200, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "breathe",
    )
    val pulse by transition.animateFloat(
        0f, 1f, infiniteRepeatable(tween(700, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "pulse",
    )
    val connected = state == ConnectionState.CONNECTED
    // 0 → 1 is the ripple leaving the orb; 1 means it is gone.
    val ripple = remember { Animatable(1f) }
    LaunchedEffect(state) {
        if (connected) {
            ripple.snapTo(0f)
            ripple.animateTo(1f, tween(1000, easing = FastOutSlowInEasing))
        } else {
            ripple.snapTo(1f)
        }
    }
    val motion = when (state) {
        ConnectionState.CONNECTED -> 1f + 0.035f * breathe
        ConnectionState.CONNECTING -> 1f + 0.05f * pulse
        ConnectionState.DISCONNECTED -> 1f
    }

    Box(
        Modifier.size(260.dp)
            .drawBehind {
                if (ripple.value < 1f) {
                    val radius = size.minDimension / 2 * (0.78f + 0.45f * ripple.value)
                    drawCircle(ring.copy(alpha = 0.4f * (1f - ripple.value)), radius, style = Stroke(3.dp.toPx()))
                }
            }
            .scale(popScale * motion),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.fillMaxSize().clip(CircleShape)
                .pointerHoverIcon(PointerIcon.Hand).clickable(role = Role.Button, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Crossfade(state, animationSpec = tween(450), label = "orb-picture") { shown ->
                Image(
                    painterResource(
                        when (shown) {
                            ConnectionState.CONNECTED -> "art/orb-on.webp"
                            ConnectionState.CONNECTING -> "art/orb-connecting.webp"
                            ConnectionState.DISCONNECTED -> "art/orb-off.webp"
                        },
                    ),
                    contentDescription = label, modifier = Modifier.fillMaxSize(),
                )
            }
            EmberIcon("power", size = 44.dp, tint = Color.White.copy(alpha = 0.92f))
        }
    }
}

/** Download and upload speed that glide between readings, with the last minute of download speed as a line. */
@Composable
internal fun SpeedReadout(traffic: TrafficStats) {
    val reading by traffic.readings.collectAsState()
    if (!reading.available) return
    val download by animateFloatAsState(reading.downloadSpeed.toFloat(), tween(900), label = "download")
    val upload by animateFloatAsState(reading.uploadSpeed.toFloat(), tween(900), label = "upload")
    val history = remember { mutableStateListOf<Float>() }
    LaunchedEffect(reading) {
        history.add(reading.downloadSpeed.toFloat())
        while (history.size > 30) history.removeAt(0)
    }
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(8.dp))
            Text(
                "↓ ${formatBytesPerSecond(download.toLong())}   ↑ ${formatBytesPerSecond(upload.toLong())}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (history.size >= 3) {
                Spacer(Modifier.height(6.dp))
                Sparkline(history.toList(), MaterialTheme.colorScheme.tertiary)
            }
        }
    }
}

@Composable
private fun Sparkline(values: List<Float>, color: Color) {
    Canvas(Modifier.width(160.dp).height(28.dp)) {
        val top = (values.maxOrNull() ?: 0f).coerceAtLeast(1f)
        val step = size.width / (values.size - 1)
        fun y(value: Float) = size.height - (value / top) * (size.height - 4.dp.toPx()) - 2.dp.toPx()
        val line = Path().apply {
            moveTo(0f, y(values[0]))
            for (i in 1 until values.size) {
                // a soft curve through the points rather than straight segments
                val previous = Offset((i - 1) * step, y(values[i - 1]))
                val next = Offset(i * step, y(values[i]))
                val middle = (previous.x + next.x) / 2
                cubicTo(middle, previous.y, middle, next.y, next.x, next.y)
            }
        }
        val area = Path().apply {
            addPath(line)
            lineTo(size.width, size.height)
            lineTo(0f, size.height)
            close()
        }
        drawPath(area, Brush.verticalGradient(listOf(color.copy(alpha = 0.22f), color.copy(alpha = 0f))))
        drawPath(line, color, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}
