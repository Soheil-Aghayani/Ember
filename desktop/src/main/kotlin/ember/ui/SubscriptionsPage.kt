package ember.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.soheil.ember.data.formatBytes
import com.soheil.ember.data.model.ConnectionState
import ember.engine.EmberEngine
import ember.subs.ProxyNode
import ember.subs.Subscription
import ember.subs.sortNodes
import java.util.Locale
import kotlinx.coroutines.launch

/** Subscription links: add a link, see its servers sorted by the last speed test, and pick the one Connect should use. */
@Composable
internal fun SubscriptionsPage(engine: EmberEngine) {
    val strings = LocalStrings.current
    val scope = rememberCoroutineScope()
    val subscriptions by engine.subscriptions.subscriptions.collectAsState()
    val results by engine.subscriptions.results.collectAsState()
    val testing by engine.testProgress.collectAsState()
    val state by engine.state.collectAsState()
    val selectedId by engine.selectedId.collectAsState()
    val favorites by engine.favorites.collectAsState()
    val clipboard = LocalClipboardManager.current
    var showAdd by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Subscription?>(null) }
    var busyId by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    if (showAdd) AddSubscriptionDialog(engine, null, onClose = { showAdd = false })
    editing?.let { current -> AddSubscriptionDialog(engine, current, onClose = { editing = null }) }

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(strings.subscriptions, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
        IconAction("clipboard", strings.pasteFromClipboard, onClick = {
            val text = clipboard.getText()?.text.orEmpty().trim()
            if (text.isEmpty()) {
                message = strings.clipboardEmpty
            } else {
                message = null
                scope.launch { runCatching { engine.subscriptions.add(text, "") }.onFailure { message = it.message ?: strings.signInFailed } }
            }
        })
        Spacer(Modifier.width(4.dp))
        IconAction("add", strings.addSubscription, onClick = { showAdd = true }, filled = true)
    }
    Spacer(Modifier.height(8.dp))
    Text(strings.subscriptionsHelp, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    message?.let {
        Spacer(Modifier.height(8.dp))
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
    Spacer(Modifier.height(16.dp))

    if (subscriptions.isEmpty()) {
        Card {
            Text(strings.noSubscriptions, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp))
        }
        return
    }

    for (subscription in subscriptions) {
        SubscriptionCard(
            subscription = subscription,
            results = results,
            selectedId = selectedId,
            canTest = testing == null && state == ConnectionState.DISCONNECTED,
            testing = testing,
            refreshing = busyId == subscription.id,
            favorites = favorites,
            onToggleFavorite = { engine.toggleFavorite(it) },
            onSelect = { node -> engine.chooseServer(node.id) },
            onTest = { engine.testNodes(subscription.nodes) },
            onStopTest = { engine.cancelTest() },
            onRefresh = {
                busyId = subscription.id
                message = null
                scope.launch {
                    runCatching { engine.subscriptions.refresh(subscription.id) }.onFailure { message = it.message ?: strings.signInFailed }
                    busyId = null
                }
            },
            onEdit = { editing = subscription },
            onRemove = {
                if (subscription.nodes.any { it.id == selectedId }) engine.chooseServer("")
                engine.subscriptions.remove(subscription.id)
            },
        )
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun SubscriptionCard(
    subscription: Subscription,
    results: Map<String, ember.subs.NodeResult>,
    selectedId: String,
    favorites: Set<String>,
    onToggleFavorite: (ProxyNode) -> Unit,
    canTest: Boolean,
    testing: String?,
    refreshing: Boolean,
    onSelect: (ProxyNode) -> Unit,
    onTest: () -> Unit,
    onStopTest: () -> Unit,
    onRefresh: () -> Unit,
    onRemove: () -> Unit,
    onEdit: () -> Unit,
) {
    val strings = LocalStrings.current
    var expanded by remember { mutableStateOf(true) }
    val servers = subscription.nodes.filterNot { it.isInfo }
    val info = subscription.nodes.filter { it.isInfo }
    val sorted = sortNodes(servers, results, favorites)
    Card {
        Column(Modifier.padding(vertical = 8.dp)) {
            Row(
                Modifier.fillMaxWidth()
                    .pointerHoverIcon(PointerIcon.Hand)
                    .clickable(role = Role.Button) { expanded = !expanded }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(subscription.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(strings.serversCount(servers.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                EmberIcon(
                    if (expanded) "chevron-up" else "chevron-down", 18.dp, MaterialTheme.colorScheme.onSurfaceVariant,
                    contentDescription = if (expanded) strings.collapse else strings.expand,
                )
            }
            Column(Modifier.padding(horizontal = 16.dp)) {
                subscription.usage?.takeIf { it.total > 0 }?.let { usage ->
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { (usage.used.toFloat() / usage.total).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    )
                    val daysLeft = usage.expiresAtEpochSeconds?.let { ((it - System.currentTimeMillis() / 1000) / 86_400).toInt() }
                    Text(
                        strings.usageOf(formatBytes(usage.used), formatBytes(usage.total)) +
                            (daysLeft?.takeIf { it >= 0 }?.let { " · " + strings.daysLeft(it) } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                for (row in info) {
                    Text(row.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (expanded) Spacer(Modifier.height(8.dp))
                if (expanded) Row(verticalAlignment = Alignment.CenterVertically) {
                    if (testing != null) {
                        IconAction("stop", strings.stopTest, onStopTest, tint = MaterialTheme.colorScheme.primary)
                    } else {
                        IconAction("bolt", strings.testSpeed, onTest, enabled = canTest && servers.isNotEmpty(), tint = MaterialTheme.colorScheme.primary)
                    }
                    IconAction("refresh", if (refreshing) strings.refreshing else strings.refresh, onRefresh, enabled = !refreshing && subscription.url.isNotBlank())
                    IconAction("edit", strings.editSubscription, onEdit)
                    IconAction("trash", strings.removeSubscription, onRemove)
                    testing?.let {
                        Spacer(Modifier.width(8.dp))
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (expanded && testing == null && !canTest) {
                    Spacer(Modifier.height(4.dp))
                    Text(strings.testNeedsDisconnect, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (expanded) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            if (expanded) sorted.forEachIndexed { index, node ->
                if (index > 0) HorizontalDivider(Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                val selected = node.id == selectedId
                val result = results[node.id]
                Row(
                    Modifier.fillMaxWidth()
                        .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
                        .pointerHoverIcon(PointerIcon.Hand)
                        .clickable(role = Role.RadioButton) { onSelect(node) }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    node.flagCode?.let {
                        FlagIcon(it, 24.dp)
                        Spacer(Modifier.width(12.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(node.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(node.protocol.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (result != null) {
                        val speed = result.mbps?.let { String.format(Locale.US, "%.1f Mbps", it) }
                        val ping = result.pingMs?.let { "$it ms" }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                speed ?: ping ?: strings.noAnswer,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (ping == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                            )
                            if (speed != null && ping != null) {
                                Text(ping, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    val starred = node.id in favorites
                    IconAction(
                        if (starred) "star-filled" else "star", strings.favorite, onClick = { onToggleFavorite(node) },
                        tint = if (starred) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (selected) {
                        Spacer(Modifier.width(4.dp))
                        EmberIcon("check", 20.dp, MaterialTheme.colorScheme.primary, contentDescription = strings.selected)
                    }
                }
            }
        }
    }
}

@Composable
private fun AddSubscriptionDialog(engine: EmberEngine, existing: Subscription?, onClose: () -> Unit) {
    val strings = LocalStrings.current
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf(existing?.url ?: "") }
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = { if (!busy) onClose() },
        title = { Text(if (existing == null) strings.addSubscription else strings.editSubscription) },
        text = {
            Column {
                OutlinedTextField(
                    value = url, onValueChange = { url = it }, label = { Text(strings.subscriptionLink) },
                    maxLines = 4, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = name, onValueChange = { name = it }, label = { Text(strings.subscriptionNameOptional) },
                    singleLine = true, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth(),
                )
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !busy && url.isNotBlank(),
                onClick = {
                    error = null
                    busy = true
                    scope.launch {
                        runCatching {
                            if (existing == null) engine.subscriptions.add(url, name) else engine.subscriptions.update(existing.id, name, url)
                        }
                            .onSuccess { onClose() }
                            .onFailure { error = it.message ?: strings.signInFailed }
                        busy = false
                    }
                },
            ) { Text(if (busy) (if (existing == null) strings.adding else strings.saving) else (if (existing == null) strings.addSubscription else strings.save)) }
        },
        dismissButton = { TextButton(onClick = onClose, enabled = !busy) { Text(strings.cancel) } },
    )
}
