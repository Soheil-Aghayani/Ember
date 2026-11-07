package ember.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowScope

@Composable
fun WindowScope.EmberMacWindowTitleBar(
    isMaximized: Boolean,
    onClose: () -> Unit,
    onMinimize: () -> Unit,
    onZoom: () -> Unit,
) {
    val strings = LocalStrings.current
    WindowDraggableArea(Modifier.fillMaxWidth().height(EmberDesktopMetrics.titleBarHeight)) {
        Box(Modifier.fillMaxSize()) {
            Row(
                Modifier.align(Alignment.CenterStart).padding(start = EmberDesktopMetrics.windowControlInset),
                horizontalArrangement = Arrangement.spacedBy(EmberDesktopMetrics.windowControlGap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                WindowControl("close", strings.windowClose, onClose)
                WindowControl("minus", strings.windowMinimize, onMinimize)
                WindowControl(
                    if (isMaximized) "minimize-square" else "maximize-square",
                    if (isMaximized) strings.windowRestore else strings.windowZoom,
                    onZoom,
                )
            }
            Row(
                Modifier.align(Alignment.Center),
                horizontalArrangement = Arrangement.spacedBy(EmberDesktopMetrics.brandGap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Image(painterResource("ember_logo.png"), contentDescription = null, modifier = Modifier.size(EmberDesktopMetrics.brandMark))
                Text("Ember", style = MaterialTheme.typography.titleSmall)
            }
        }
    }
}

@Composable
private fun WindowControl(icon: String, label: String, onClick: () -> Unit) {
    val colors = LocalEmberWindowControlColors.current
    val color = when (icon) {
        "close" -> colors.close
        "minus" -> colors.minimize
        else -> colors.zoom
    }
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val glyphAlpha by animateFloatAsState(if (hovered) 1f else 0f, tween(140), label = "windowControlGlyph")
    Box(
        Modifier
            .size(EmberDesktopMetrics.windowControlTarget)
            .clip(CircleShape)
            .hoverable(interaction)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(EmberDesktopMetrics.windowControlDot)
                .clip(CircleShape)
                .background(color)
                .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            EmberIcon(icon, EmberDesktopMetrics.windowControlGlyph, colors.glyph.copy(alpha = glyphAlpha))
        }
    }
}
