package ember.ui

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Solar icon from resources/icons/<name>.svg, tinted to the current content colour. */
@Composable
fun EmberIcon(name: String, size: Dp = 22.dp, tint: Color = MaterialTheme.colorScheme.onSurface, contentDescription: String? = null) {
    Icon(painterResource("icons/$name.svg"), contentDescription = contentDescription, tint = tint, modifier = Modifier.size(size))
}
