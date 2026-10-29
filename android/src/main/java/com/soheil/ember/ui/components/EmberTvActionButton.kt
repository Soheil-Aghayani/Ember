package com.soheil.ember.ui.components

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.soheil.ember.ui.theme.EmberTvDimensions

@Composable
fun EmberTvActionButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    primary: Boolean = false,
    minHeight: Dp = EmberTvDimensions.actionHeight,
    content: @Composable RowScope.() -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    val focusModifier = modifier
        .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
        .emberTvFocusRing(enabled = true, shape = shape)
        .padding(EmberTvDimensions.focusGap)
        .heightIn(min = minHeight)

    if (primary) {
        Button(onClick = onClick, modifier = focusModifier, shape = shape, content = content)
    } else {
        OutlinedButton(onClick = onClick, modifier = focusModifier, shape = shape, content = content)
    }
}
