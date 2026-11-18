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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import com.soheil.ember.ui.components.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.res.painterResource
import com.soheil.ember.R
import ember.subs.SitePresets
import ember.subs.normalizeSiteUrl
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.soheil.ember.data.formatBytes
import com.soheil.ember.data.model.ConnectionState
import com.soheil.ember.engine.AndroidEngine
import com.soheil.ember.engine.VpnState
import com.soheil.ember.ui.components.FlagIcon
import com.soheil.ember.ui.components.tvFocusOutline
import com.soheil.ember.ui.components.tvEntryFocus
import com.soheil.ember.ui.components.tvDpadActivate
import androidx.compose.ui.focus.FocusRequester
import ember.subs.Subscription
import ember.subs.shareLink
import ember.subs.sortNodes
import java.util.Locale

/** Subscriptions and servers: add them (link, clipboard, QR code, file, WARP), test them, and pick the one Connect uses. */
@Composable
fun ServersScreen(
    engine: AndroidEngine,
    say: (String) -> Unit,
    onConnect: () -> Unit,
    modifier: Modifier = Modifier,
    isTv: Boolean = false,
    tvNavigationFocusRequester: FocusRequester? = null,
    tvEntryFocusRequester: FocusRequester? = null,
) {
    val context = LocalContext.current
    val s = LocalStrings.current
    val clipboard = LocalClipboardManager.current
    val subscriptions by engine.subscriptions.subscriptions.collectAsState()
    val results by engine.subscriptions.results.collectAsState()
    val selectedId by engine.selectedId.collectAsState()
    val favorites by engine.favorites.collectAsState()
    val testing by engine.testProgress.collectAsState()
    val phase by VpnState.phase.collectAsState()
    var menu by remember { mutableStateOf(false) }
    var askLink by remember { mutableStateOf(false) }
    var askSite by remember { mutableStateOf(false) }
    var qrNode by remember { mutableStateOf<ember.subs.ProxyNode?>(null) }
    val siteCheck by engine.siteCheck.collectAsState()

    fun report(result: Result<Subscription>) {
        result.onSuccess { say(s.serversAdded(it.nodes.count { node -> !node.isInfo })) }
            .onFailure { say(it.message ?: s.couldNotAdd) }
    }
    fun add(text: String, name: String) = engine.work({ engine.subscriptions.add(text, name) }, ::report)

    val scan = rememberLauncherForActivityResult(ScanContract()) { scanned -> scanned.contents?.let { add(it, "") } }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val text = runCatching {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes().take(2_000_000).toByteArray().decodeToString() }
            }.getOrNull()
            if (text.isNullOrBlank()) say(s.fileUnreadable) else add(text, "")
        }
    }

    if (askLink) {
        var link by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { askLink = false },
            title = { Text(s.addLink) },
            text = {
                OutlinedTextField(
                    value = link, onValueChange = { link = it }, label = { Text(s.linkLabel) },
                    maxLines = 4, modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                Button(
                    enabled = link.isNotBlank(), onClick = { askLink = false; add(link, "") },
                    modifier = Modifier.tvDpadActivate(isTv && link.isNotBlank()) { askLink = false; add(link, "") }.tvFocusOutline(isTv),
                ) { Text(s.add) }
            },
            dismissButton = {
                TextButton(onClick = { askLink = false }, modifier = Modifier.tvDpadActivate(isTv) { askLink = false }.tvFocusOutline(isTv)) { Text(s.cancel) }
            },
        )
    }

    qrNode?.let { QrDialog(it, say, onClose = { qrNode = null }) }

    if (askSite) {
        var input by remember { mutableStateOf("") }
        var picked by remember { mutableStateOf<String?>(null) }
        val url = normalizeSiteUrl(input)
        fun checkSite() {
            val checkedUrl = url ?: return
            askSite = false
            val host = picked ?: runCatching { java.net.URI(checkedUrl).host.removePrefix("www.") }.getOrDefault(checkedUrl)
            engine.checkSite(subscriptions.flatMap { it.nodes }, host, checkedUrl)
        }
        AlertDialog(
            onDismissRequest = { askSite = false },
            title = { Text(s.siteTitle) },
            text = {
                Column {
                    Text(
                        s.siteHelp,
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for ((name, address) in SitePresets.list) {
                            FilterChip(selected = picked == name, onClick = { picked = name; input = address }, label = { Text(name) })
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = input, onValueChange = { input = it; picked = null }, label = { Text(s.siteHint) },
                        singleLine = true, isError = input.isNotBlank() && url == null, modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                Button(
                    enabled = url != null,
                    onClick = { checkSite() },
                    modifier = Modifier.tvDpadActivate(isTv && url != null) { checkSite() }.tvFocusOutline(isTv),
                ) { Text(s.check) }
            },
            dismissButton = {
                TextButton(onClick = { askSite = false }, modifier = Modifier.tvDpadActivate(isTv) { askSite = false }.tvFocusOutline(isTv)) { Text(s.cancel) }
            },
        )
    }

    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(s.servers, Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium)
                if (testing != null) {
                    CircularProgressIndicator(Modifier.width(20.dp).height(20.dp), strokeWidth = 2.dp)
                    TextButton(
                        onClick = { engine.cancelTest() },
                        modifier = Modifier.tvDpadActivate(isTv) { engine.cancelTest() }.tvFocusOutline(isTv),
                    ) { Text(s.stop) }
                } else {
                    val canTest = phase == ConnectionState.DISCONNECTED && subscriptions.isNotEmpty()
                    val testServers = { engine.testNodes(subscriptions.flatMap { it.nodes }) }
                    TextButton(
                        enabled = canTest,
                        onClick = testServers,
                        modifier = Modifier.tvDpadActivate(isTv && canTest, testServers).tvFocusOutline(isTv),
                    ) { Text(s.testSpeed) }
                }
                val canCheckSite = phase == ConnectionState.DISCONNECTED && testing == null && subscriptions.isNotEmpty()
                IconButton(
                    enabled = canCheckSite,
                    onClick = { askSite = true },
                    modifier = Modifier.tvDpadActivate(isTv && canCheckSite) { askSite = true }.tvFocusOutline(isTv),
                ) { Icon(painterResource(R.drawable.ic_solar_magnifier), contentDescription = s.checkSite) }
                Box {
                    FilledIconButton(
                        onClick = { menu = true },
                        modifier = Modifier.tvEntryFocus(isTv, tvEntryFocusRequester, tvNavigationFocusRequester)
                            .tvDpadActivate(isTv) { menu = true }
                            .tvFocusOutline(isTv),
                    ) { Icon(Icons.Filled.Add, contentDescription = s.addServers) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text(s.addLink) }, onClick = { menu = false; askLink = true })
                        DropdownMenuItem(text = { Text(s.fromClipboard) }, onClick = {
                            menu = false
                            val text = clipboard.getText()?.text.orEmpty().trim()
                            if (text.isEmpty()) say(s.clipboardEmpty) else add(text, "")
                        })
                        if (!isTv) {
                            DropdownMenuItem(text = { Text(s.scanQr) }, onClick = {
                                menu = false
                                scan.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setBeepEnabled(false).setOrientationLocked(false)
                                    .setPrompt(s.qrPrompt))
                            })
                        }
                        DropdownMenuItem(text = { Text(s.warpThroughServer) }, onClick = {
                            menu = false
                            val carrier = engine.selectedNode()?.takeIf { it.protocol !in setOf("openvpn", "wireguard", "warp") }
                            if (carrier == null) say(s.chooseOwnServerFirst) else {
                                say(s.registering)
                                engine.work({ engine.subscriptions.addWarpChain(carrier) }) { r ->
                                    r.onSuccess { say(s.warpAdded(carrier.title)) }.onFailure { say(it.message ?: s.couldNotAdd) }
                                }
                            }
                        })
                        DropdownMenuItem(text = { Text(s.fromFile) }, onClick = { menu = false; pickFile.launch("*/*") })
                        DropdownMenuItem(text = { Text(s.warpFree) }, onClick = {
                            menu = false
                            say(s.registering)
                            engine.work({ engine.subscriptions.addWarp() }, ::report)
                        })
                    }
                }
            }
        }
        siteCheck?.let { check ->
            item {
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            s.siteSummary(check.label, check.results.values.count { it.ok }, check.results.size),
                            Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                        )
                        val best = sortNodes(
                            subscriptions.flatMap { it.nodes }.filter { n -> check.results[n.id]?.ok == true }, results, favorites,
                        ).firstOrNull()
                        val connectBest = {
                            best?.let {
                                engine.chooseServer(it.id)
                                onConnect()
                            }
                        }
                        TextButton(enabled = best != null && testing == null && phase == ConnectionState.DISCONNECTED, onClick = {
                            connectBest()
                        }, modifier = Modifier.tvDpadActivate(isTv && best != null && testing == null && phase == ConnectionState.DISCONNECTED) {
                            connectBest()
                        }.tvFocusOutline(isTv)) { Text(s.connect) }
                        IconButton(
                            onClick = { engine.clearSiteCheck() },
                            modifier = Modifier.tvDpadActivate(isTv) { engine.clearSiteCheck() }.tvFocusOutline(isTv),
                        ) { Icon(Icons.Filled.Close, contentDescription = s.clear) }
                    }
                }
            }
        }
        if (subscriptions.isEmpty()) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Text(
                        s.emptyServers,
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
                                s.serversCount(servers.size) + (usage?.let { " · " + s.usageOf(formatBytes(it.used), formatBytes(it.total)) } ?: ""),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (subscription.url.isNotBlank()) {
                            val refreshSubscription = {
                                engine.work({ engine.subscriptions.refresh(subscription.id) }) { r ->
                                    r.onSuccess { say(s.updated) }.onFailure { say(it.message ?: s.couldNotUpdate) }
                                }
                            }
                            IconButton(
                                onClick = refreshSubscription,
                                modifier = Modifier.tvDpadActivate(isTv, refreshSubscription).tvFocusOutline(isTv),
                            ) { Icon(Icons.Filled.Refresh, contentDescription = s.update) }
                        }
                        val removeSubscription = {
                            if (subscription.nodes.any { it.id == selectedId }) engine.chooseServer(ember.subs.AUTO_NODE)
                            engine.subscriptions.remove(subscription.id)
                        }
                        IconButton(
                            onClick = removeSubscription,
                            modifier = Modifier.tvDpadActivate(isTv, removeSubscription).tvFocusOutline(isTv),
                        ) { Icon(Icons.Filled.Delete, contentDescription = s.remove) }
                    }
                    val ordered = sortNodes(servers, results, favorites)
                    // after a website check the servers that reached it come first, then the ones not asked, then the refused ones
                    val shown = siteCheck?.let { check -> ordered.sortedBy { n -> check.results[n.id]?.let { if (it.ok) 0 else 2 } ?: 1 } } ?: ordered
                    for (node in shown) {
                        val result = results[node.id]
                        val selected = node.id == selectedId
                        val chooseServer = { engine.chooseServer(node.id) }
                        Row(
                            Modifier.fillMaxWidth().tvDpadActivate(isTv, chooseServer).tvFocusOutline(isTv)
                                .clickable(onClick = chooseServer)
                                .padding(start = 16.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
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
                                val speed = result.mbps?.let { String.format(Locale.US, "⁦%.1f Mbps⁩", it) }
                                Text(
                                    speed ?: result.pingMs?.let { "⁦$it ms⁩" } ?: s.noAnswer, style = MaterialTheme.typography.bodyMedium,
                                    color = if (result.pingMs == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                                )
                            }
                            siteCheck?.results?.get(node.id)?.let { site ->
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    if (site.ok) "✓ ⁦${site.ms} ms⁩" else "✗ ${site.code ?: s.noAnswer}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (site.ok) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
                                )
                            }
                            if (selected && node.shareLink != null) {
                                val showQr = { qrNode = node }
                                IconButton(
                                    onClick = showQr,
                                    modifier = Modifier.tvDpadActivate(isTv, showQr).tvFocusOutline(isTv),
                                ) {
                                    Icon(painterResource(R.drawable.ic_solar_qr), contentDescription = s.showQr)
                                }
                            }
                            val toggleFavorite = { engine.toggleFavorite(node) }
                            IconButton(
                                onClick = toggleFavorite,
                                modifier = Modifier.tvDpadActivate(isTv, toggleFavorite).tvFocusOutline(isTv),
                            ) {
                                Icon(
                                    Icons.Filled.Star, contentDescription = s.favourite,
                                    tint = if (node.id in favorites) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f),
                                )
                            }
                            if (selected) Icon(Icons.Filled.Check, contentDescription = s.selected, tint = MaterialTheme.colorScheme.primary)
                            else Spacer(Modifier.width(24.dp))
                        }
                    }
                }
            }
        }
    }
}
