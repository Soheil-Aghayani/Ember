package com.soheil.ember.ui

import com.soheil.ember.ui.components.glassEdge
import com.soheil.ember.ui.components.glassFill
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.soheil.ember.data.model.ConnectionState
import com.soheil.ember.engine.AndroidEngine
import com.soheil.ember.engine.VpnState
import com.soheil.ember.ui.components.FlagIcon
import ember.subs.AUTO_NODE
import java.util.Locale
import kotlinx.coroutines.delay

/** The Connect button, how long the connection has been up, and the server it uses. */
@Composable
fun HomeScreen(
    engine: AndroidEngine,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onOpenServers: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = LocalStrings.current
    val phase by VpnState.phase.collectAsState()
    val progress by VpnState.progress.collectAsState()
    val error by VpnState.error.collectAsState()
    val connectedAt by VpnState.connectedAt.collectAsState()
    val connectedNode by VpnState.node.collectAsState()
    val selectedId by engine.selectedId.collectAsState()
    val results by engine.subscriptions.results.collectAsState()
    engine.subscriptions.subscriptions.collectAsState().value // the best server changes when subscriptions do
    val node = connectedNode ?: engine.selectedNode()

    var elapsed by remember { mutableLongStateOf(0L) }
    LaunchedEffect(phase, connectedAt) {
        val start = connectedAt
        if (phase == ConnectionState.CONNECTED && start != null) {
            while (true) {
                elapsed = ((System.currentTimeMillis() - start) / 1000).coerceAtLeast(0)
                delay(1000)
            }
        } else elapsed = 0
    }

    val ring = when (phase) {
        ConnectionState.CONNECTED -> MaterialTheme.colorScheme.tertiary
        ConnectionState.CONNECTING -> MaterialTheme.colorScheme.secondary
        ConnectionState.DISCONNECTED -> MaterialTheme.colorScheme.primary
    }
    Column(modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Orb(phase, ring) {
            when {
                phase != ConnectionState.DISCONNECTED -> onDisconnect()
                node == null -> onOpenServers()
                else -> onConnect()
            }
        }
        Spacer(Modifier.height(24.dp))
        Text(
            when (phase) {
                ConnectionState.CONNECTED -> s.connected
                ConnectionState.CONNECTING -> s.connecting
                ConnectionState.DISCONNECTED -> s.disconnected
            },
            style = MaterialTheme.typography.headlineMedium,
        )
        val detail = when (phase) {
            ConnectionState.CONNECTED -> String.format(Locale.US, "%02d:%02d:%02d", elapsed / 3600, elapsed % 3600 / 60, elapsed % 60)
            ConnectionState.CONNECTING -> progress
            ConnectionState.DISCONNECTED -> null
        }
        detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        error?.takeIf { phase == ConnectionState.DISCONNECTED }?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(32.dp))
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                .background(glassFill())
                .border(1.dp, glassEdge(), RoundedCornerShape(16.dp))
                .clickable(enabled = phase == ConnectionState.DISCONNECTED, onClick = onOpenServers)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            node?.flagCode?.let {
                FlagIcon(it, 24.dp)
                Spacer(Modifier.width(12.dp))
            }
            Text(
                when {
                    node == null -> s.chooseServer
                    selectedId == AUTO_NODE && phase == ConnectionState.DISCONNECTED -> s.fastestWithServer(node.title)
                    else -> node.title
                },
                Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, maxLines = 1,
            )
            val result = node?.let { results[it.id] }
            (result?.mbps?.let { String.format(Locale.US, "⁦%.1f Mbps⁩", it) } ?: result?.pingMs?.let { "⁦$it ms⁩" })?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** The big round button: still when idle, an arc runs around it while connecting, and it breathes once connected. */
@Composable
private fun Orb(phase: ConnectionState, ring: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    val transition = rememberInfiniteTransition(label = "orb")
    val breathe by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(2200, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "breathe")
    val spin by transition.animateFloat(0f, 360f, infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "spin")
    val s = LocalStrings.current
    val scale = if (phase == ConnectionState.CONNECTED) 1f + 0.035f * breathe else 1f
    Box(Modifier.size(200.dp).scale(scale), contentAlignment = Alignment.Center) {
        Box(
            Modifier.fillMaxSize().clip(CircleShape).background(ring.copy(alpha = 0.15f)).clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(156.dp).clip(CircleShape).background(ring.copy(alpha = 0.25f)), contentAlignment = Alignment.Center) {
                Text(
                    when (phase) {
                        ConnectionState.DISCONNECTED -> s.connect
                        ConnectionState.CONNECTING -> s.cancel
                        ConnectionState.CONNECTED -> s.disconnect
                    },
                    style = MaterialTheme.typography.titleMedium, color = ring,
                )
            }
        }
        if (phase == ConnectionState.CONNECTING) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = 4.dp.toPx()
                drawArc(
                    color = ring, startAngle = spin, sweepAngle = 100f, useCenter = false,
                    topLeft = Offset(stroke / 2, stroke / 2), size = Size(size.width - stroke, size.height - stroke),
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
            }
        }
    }
}
