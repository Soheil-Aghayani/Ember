package ember.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Round country flag from the bundled circle-flags set (flags/<iso2>.svg). Shows a plain disc if there is no flag. */
@Composable
fun FlagIcon(countryCode: String, size: Dp = 24.dp) {
    val path = "flags/${countryCode.lowercase()}.svg"
    val exists = remember(path) { Thread.currentThread().contextClassLoader.getResource(path) != null }
    if (exists) {
        Image(painterResource(path), contentDescription = null, modifier = Modifier.size(size).clip(CircleShape))
    } else {
        Box(Modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant))
    }
}
