package com.soheil.ember.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.soheil.ember.data.formatBytes
import com.soheil.ember.data.model.ConnectionState
import com.soheil.ember.engine.AndroidEngine
import com.soheil.ember.engine.VpnState
import com.soheil.ember.ui.components.FlagIcon
import ember.subs.Subscription
import ember.subs.sortNodes
import java.util.Locale

/** Subscriptions and servers: add them (link, clipboard, QR code, file, WARP), test them, and pick the one Connect uses. */
@Composable
fun ServersScreen(engine: AndroidEngine, say: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val subscriptions by engine.subscriptions.subscriptions.collectAsState()
    val results by engine.subscriptions.results.collectAsState()
    val selectedId by engine.selectedId.collectAsState()
    val favorites by engine.favorites.collectAsState()
    val testing by engine.testProgress.collectAsState()
    val phase by VpnState.phase.collectAsState()
    var menu by remember { mutableStateOf(false) }
    var askLink by remember { mutableStateOf(false) }

    fun report(result: Result<Subscription>) {
        result.onSuccess { say("Added ${it.nodes.count { node -> !node.isInfo }} servers") }
            .onFailure { say(it.message ?: "Could not add that.") }
    }
    fun add(text: String, name: String) = engine.work({ engine.subscriptions.add(text, name) }, ::report)

    val scan = rememberLauncherForActivityResult(ScanContract()) { scanned -> scanned.contents?.let { add(it, "") } }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val text = runCatching {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes().take(2_000_000).toByteArray().decodeToString() }
            }.getOrNull()
            if (text.isNullOrBlank()) say("That file could not be read.") else add(text, "")
        }
    }

    if (askLink) {
        var link by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { askLink = false },
            title = { Text("Add a link") },
            text = {
                OutlinedTextField(
                    value = link, onValueChange = { link = it }, label = { Text("Subscription link or server link") },
                    maxLines = 4, modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = { Button(enabled = link.isNotBlank(), onClick = { askLink = false; add(link, "") }) { Text("Add") } },
            dismissButton = { TextButton(onClick = { askLink = false }) { Text("Cancel") } },
        )
    }

    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Servers", Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium)
                if (testing != null) {
                    CircularProgressIndicator(Modifier.width(20.dp).height(20.dp), strokeWidth = 2.dp)
                    TextButton(onClick = { engine.cancelTest() }) { Text("Stop") }
                } else {
                    TextButton(
                        enabled = phase == ConnectionState.DISCONNECTED && subscriptions.isNotEmpty(),
                        onClick = { engine.testNodes(subscriptions.flatMap { it.nodes }) },
                    ) { Text("Test speed") }
                }
                Box {
                    FilledIconButton(onClick = { menu = true }) { Icon(Icons.Filled.Add, contentDescription = "Add servers") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Add a link") }, onClick = { menu = false; askLink = true })
                        DropdownMenuItem(text = { Text("From the clipboard") }, onClick = {
                            menu = false
                            val text = clipboard.getText()?.text.orEmpty().trim()
                            if (text.isEmpty()) say("The clipboard is empty.") else add(text, "")
                        })
                        DropdownMenuItem(text = { Text("Scan a QR code") }, onClick = {
                            menu = false
                            scan.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setBeepEnabled(false).setOrientationLocked(false)
                                .setPrompt("Point the camera at a server's QR code"))
                        })
                        DropdownMenuItem(text = { Text("From a file") }, onClick = { menu = false; pickFile.launch("*/*") })
                        DropdownMenuItem(text = { Text("Cloudflare WARP (free)") }, onClick = {
                            menu = false
                            say("Registering with Cloudflare…")
                            engine.work({ engine.subscriptions.addWarp() }, ::report)
                        })
                    }
                }
            }
        }
        if (subscriptions.isEmpty()) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Text(
                        "Nothing here yet. Add the subscription link your VPN provider gave you (the + button), then test the servers to find the fastest one.",
                        Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
        items(subscriptions, key = { it.id }) { subscription ->
            val servers = subscription.nodes.filterNot { it.isInfo || it.protocol == "openvpn" }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(vertical = 8.dp)) {
                    Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(subscription.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val usage = subscription.usage?.takeIf { it.total > 0 }
                            Text(
                                "${servers.size} servers" + (usage?.let { " · ${formatBytes(it.used)} of ${formatBytes(it.total)}" } ?: ""),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (subscription.url.isNotBlank()) {
                            IconButton(onClick = {
                                engine.work({ engine.subscriptions.refresh(subscription.id) }) { r ->
                                    r.onSuccess { say("Updated") }.onFailure { say(it.message ?: "Could not update.") }
                                }
                            }) { Icon(Icons.Filled.Refresh, contentDescription = "Update") }
                        }
                        IconButton(onClick = {
                            if (subscription.nodes.any { it.id == selectedId }) engine.chooseServer(ember.subs.AUTO_NODE)
                            engine.subscriptions.remove(subscription.id)
                        }) { Icon(Icons.Filled.Delete, contentDescription = "Remove") }
                    }
                    for (node in sortNodes(servers, results, favorites)) {
                        val result = results[node.id]
                        val selected = node.id == selectedId
                        Row(
                            Modifier.fillMaxWidth().clickable { engine.chooseServer(node.id) }.padding(start = 16.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            node.flagCode?.let {
                                FlagIcon(it, 24.dp)
                                Spacer(Modifier.width(12.dp))
                            }
                            Column(Modifier.weight(1f)) {
                                Text(node.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                                Text(node.protocol.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (result != null) {
                                val speed = result.mbps?.let { String.format(Locale.US, "%.1f Mbps", it) }
                                Text(
                                    speed ?: result.pingMs?.let { "$it ms" } ?: "No answer", style = MaterialTheme.typography.bodyMedium,
                                    color = if (result.pingMs == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                                )
                            }
                            IconButton(onClick = { engine.toggleFavorite(node) }) {
                                Icon(
                                    Icons.Filled.Star, contentDescription = "Favourite",
                                    tint = if (node.id in favorites) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                )
                            }
                            if (selected) Icon(Icons.Filled.Check, contentDescription = "Selected", tint = MaterialTheme.colorScheme.primary)
                            else Spacer(Modifier.width(24.dp))
                        }
                    }
                }
            }
        }
    }
}
