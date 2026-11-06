package ember.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.zIndex
import kotlinx.coroutines.delay
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import ember.subs.shareLink
import ember.subs.sortNodes
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    var addMenu by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Subscription?>(null) }
    var busyId by remember { mutableStateOf<String?>(null) }
    val toaster = LocalToaster.current
    val siteCheck by engine.siteCheck.collectAsState()
    var showCheck by remember { mutableStateOf(false) }
    var qrNode by remember { mutableStateOf<ProxyNode?>(null) }

    fun addFromClipboard() {
        val text = clipboard.getText()?.text.orEmpty().trim()
        if (text.isEmpty()) {
            toaster.show(strings.clipboardEmpty)
            return
        }
        scope.launch {
            runCatching { engine.subscriptions.add(text, "") }
                .onSuccess { toaster.show(strings.serversAdded(it.nodes.count { node -> !node.isInfo }), ToastKind.Success) }
                .onFailure { toaster.show(it.message ?: strings.signInFailed, ToastKind.Error) }
        }
    }

    fun addWarp() {
        toaster.show(strings.warpRegistering)
        scope.launch {
            runCatching { engine.subscriptions.addWarp() }
                .onSuccess { toaster.show(strings.serversAdded(it.nodes.size), ToastKind.Success) }
                .onFailure { toaster.show(it.message ?: strings.signInFailed, ToastKind.Error) }
        }
    }

    fun importFiles() {
        scope.launch {
            val chosen = withContext(Dispatchers.IO) { chooseServerFiles(strings.chooseFileTitle) }
            val problems = mutableListOf<String>()
            var added = 0
            for (file in chosen) {
                // A server file is small; anything bigger is not one.
                if (file.length() > 2_000_000) {
                    problems += strings.fileTooLarge(file.name)
                    continue
                }
                runCatching { engine.subscriptions.add(withContext(Dispatchers.IO) { readServerFile(file) }, file.nameWithoutExtension) }
                    .onSuccess { added += it.nodes.count { node -> !node.isInfo } }
                    .onFailure { problems += "${file.name}: ${it.message ?: strings.signInFailed}" }
            }
            if (added > 0) toaster.show(strings.serversAdded(added), ToastKind.Success)
            if (problems.isNotEmpty()) toaster.show(problems.joinToString("\n"), ToastKind.Error)
        }
    }

    qrNode?.let { QrDialog(it, onClose = { qrNode = null }) }
    if (showCheck) {
        SiteCheckDialog(
            onCheck = { url, label ->
                showCheck = false
                if (state != ConnectionState.DISCONNECTED) toaster.show(strings.testNeedsDisconnect)
                else engine.checkSite(subscriptions.flatMap { it.nodes }, label, url)
            },
            onClose = { showCheck = false },
        )
    }
    if (showAdd) AddSubscriptionDialog(engine, null, onClose = { showAdd = false })
    editing?.let { current -> AddSubscriptionDialog(engine, current, onClose = { editing = null }) }

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(strings.subscriptions, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
        IconAction("magnifier", strings.checkSite, onClick = { showCheck = true }, enabled = subscriptions.isNotEmpty() && testing == null)
        Spacer(Modifier.width(4.dp))
        Box {
            IconAction("plus", strings.addServers, onClick = { addMenu = true }, filled = true)
            DropdownMenu(
                expanded = addMenu, onDismissRequest = { addMenu = false },
                shape = RoundedCornerShape(EmberDesktopMetrics.countryPopoverRadius),
                containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp, shadowElevation = 6.dp,
            ) {
                DropdownMenuItem(
                    text = { Text(strings.addSubscription) },
                    leadingIcon = { EmberIcon("add", 20.dp, MaterialTheme.colorScheme.onSurfaceVariant) },
                    onClick = { addMenu = false; showAdd = true },
                )
                DropdownMenuItem(
                    text = { Text(strings.pasteFromClipboard) },
                    leadingIcon = { EmberIcon("clipboard", 20.dp, MaterialTheme.colorScheme.onSurfaceVariant) },
                    onClick = { addMenu = false; addFromClipboard() },
                )
                DropdownMenuItem(
                    text = { Text(strings.addWarp) },
                    leadingIcon = { EmberIcon("cloud", 20.dp, MaterialTheme.colorScheme.onSurfaceVariant) },
                    onClick = { addMenu = false; addWarp() },
                )
                DropdownMenuItem(
                    text = { Text(strings.importFile) },
                    leadingIcon = { EmberIcon("import-file", 20.dp, MaterialTheme.colorScheme.onSurfaceVariant) },
                    onClick = { addMenu = false; importFiles() },
                )
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    Text(strings.subscriptionsHelp, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    siteCheck?.let { check ->
        Spacer(Modifier.height(12.dp))
        Card {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    strings.siteSummary(check.label, check.results.values.count { it.ok }, check.results.size),
                    Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                )
                val best = sortNodes(
                    subscriptions.flatMap { it.nodes }.filter { n -> check.results[n.id]?.ok == true }, results, favorites,
                ).firstOrNull()
                IconAction(
                    "bolt", strings.connectForSite(check.label),
                    onClick = {
                        best?.let {
                            engine.chooseServer(it.id)
                            if (state == ConnectionState.DISCONNECTED) engine.connectSelected()
                        }
                    },
                    enabled = best != null && testing == null, tint = MaterialTheme.colorScheme.primary,
                )
                IconAction("close", strings.clearCheck, onClick = { engine.clearSiteCheck() })
            }
        }
    }
    Spacer(Modifier.height(16.dp))

    if (subscriptions.isEmpty()) {
        Card {
            EmptyState("subscriptions", strings.emptyServersTitle, strings.noSubscriptions) {
                Button(onClick = { showAdd = true }, shape = RoundedCornerShape(12.dp)) { Text(strings.addSubscription) }
                IconAction("cloud", strings.addWarp, onClick = { addWarp() })
                IconAction("clipboard", strings.pasteFromClipboard, onClick = { addFromClipboard() })
                IconAction("import-file", strings.importFile, onClick = { importFiles() })
            }
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
            siteResults = siteCheck?.results,
            onToggleFavorite = { engine.toggleFavorite(it) },
            onShowQr = { qrNode = it },
            onSelect = { node -> engine.chooseServer(node.id) },
            onTest = { engine.testNodes(subscription.nodes) },
            onStopTest = { engine.cancelTest() },
            onRefresh = {
                busyId = subscription.id
                scope.launch {
                    runCatching { engine.subscriptions.refresh(subscription.id) }
                        .onSuccess { toaster.show(strings.updated, ToastKind.Success) }
                        .onFailure { toaster.show(it.message ?: strings.signInFailed, ToastKind.Error) }
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

/** The text of a server file. An OpenVPN profile gets the certificate and key files next to it moved inside. */
private fun readServerFile(file: java.io.File): String {
    if (file.extension.lowercase() in setOf("png", "jpg", "jpeg", "bmp", "gif")) {
        return ember.subs.QrCodes.decode(file) ?: error("No QR code was found in that picture.")
    }
    val text = file.readText()
    if (!ember.subs.ConfigFiles.isOpenVpn(text)) return text
    val folder = file.parentFile?.canonicalFile ?: return text
    return ember.subs.OpenVpnProfile.prepare(text) { name ->
        // Only files in the profile's own folder (or below it) are read.
        val target = java.io.File(folder, name).canonicalFile
        target.takeIf { it.isFile && it.length() < 100_000 && it.path.startsWith(folder.path + java.io.File.separator) }?.readText()
    }
}

/** The system file dialog, for server files; call it off the UI thread (it blocks until the user is done). */
private fun chooseServerFiles(title: String): List<java.io.File> {
    val dialog = java.awt.FileDialog(null as java.awt.Frame?, title, java.awt.FileDialog.LOAD)
    dialog.isMultipleMode = true
    dialog.file = "*.json;*.conf;*.txt;*.ovpn;*.png;*.jpg;*.jpeg" // Windows reads this as the file-type filter
    dialog.isVisible = true
    return dialog.files.toList()
}

@Composable
private fun SubscriptionCard(
    subscription: Subscription,
    results: Map<String, ember.subs.NodeResult>,
    selectedId: String,
    favorites: Set<String>,
    siteResults: Map<String, ember.subs.SiteResult>?,
    onToggleFavorite: (ProxyNode) -> Unit,
    onShowQr: (ProxyNode) -> Unit,
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
    var expanded by remember { mutableStateOf(false) }
    var rowHeight by remember { mutableStateOf(0) }
    val lastIndexes = remember { mutableMapOf<String, Int>() }
    val servers = subscription.nodes.filterNot { it.isInfo }
    val info = subscription.nodes.filter { it.isInfo }
    val ordered = sortNodes(servers, results, favorites)
    // after a website check the servers that reached it come first, then the ones not asked, then the refused ones
    val sorted = if (siteResults == null) ordered else ordered.sortedBy { n -> siteResults[n.id]?.let { if (it.ok) 0 else 2 } ?: 1 }
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (testing != null) {
                            CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(
                            testing ?: strings.serversCount(servers.size), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (testing != null) {
                    IconAction("stop", strings.stopTest, onStopTest, tint = MaterialTheme.colorScheme.primary)
                } else {
                    IconAction("bolt", strings.testSpeed, onTest, enabled = canTest && servers.isNotEmpty(), tint = MaterialTheme.colorScheme.primary)
                }
                IconAction("refresh", if (refreshing) strings.refreshing else strings.refresh, onRefresh, enabled = !refreshing && subscription.url.isNotBlank())
                IconAction("edit", strings.editSubscription, onEdit)
                IconAction("trash", strings.removeSubscription, onRemove)
                Spacer(Modifier.width(4.dp))
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
                if (expanded) for (row in info) {
                    Text(row.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (expanded) Spacer(Modifier.height(8.dp))
                if (testing == null && !canTest) {
                    Spacer(Modifier.height(4.dp))
                    Text(strings.testNeedsDisconnect, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(tween(320, easing = FastOutSlowInEasing)) + fadeIn(tween(220)),
                exit = shrinkVertically(tween(260)) + fadeOut(tween(140)),
            ) {
                Column {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    val fastest = sorted.mapNotNull { results[it.id]?.mbps }.maxOrNull() ?: 0.0
                    sorted.forEachIndexed { index, node ->
                        key(node.id) {
                            ServerRow(
                                node = node, index = index, selected = node.id == selectedId, starred = node.id in favorites,
                                result = results[node.id], site = siteResults?.get(node.id), fastest = fastest, rowHeight = rowHeight,
                                onMeasured = { rowHeight = it }, lastIndexes = lastIndexes,
                                onSelect = { onSelect(node) }, onToggleFavorite = { onToggleFavorite(node) },
                                onShowQr = node.shareLink?.let { { onShowQr(node) } },
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * One server. It fades and rises in a moment after the list opens (each a little later than the one above), slides to
 * its new place when a test changes the order, and shows how its speed compares with the fastest as a thin bar.
 */
@Composable
private fun ServerRow(
    node: ProxyNode,
    index: Int,
    selected: Boolean,
    starred: Boolean,
    result: ember.subs.NodeResult?,
    site: ember.subs.SiteResult?,
    fastest: Double,
    rowHeight: Int,
    onMeasured: (Int) -> Unit,
    lastIndexes: MutableMap<String, Int>,
    onSelect: () -> Unit,
    onToggleFavorite: () -> Unit,
    onShowQr: (() -> Unit)?,
) {
    val strings = LocalStrings.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay((index * 22L).coerceAtMost(330L))
        appear.animateTo(1f, tween(300, easing = FastOutSlowInEasing))
    }
    val slide = remember { Animatable(0f) }
    LaunchedEffect(index) {
        val previous = lastIndexes[node.id]
        lastIndexes[node.id] = index
        if (previous != null && previous != index && rowHeight > 0) {
            slide.snapTo(((previous - index) * rowHeight).toFloat())
            slide.animateTo(0f, tween(500, easing = FastOutSlowInEasing))
        }
    }
    val background by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface, tween(250), label = "selected",
    )
    val speedBar = remember { Animatable(0f) }
    val barTarget = if (result?.mbps != null && fastest > 0) (result.mbps / fastest).toFloat().coerceIn(0.04f, 1f) else 0f
    LaunchedEffect(barTarget) { speedBar.animateTo(barTarget, tween(700, easing = FastOutSlowInEasing)) }
    val pop = remember { Animatable(1f) }
    var firstStar by remember { mutableStateOf(true) }
    LaunchedEffect(starred) {
        // a small bounce when the star is turned on (not when the list simply opens with it already on)
        if (!firstStar && starred) {
            pop.snapTo(0.55f)
            pop.animateTo(1f, spring(dampingRatio = 0.35f, stiffness = 420f))
        }
        firstStar = false
    }

    Column(
        Modifier.zIndex(if (slide.isRunning) 1f else 0f)
            .onSizeChanged { if (index == 0) onMeasured(it.height) }
            .graphicsLayer {
                alpha = appear.value
                translationY = slide.value + (1f - appear.value) * 10.dp.toPx()
            },
    ) {
        if (index > 0) HorizontalDivider(Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            Modifier.fillMaxWidth()
                .background(background)
                .hoverable(interaction)
                .pointerHoverIcon(PointerIcon.Hand)
                .clickable(role = Role.RadioButton, onClick = onSelect)
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
                if (result?.mbps != null) {
                    Box(
                        Modifier.padding(top = 6.dp, end = 12.dp).fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp))
                            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                    ) {
                        Box(
                            Modifier.fillMaxWidth(speedBar.value).fillMaxHeight().clip(RoundedCornerShape(2.dp))
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)),
                        )
                    }
                }
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
            // the QR button shows when the pointer is over the row or the row is the chosen server
            if (onShowQr != null && (hovered || selected)) IconAction("qr", strings.showQr, onClick = onShowQr)
            site?.let {
                val color = if (it.ok) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error
                Spacer(Modifier.width(8.dp))
                EmberIcon(if (it.ok) "check" else "close", 16.dp, color)
                Spacer(Modifier.width(2.dp))
                Text(
                    if (it.ok) "${it.ms} ms" else (it.code?.toString() ?: strings.noAnswer),
                    style = MaterialTheme.typography.labelSmall, color = color,
                )
                Spacer(Modifier.width(4.dp))
            }
            Box(Modifier.scale(pop.value)) {
                IconAction(
                    if (starred) "star-filled" else "star", strings.favorite, onClick = onToggleFavorite,
                    tint = if (starred) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (selected) {
                Spacer(Modifier.width(4.dp))
                EmberIcon("check", 20.dp, MaterialTheme.colorScheme.primary, contentDescription = strings.selected)
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
