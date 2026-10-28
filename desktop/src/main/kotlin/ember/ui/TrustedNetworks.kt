package ember.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ember.engine.EmberEngine
import ember.engine.NetworkIdentity

/** Settings card: connect automatically on networks the user does not trust, plus the list of trusted networks. */
@Composable
internal fun TrustedNetworksSettings(engine: EmberEngine) {
    val strings = LocalStrings.current
    val current by engine.currentNetwork.collectAsState()
    var auto by remember { mutableStateOf(engine.settings.autoConnectUntrusted) }
    var trusted by remember { mutableStateOf(engine.settings.trustedNetworks) }

    fun name(id: String) = if (id.startsWith("wifi:")) NetworkIdentity.displayName(id) else strings.wiredNetwork

    Card {
        Column(Modifier.padding(vertical = 4.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(strings.autoConnectUntrusted, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        strings.autoConnectUntrustedHelp,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.padding(start = 12.dp))
                Switch(checked = auto, onCheckedChange = { auto = it; engine.settings.autoConnectUntrusted = it })
            }
            HorizontalDivider(Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    current?.let { strings.currentNetwork(name(it)) } ?: strings.offline,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(
                    onClick = {
                        if (engine.trustCurrentNetwork()) trusted = engine.settings.trustedNetworks
                    },
                    enabled = current != null && current !in trusted,
                ) { Text(strings.trustThisNetwork) }
            }
            HorizontalDivider(Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
            if (trusted.isEmpty()) {
                Text(
                    strings.noTrustedNetworks,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            } else {
                trusted.forEachIndexed { index, id ->
                    if (index > 0) HorizontalDivider(Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    Row(
                        Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(name(id), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        TextButton(onClick = {
                            engine.untrustNetwork(id)
                            trusted = engine.settings.trustedNetworks
                        }) { Text(strings.removeNetwork) }
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}
