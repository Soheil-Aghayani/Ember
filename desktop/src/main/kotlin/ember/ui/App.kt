package ember.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.scale
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.drawBehind
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.soheil.ember.data.AppLogger
import com.soheil.ember.data.LogLevel
import com.soheil.ember.data.formatBytes
import com.soheil.ember.data.formatBytesPerSecond
import com.soheil.ember.data.model.ConnectionState
import com.soheil.ember.vpn.tun.HevSocks5TunnelConfig
import ember.engine.Autostart
import ember.engine.Diagnostics
import ember.engine.EmberEngine
import ember.subs.AUTO_NODE
import ember.subs.ProxyNode
import ember.subs.sortNodes
import ember.engine.HTTP_PORT
import ember.engine.LOCAL_BIND
import ember.engine.MTU_RANGE
import ember.engine.SOCKS_PORT
import ember.engine.TrafficStats
import java.awt.Desktop
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext

/** [bottom] pages sit at the bottom of the sidebar, apart from the main ones. */
internal enum class Page(val icon: String, val bottom: Boolean = false) {
    Home("home"),
    Servers("subscriptions"),
    Settings("settings"),
    Logs("logs"),
    WhatsNew("whats-new", bottom = true),
    About("info-circle", bottom = true),
}

private val CardShape = RoundedCornerShape(16.dp)

/**
 * A scope that lives as long as the window, for work that must finish even if the user leaves the page that started it
 * (adding servers, registering with Cloudflare, refreshing a subscription).
 */
internal val LocalAppScope = staticCompositionLocalOf<kotlinx.coroutines.CoroutineScope> { error("no app scope") }

/** Like [runCatching], but a cancelled coroutine is not a failure to report: the cancellation goes on. */
internal inline fun <T> attempt(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (cancel: kotlinx.coroutines.CancellationException) {
    throw cancel
} catch (failure: Throwable) {
    Result.failure(failure)
}

@Composable
fun EmberApp(
    engine: EmberEngine,
    theme: String,
    onThemeChange: (String) -> Unit,
    language: String,
    onLanguageChange: (String) -> Unit,
) {
    LaunchedEffect(Unit) {
        if (engine.settings.autoConnect && engine.state.value == ConnectionState.DISCONNECTED && engine.selectedNode() != null) {
            engine.connectSelected()
        }
    }
    val toaster = remember { Toaster() }
    val appScope = rememberCoroutineScope()
    val notice by engine.notice.collectAsState()
    LaunchedEffect(notice) {
        notice?.let {
            toaster.show(it)
            engine.clearNotice()
        }
    }
    CompositionLocalProvider(LocalToaster provides toaster, LocalAppScope provides appScope) {
        Box(Modifier.fillMaxSize()) {
            MainShell(engine, theme, onThemeChange, language, onLanguageChange)
            CredentialDialog(engine)
            ToastHost(toaster)
        }
    }
}

@Composable
internal fun Card(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(glassFill())
            .border(BorderStroke(1.dp, glassEdge()), CardShape),
    ) { CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) { content() } }
}

@Composable
private fun MainShell(
    engine: EmberEngine,
    theme: String,
    onThemeChange: (String) -> Unit,
    language: String,
    onLanguageChange: (String) -> Unit,
) {
    var page by remember { mutableStateOf(if (engine.selectedNode() == null) Page.Servers else Page.Home) }

    Row(Modifier.fillMaxSize()) {
        Sidebar(page) { page = it }
        Box(Modifier.width(1.dp).fillMaxHeight().background(glassDivider()))
        AnimatedContent(
            targetState = page,
            modifier = Modifier.weight(1f).fillMaxHeight(),
            contentAlignment = Alignment.TopCenter,
            transitionSpec = {
                // The new page rises or sinks in from the side of the sidebar item it came from, while the old one fades out.
                val direction = if (targetState.ordinal > initialState.ordinal) 1 else -1
                (fadeIn(tween(240, delayMillis = 70)) +
                    slideInVertically(tween(300, easing = FastOutSlowInEasing)) { direction * it / 20 } +
                    scaleIn(tween(300, easing = FastOutSlowInEasing), initialScale = 0.97f)) togetherWith
                    fadeOut(tween(110))
            },
            label = "page",
        ) { current ->
            Column(
                Modifier.widthIn(max = 640.dp).fillMaxWidth().fillMaxHeight().verticalScroll(rememberScrollState()).padding(32.dp),
            ) {
                when (current) {
                    Page.Home -> HomePage(engine) { page = Page.Servers }
                    Page.Servers -> SubscriptionsPage(engine)
                    Page.Settings -> SettingsPage(engine, theme, onThemeChange, language, onLanguageChange)
                    Page.Logs -> LogsPage()
                    Page.WhatsNew -> WhatsNewPage(language)
                    Page.About -> AboutPage()
                }
            }
        }
    }
}

@Composable
internal fun Sidebar(selected: Page, onSelect: (Page) -> Unit) {
    val strings = LocalStrings.current
    Column(
        Modifier.width(220.dp).fillMaxHeight().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(Modifier.padding(start = 8.dp, bottom = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource("ember_logo.png"), contentDescription = null, modifier = Modifier.size(36.dp))
            Spacer(Modifier.width(10.dp))
            Text("Ember", style = MaterialTheme.typography.headlineSmall)
        }
        for (p in Page.entries) {
            if (p.bottom && p == Page.entries.first { it.bottom }) Spacer(Modifier.weight(1f))
            val active = p == selected
            val interaction = remember { MutableInteractionSource() }
            val hovered by interaction.collectIsHoveredAsState()
            // the highlights are drawn as see-through tints whose strength is read while drawing, so a fast sweep of the
            // mouse fades each item in and out on its own without redrawing the window or leaving a grey patch behind
            val hoverStrength = animateFloatAsState(if (hovered && !active) 1f else 0f, tween(if (hovered) 110 else 280, easing = FastOutSlowInEasing))
            val activeStrength = animateFloatAsState(if (active) 1f else 0f, tween(260, easing = FastOutSlowInEasing))
            val hoverTint = MaterialTheme.colorScheme.primary.copy(alpha = 0.09f)
            val activeTint = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .drawBehind {
                        drawRect(hoverTint.copy(alpha = hoverTint.alpha * hoverStrength.value))
                        drawRect(activeTint.copy(alpha = activeTint.alpha * activeStrength.value))
                    }
                    .hoverable(interaction)
                    .pointerHoverIcon(PointerIcon.Hand)
                    .clickable(role = Role.Button) { onSelect(p) }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                val contentColor by animateColorAsState(
                    if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    tween(200),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    EmberIcon(p.icon, 20.dp, contentColor)
                    Spacer(Modifier.width(12.dp))
                    Text(when (p) { Page.Home -> strings.home; Page.Servers -> strings.servers; Page.Settings -> strings.settings; Page.Logs -> strings.logs; Page.WhatsNew -> strings.whatsNew; Page.About -> strings.about }, style = MaterialTheme.typography.titleSmall, color = contentColor)
                }
            }
        }
    }
}

@Composable
private fun HomePage(
    engine: EmberEngine, onOpenServers: () -> Unit,
) {
    val strings = LocalStrings.current
    val state by engine.state.collectAsState()
    val error by engine.lastError.collectAsState()
    val connectedAt by engine.connectedAtMs.collectAsState()
    val blocked by engine.blockedNotice.collectAsState()
    val whole by engine.wholeDevice.collectAsState()
    val viaOpenVpn by engine.usingOpenVpn.collectAsState()
    val viaAppRules by engine.appRules.collectAsState()
    val traffic = remember(state, whole) {
        if (state == ConnectionState.CONNECTED && whole) TrafficStats() else null
    }
    DisposableEffect(traffic) { onDispose { traffic?.close() } }
    val wholeSetting = engine.settings.wholeDevice
    val progress by engine.progress.collectAsState()
    var showAdminNotice by remember { mutableStateOf(false) }
    val connectedNode by engine.connectedNode.collectAsState()
    val exit by engine.exit.collectAsState()
    val results by engine.subscriptions.results.collectAsState()
    val subscriptions by engine.subscriptions.subscriptions.collectAsState()
    val testing by engine.testProgress.collectAsState()
    val selectedId by engine.selectedId.collectAsState()
    val favoriteIds by engine.favorites.collectAsState()
    var menuOpen by remember { mutableStateOf(false) }
    val canPick = state == ConnectionState.DISCONNECTED
    LaunchedEffect(canPick) { if (!canPick) menuOpen = false }
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    var elapsed by remember { mutableStateOf(0L) }

    LaunchedEffect(state, connectedAt) {
        val start = connectedAt
        if (state == ConnectionState.CONNECTED && start != null) {
            while (true) {
                elapsed = ((System.currentTimeMillis() - start) / 1000).coerceAtLeast(0)
                delay(1000)
            }
        } else {
            elapsed = 0
        }
    }

    if (showAdminNotice) {
        AlertDialog(
            onDismissRequest = { showAdminNotice = false },
            title = { Text(strings.adminTitle) },
            text = { Text(strings.adminHelp) },
            confirmButton = {
                Button(onClick = {
                    engine.settings.adminNoticeSeen = true
                    showAdminNotice = false
                    engine.connectSelected()
                }) { Text(strings.continueLabel) }
            },
            dismissButton = { TextButton(onClick = { showAdminNotice = false }) { Text(strings.cancel) } },
        )
    }

    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(24.dp))
        ConnectOrb(
            state = state,
            label = when (state) {
                ConnectionState.DISCONNECTED -> strings.connect
                ConnectionState.CONNECTING -> strings.cancel
                ConnectionState.CONNECTED -> strings.disconnect
            },
            onClick = {
                when {
                    state != ConnectionState.DISCONNECTED -> engine.disconnect()
                    engine.selectedNode() == null -> onOpenServers()
                    wholeSetting && engine.tunnel.isAvailable && !engine.settings.adminNoticeSeen -> showAdminNotice = true
                    else -> engine.connectSelected()
                }
            },
        )
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            // the check mark pops in when the connection comes up
            AnimatedVisibility(
                visible = state == ConnectionState.CONNECTED,
                enter = scaleIn(tween(320, easing = FastOutSlowInEasing)) + fadeIn(tween(240)),
                exit = fadeOut(tween(120)),
            ) {
                Row {
                    EmberIcon("check", 28.dp, MaterialTheme.colorScheme.tertiary)
                    Spacer(Modifier.width(8.dp))
                }
            }
            Text(
                when (state) {
                    ConnectionState.CONNECTED -> strings.connected
                    ConnectionState.CONNECTING -> strings.connecting
                    ConnectionState.DISCONNECTED -> strings.disconnected
                },
                style = MaterialTheme.typography.headlineMedium,
            )
        }
        val secondary = when (state) {
            ConnectionState.CONNECTED -> formatDuration(elapsed)
            ConnectionState.CONNECTING -> progress
            ConnectionState.DISCONNECTED -> null
        }
        if (!secondary.isNullOrBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                secondary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        traffic?.let { SpeedReadout(it) }
        if (state == ConnectionState.CONNECTED) SpeedTestControl(engine)
        Spacer(Modifier.height(32.dp))

        val connected = state == ConnectionState.CONNECTED
        val node = connectedNode ?: engine.selectedNode()
        val autoMode = selectedId == AUTO_NODE
        val exitCode = if (connected) exit.info?.countryCode.orEmpty() else ""
        val shownCode = if (exitCode.length == 2) exitCode else node?.flagCode.orEmpty()
        val result = node?.let { results[it.id] }
        val choices = remember(subscriptions, results, favoriteIds) {
            sortNodes(subscriptions.flatMap { it.nodes }.filterNot { it.isInfo }, results, favoriteIds)
        }
        val choose: (String) -> Unit = {
            engine.chooseServer(it)
            menuOpen = false
        }
        Box(Modifier.fillMaxWidth()) {
            Card {
                Row(
                    Modifier.fillMaxWidth().background(
                        if (hovered && canPick) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface,
                    ).hoverable(interaction, enabled = canPick)
                        .pointerHoverIcon(if (canPick) PointerIcon.Hand else PointerIcon.Default)
                        .clickable(enabled = canPick, role = Role.Button) { if (choices.isEmpty()) onOpenServers() else menuOpen = !menuOpen }
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // the flag slides out and the next one slides in when the server changes
                    AnimatedContent(
                        targetState = shownCode.takeIf { it.length == 2 } ?: (if (autoMode) "bolt" else "subscriptions"),
                        transitionSpec = {
                            (slideInHorizontally(tween(260, easing = FastOutSlowInEasing)) { -it } + fadeIn(tween(200))) togetherWith
                                (slideOutHorizontally(tween(200)) { it } + fadeOut(tween(120)))
                        },
                        label = "flag",
                    ) { shown ->
                        if (shown.length == 2) FlagIcon(shown, 24.dp)
                        else EmberIcon(shown, 24.dp, MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        when {
                            autoMode && !connected -> strings.fastestServer + (node?.let { " · " + it.title } ?: "")
                            else -> node?.title ?: strings.chooseServer
                        },
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!connected) {
                        val speed = result?.mbps?.let { String.format(java.util.Locale.US, "%.1f Mbps", it) }
                        val ping = result?.pingMs?.let { "$it ms" }
                        (speed ?: ping)?.let {
                            Spacer(Modifier.width(12.dp))
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (canPick) {
                        Spacer(Modifier.width(12.dp))
                        EmberIcon(if (menuOpen) "chevron-up" else "chevron-down", 16.dp, MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            DropdownMenu(
                expanded = menuOpen && canPick,
                onDismissRequest = { menuOpen = false },
                modifier = Modifier
                    .width(EmberDesktopMetrics.countryPickerWidth)
                    .heightIn(max = EmberDesktopMetrics.countryPickerMaxHeight),
                offset = DpOffset(0.dp, 6.dp),
                shape = RoundedCornerShape(EmberDesktopMetrics.countryPopoverRadius),
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 0.dp,
                shadowElevation = 6.dp,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                DropdownMenuItem(
                    text = { Text(strings.fastestServer) },
                    leadingIcon = { EmberIcon("bolt", 24.dp, MaterialTheme.colorScheme.primary) },
                    trailingIcon = { if (autoMode) EmberIcon("check", 20.dp, MaterialTheme.colorScheme.primary, contentDescription = strings.selected) },
                    onClick = { choose(AUTO_NODE) },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                for (choice in choices) {
                    val r = results[choice.id]
                    val label = r?.mbps?.let { String.format(java.util.Locale.US, "%.0f Mbps", it) } ?: r?.pingMs?.let { "$it ms" }
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(choice.title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                label?.let {
                                    Spacer(Modifier.width(12.dp))
                                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        },
                        leadingIcon = {
                            val code = choice.flagCode
                            if (code != null) FlagIcon(code) else EmberIcon("subscriptions", 24.dp, MaterialTheme.colorScheme.onSurfaceVariant)
                        },
                        trailingIcon = { if (!autoMode && choice.id == selectedId) EmberIcon("check", 20.dp, MaterialTheme.colorScheme.primary, contentDescription = strings.selected) },
                        onClick = { choose(choice.id) },
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                DropdownMenuItem(
                    text = { Text(strings.manageServers) },
                    onClick = { menuOpen = false; onOpenServers() },
                )
            }
        }
        if ((canPick && choices.isNotEmpty()) || testing != null) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (testing != null) {
                    IconAction("stop", strings.stopTest, onClick = { engine.cancelTest() }, tint = MaterialTheme.colorScheme.primary)
                } else {
                    IconAction("bolt", strings.testSpeed, onClick = { engine.testNodes(choices) }, tint = MaterialTheme.colorScheme.primary)
                }
                testing?.let {
                    Spacer(Modifier.width(8.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (connected && !whole && !viaOpenVpn && !viaAppRules) {
            Spacer(Modifier.height(12.dp))
            Text(
                strings.proxyModeCaption("$LOCAL_BIND:$SOCKS_PORT", "$LOCAL_BIND:$HTTP_PORT"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        error?.let {
            Spacer(Modifier.height(16.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (connected) exit.error?.let {
            Spacer(Modifier.height(12.dp))
            Text(strings.connectionCheckFailed(it), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        blocked?.let { notice ->
            Spacer(Modifier.height(16.dp))
            Card {
                Column(Modifier.padding(16.dp)) {
                    Text(strings.internetBlocked, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(4.dp))
                    Text(notice, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { engine.restoreInternet() }, shape = RoundedCornerShape(12.dp)) { Text(strings.restoreInternet) }
                }
            }
        }
    }
}

@Composable
internal fun SwitchRow(title: String, subtitle: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

private fun formatDuration(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
}

private enum class LogFilter { All, Info, Problems }

@Composable
internal fun LogsPage() {
    val strings = LocalStrings.current
    val entries by AppLogger.entries.collectAsState()
    var filter by remember { mutableStateOf(LogFilter.All) }
    val clipboard = LocalClipboardManager.current
    val toaster = LocalToaster.current
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US) }
    val lines = remember(entries, filter) {
        entries.asReversed().filter {
            when (filter) {
                LogFilter.All -> true
                LogFilter.Info -> it.level == LogLevel.INFO
                LogFilter.Problems -> it.level == LogLevel.WARN || it.level == LogLevel.ERROR
            }
        }.map { "${dateFormat.format(Date(it.timestampMillis))} ${it.level} [${it.tag}] ${it.message}" }
    }
    Text(strings.logs, style = MaterialTheme.typography.headlineLarge)
    Spacer(Modifier.height(16.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        for (choice in LogFilter.entries) {
            EmberFilterChip(
                selected = filter == choice,
                onClick = { filter = choice },
                label = { Text(when (choice) { LogFilter.All -> strings.allLogs; LogFilter.Info -> strings.infoLogs; LogFilter.Problems -> strings.problemLogs }) },
            )
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        IconAction("copy", strings.copy, onClick = {
            clipboard.setText(AnnotatedString(lines.joinToString("\n")))
            toaster.show(strings.copied, ToastKind.Success)
        }, enabled = lines.isNotEmpty())
        IconAction("trash", strings.clear, onClick = { AppLogger.clear() }, enabled = entries.isNotEmpty())
        TextButton(onClick = {
            val recent = entries.takeLast(120).map { "${dateFormat.format(Date(it.timestampMillis))} ${it.level} [${it.tag}] ${it.message}" }
            clipboard.setText(AnnotatedString(Diagnostics.report(System.getProperty("jpackage.app-version"), recent)))
            toaster.show(strings.reportCopied, ToastKind.Success)
        }) { Text(strings.copyReport) }
    }
    Text(strings.copyReportHint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(12.dp))
    Card {
        SelectionContainer {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (lines.isEmpty()) EmptyState("logs", strings.emptyLogsTitle, strings.emptyLogs)
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (line in lines) Text(line, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
    }
}

// Keep desktop chips visually stable on hover; selection remains the only chip fill state.
@Composable
private fun EmberFilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: @Composable () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = label,
        elevation = FilterChipDefaults.filterChipElevation(hoveredElevation = 0.dp),
    )
}
