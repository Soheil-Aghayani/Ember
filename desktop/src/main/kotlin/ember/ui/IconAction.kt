package ember.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import kotlinx.coroutines.delay

/** Puts a tooltip right under the element it belongs to, kept inside the window. */
private object BelowAnchor : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val x = (anchorBounds.center.x - popupContentSize.width / 2).coerceIn(4, (windowSize.width - popupContentSize.width - 4).coerceAtLeast(4))
        return IntOffset(x, anchorBounds.bottom + 4)
    }
}

/**
 * Shows [text] under the content after the pointer has rested on it. It follows enter and exit events of its own box,
 * so only the element under the pointer ever has a tooltip open.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun Tip(text: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    var hovered by remember { mutableStateOf(false) }
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(hovered) {
        if (hovered) {
            delay(450)
            visible = true
        } else {
            visible = false
        }
    }
    Box(
        modifier
            .onPointerEvent(PointerEventType.Enter) { hovered = true }
            .onPointerEvent(PointerEventType.Exit) { hovered = false },
    ) {
        content()
        if (visible) {
            Popup(popupPositionProvider = BelowAnchor) {
                Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.inverseSurface, shadowElevation = 4.dp) {
                    Text(
                        text,
                        Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.inverseOnSurface,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }
    }
}

/** An icon button with a tooltip: the app's replacement for small text buttons. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun IconAction(
    icon: String,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    filled: Boolean = false,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Tip(description, modifier) {
        if (filled) {
            Box(
                Modifier.size(40.dp).clip(CircleShape)
                    .background(if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                    .pointerHoverIcon(PointerIcon.Hand)
                    .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
                contentAlignment = Alignment.Center,
            ) {
                EmberIcon(icon, 20.dp, MaterialTheme.colorScheme.onPrimary, contentDescription = description)
            }
        } else {
            IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(36.dp).pointerHoverIcon(PointerIcon.Hand)) {
                EmberIcon(icon, 20.dp, if (enabled) tint else tint.copy(alpha = 0.38f), contentDescription = description)
            }
        }
    }
}
