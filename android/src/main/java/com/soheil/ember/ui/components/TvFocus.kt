package com.soheil.ember.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.material3.MaterialTheme

/** A clear outline for D-pad focus; the modifier adds no focus target of its own. */
fun Modifier.tvFocusOutline(enabled: Boolean, shape: Shape = RoundedCornerShape(16.dp)): Modifier =
    if (!enabled) this else composed {
        var focused by remember { mutableStateOf(false) }
        onFocusChanged { focused = it.isFocused }
            .then(if (focused) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, shape) else Modifier)
    }

/** Gives each tab a stable first target and returns leftward focus to that tab's rail item. */
fun Modifier.tvEntryFocus(
    enabled: Boolean,
    entryRequester: FocusRequester?,
    navigationRequester: FocusRequester?,
): Modifier = if (!enabled || entryRequester == null) this else {
    focusRequester(entryRequester).tvReturnToNavigation(enabled, navigationRequester)
}

/** Sends leftward focus from a page control back to its tab in the TV navigation rail. */
fun Modifier.tvReturnToNavigation(enabled: Boolean, navigationRequester: FocusRequester?): Modifier =
    if (!enabled || navigationRequester == null) this else focusProperties { left = navigationRequester }

/** Maps a TV remote's Select key to the action when Compose's generic clickable key handling omits it. */
fun Modifier.tvDpadActivate(enabled: Boolean, onActivate: () -> Unit): Modifier =
    if (!enabled) this else composed {
        val action = rememberUpdatedState(onActivate)
        onPreviewKeyEvent { event ->
            if (event.key == Key.DirectionCenter) {
                if (event.type == KeyEventType.KeyUp) action.value()
                true
            } else false
        }
    }
