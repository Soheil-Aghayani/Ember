package ember.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

enum class ToastKind { Info, Success, Error }

class Toast(val id: Long, val text: String, val kind: ToastKind, val millis: Long)

/** Short messages that slide up from the bottom of the window and go away by themselves. */
class Toaster {
    internal val toasts = mutableStateListOf<Toast>()
    private var next = 0L

    fun show(text: String, kind: ToastKind = ToastKind.Info) {
        if (text.isBlank()) return
        // Errors stay a little longer; a long message gets longer still.
        val millis = (if (kind == ToastKind.Error) 5_000L else 3_000L) + (text.length * 25L).coerceAtMost(4_000L)
        toasts.add(Toast(next++, text, kind, millis))
        while (toasts.size > 3) toasts.removeAt(0)
    }

    internal fun dismiss(id: Long) {
        toasts.removeAll { it.id == id }
    }
}

val LocalToaster = staticCompositionLocalOf { Toaster() }

@Composable
internal fun ToastHost(toaster: Toaster) {
    Box(Modifier.fillMaxSize().padding(bottom = 24.dp, start = 16.dp, end = 16.dp), contentAlignment = Alignment.BottomCenter) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            for (toast in toaster.toasts) {
                androidx.compose.runtime.key(toast.id) { ToastItem(toast) { toaster.dismiss(toast.id) } }
            }
        }
    }
}

@Composable
private fun ToastItem(toast: Toast, onGone: () -> Unit) {
    val visible = remember { MutableTransitionState(false).apply { targetState = true } }
    LaunchedEffect(toast.id) {
        delay(toast.millis)
        visible.targetState = false
    }
    // once the exit animation has finished the toast is removed from the list
    LaunchedEffect(visible.currentState, visible.targetState) {
        if (!visible.targetState && !visible.currentState) onGone()
    }
    AnimatedVisibility(
        visibleState = visible,
        enter = slideInVertically(tween(320, easing = FastOutSlowInEasing)) { it / 2 } + fadeIn(tween(220)),
        exit = slideOutVertically(tween(240)) { it / 3 } + fadeOut(tween(200)),
    ) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.inverseSurface,
            contentColor = MaterialTheme.colorScheme.inverseOnSurface,
            shadowElevation = 8.dp,
        ) {
            Row(Modifier.widthIn(max = 440.dp).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                when (toast.kind) {
                    ToastKind.Success -> EmberIcon("check", 20.dp, MaterialTheme.colorScheme.tertiaryContainer)
                    ToastKind.Error -> EmberIcon("warning", 20.dp, MaterialTheme.colorScheme.errorContainer)
                    ToastKind.Info -> {}
                }
                if (toast.kind != ToastKind.Info) Spacer(Modifier.width(10.dp))
                Text(toast.text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.inverseOnSurface)
            }
        }
    }
}
