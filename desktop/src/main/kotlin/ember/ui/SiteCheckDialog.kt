package ember.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ember.subs.SitePresets
import ember.subs.normalizeSiteUrl
import java.net.URI

/** Pick a website (or type one); [onCheck] gets its web address and a short name for it. */
@Composable
internal fun SiteCheckDialog(onCheck: (url: String, label: String) -> Unit, onClose: () -> Unit) {
    val strings = LocalStrings.current
    var input by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf<String?>(null) }
    val url = normalizeSiteUrl(input)
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(strings.checkSiteTitle) },
        text = {
            Column {
                Text(strings.checkSiteHelp, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((name, address) in SitePresets.list) {
                        FilterChip(selected = picked == name, onClick = { picked = name; input = address }, label = { Text(name) })
                    }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = input, onValueChange = { input = it; picked = null }, label = { Text(strings.siteHint) },
                    singleLine = true, isError = input.isNotBlank() && url == null,
                    shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                enabled = url != null,
                onClick = { onCheck(url!!, picked ?: runCatching { URI(url).host.removePrefix("www.") }.getOrDefault(url!!)) },
            ) { Text(strings.checkButton) }
        },
        dismissButton = { TextButton(onClick = onClose) { Text(strings.cancel) } },
    )
}
