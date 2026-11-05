package ember.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
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
import androidx.compose.ui.Alignment
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
import ember.engine.EmberEngine
import ember.subs.AUTO_NODE
import ember.subs.ProxyNode
import ember.engine.HTTP_PORT
import ember.engine.LOCAL_BIND
import ember.engine.MTU_RANGE
import ember.engine.SOCKS_PORT
import ember.engine.TrafficStats
import ember.engine.UpdateCheck
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

private enum class Page(val icon: String) {
    Home("home"),
    Servers("subscriptions"),
    Settings("settings"),
    Logs("logs"),
}

private val CardShape = RoundedCornerShape(16.dp)

@Composable
fun EmberApp(
    engine: EmberEngine,
    theme: String,
    onThemeChange: (String) -> Unit,
    language: String,
    onLanguageChange: (String) -> Unit,
    update: String?,
    onDismissUpdate: () -> Unit,
) {
    LaunchedEffect(Unit) {
        if (engine.settings.autoConnect && engine.state.value == ConnectionState.DISCONNECTED && engine.selectedNode() != null) {
            engine.connectSelected()
        }
    }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        MainShell(engine, theme, onThemeChange, language, onLanguageChange, update, onDismissUpdate)
    }
}

@Composable
internal fun Card(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(MaterialTheme.colorScheme.surface)
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), CardShape),
    ) { content() }
}

@Composable
private fun MainShell(
    engine: EmberEngine,
    theme: String,
    onThemeChange: (String) -> Unit,
    language: String,
    onLanguageChange: (String) -> Unit,
    update: String?,
    onDismissUpdate: () -> Unit,
) {
    var page by remember { mutableStateOf(if (engine.selectedNode() == null) Page.Servers else Page.Home) }

    Row(Modifier.fillMaxSize()) {
        Sidebar(page) { page = it }
        Box(Modifier.width(1.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant))
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
                    Page.Home -> HomePage(engine, update, onDismissUpdate) { page = Page.Servers }
                    Page.Servers -> SubscriptionsPage(engine)
                    Page.Settings -> SettingsPage(engine, theme, onThemeChange, language, onLanguageChange)
                    Page.Logs -> LogsPage()
                }
            }
        }
    }
}

@Composable
private fun Sidebar(selected: Page, onSelect: (Page) -> Unit) {
    val strings = LocalStrings.current
    Column(
        Modifier.width(220.dp).fillMaxHeight().background(MaterialTheme.colorScheme.surface).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(Modifier.padding(start = 8.dp, bottom = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource("ember_logo.png"), contentDescription = null, modifier = Modifier.size(36.dp))
            Spacer(Modifier.width(10.dp))
            Text("Ember", style = MaterialTheme.typography.headlineSmall)
        }
        for (p in Page.entries) {
            val active = p == selected
            val interaction = remember { MutableInteractionSource() }
            val hovered by interaction.collectIsHoveredAsState()
            val background by animateColorAsState(
                when {
                    active -> MaterialTheme.colorScheme.primaryContainer
                    hovered -> MaterialTheme.colorScheme.surfaceVariant
                    else -> MaterialTheme.colorScheme.surface
                },
                tween(220),
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(background)
                    .hoverable(interaction)
                    .pointerHoverIcon(PointerIcon.Hand)
                    .clickable(role = Role.Button) { onSelect(p) }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                val contentColor by animateColorAsState(
                    if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                    tween(220),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    EmberIcon(p.icon, 20.dp, contentColor)
                    Spacer(Modifier.width(12.dp))
                    Text(when (p) { Page.Home -> strings.home; Page.Servers -> strings.servers; Page.Settings -> strings.settings; Page.Logs -> strings.logs }, style = MaterialTheme.typography.titleSmall, color = contentColor)
                }
            }
        }
    }
}

@Composable
private fun HomePage(
    engine: EmberEngine, update: String?, onDismissUpdate: () -> Unit, onOpenServers: () -> Unit,
) {
    val strings = LocalStrings.current
    val state by engine.state.collectAsState()
    val error by engine.lastError.collectAsState()
    val connectedAt by engine.connectedAtMs.collectAsState()
    val blocked by engine.blockedNotice.collectAsState()
    val whole by engine.wholeDevice.collectAsState()
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
    var selectedId by remember { mutableStateOf(engine.settings.selectedNodeId) }
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

    val ring by animateColorAsState(when (state) {
        ConnectionState.CONNECTED -> MaterialTheme.colorScheme.tertiary
        ConnectionState.CONNECTING -> MaterialTheme.colorScheme.secondary
        ConnectionState.DISCONNECTED -> MaterialTheme.colorScheme.primary
    }, tween(220))
    val connectedScale = remember { Animatable(1f) }
    LaunchedEffect(state) {
        if (state == ConnectionState.CONNECTED) {
            connectedScale.snapTo(0.97f)
            connectedScale.animateTo(1f, tween(200))
        } else connectedScale.snapTo(1f)
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
        Box(
            Modifier.size(180.dp).scale(connectedScale.value).clip(CircleShape)
                .background(ring.copy(alpha = connectingRingAlpha(state)))
                .pointerHoverIcon(PointerIcon.Hand).clickable(role = Role.Button) {
                when {
                    state != ConnectionState.DISCONNECTED -> engine.disconnect()
                    engine.selectedNode() == null -> onOpenServers()
                    wholeSetting && engine.tunnel.isAvailable && !engine.settings.adminNoticeSeen -> showAdminNotice = true
                    else -> engine.connectSelected()
                }
            },
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(140.dp).clip(CircleShape).background(ring.copy(alpha = 0.25f)), contentAlignment = Alignment.Center) {
                Text(
                    when (state) {
                        ConnectionState.DISCONNECTED -> strings.connect
                        ConnectionState.CONNECTING -> strings.cancel
                        ConnectionState.CONNECTED -> strings.disconnect
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = ring,
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(
            when (state) {
                ConnectionState.CONNECTED -> strings.connected
                ConnectionState.CONNECTING -> strings.connecting
                ConnectionState.DISCONNECTED -> strings.disconnected
            },
            style = MaterialTheme.typography.headlineMedium,
        )
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
        traffic?.let { TrafficCaption(it) }
        if (state == ConnectionState.CONNECTED) SpeedTestControl(engine)
        Spacer(Modifier.height(32.dp))

        val connected = state == ConnectionState.CONNECTED
        val node = connectedNode ?: engine.selectedNode()
        val autoMode = selectedId == AUTO_NODE
        val exitCode = if (connected) exit.info?.countryCode.orEmpty() else ""
        val shownCode = if (exitCode.length == 2) exitCode else node?.flagCode.orEmpty()
        val result = node?.let { results[it.id] }
        val choices = remember(subscriptions, results) {
            subscriptions.flatMap { it.nodes }.filterNot { it.isInfo }.sortedWith(
                compareByDescending<ProxyNode> { results[it.id]?.mbps ?: -1.0 }.thenBy { results[it.id]?.pingMs ?: Int.MAX_VALUE },
            )
        }
        val choose: (String) -> Unit = {
            selectedId = it
            engine.settings.selectedNodeId = it
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
                    if (shownCode.length == 2) FlagIcon(shownCode, 24.dp)
                    else EmberIcon(if (autoMode) "bolt" else "subscriptions", 24.dp, MaterialTheme.colorScheme.onSurfaceVariant)
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
        if (connected && !whole) {
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
        if (update != null) {
            Spacer(Modifier.height(16.dp))
            UpdateBanner(update, onDismissUpdate)
        }
    }
}

@Composable
private fun SettingsPage(engine: EmberEngine, theme: String, onThemeChange: (String) -> Unit, language: String, onLanguageChange: (String) -> Unit) {
    val strings = LocalStrings.current
    var whole by remember { mutableStateOf(engine.settings.wholeDevice) }
    var killSwitch by remember { mutableStateOf(engine.settings.killSwitch) }
    var ipv6 by remember { mutableStateOf(engine.settings.ipv6) }
    val available = engine.tunnel.isAvailable
    val setupScope = rememberCoroutineScope()
    var noPrompt by remember { mutableStateOf(false) }
    var noPromptBusy by remember { mutableStateOf(true) }
    var noPromptError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        noPrompt = withContext(Dispatchers.IO) { available && engine.tunnel.privileged.isInstalled() }
        noPromptBusy = false
    }

    Text(strings.settings, style = MaterialTheme.typography.headlineLarge)
    Spacer(Modifier.height(16.dp))
    GeneralSettings(engine, theme, onThemeChange, language, onLanguageChange)
    Spacer(Modifier.height(16.dp))
    Card {
        Column {
            Text(strings.protection, Modifier.padding(16.dp), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            SwitchRow(
                strings.wholeDevice,
                if (available) strings.wholeDeviceHelp
                else strings.wholeDeviceUnavailable,
                whole && available,
                enabled = available,
            ) {
                whole = it
                engine.settings.wholeDevice = it
            }
            HorizontalDivider(Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
            SwitchRow(
                strings.killSwitch,
                strings.killSwitchHelp,
                killSwitch,
                enabled = available,
            ) {
                killSwitch = it
                engine.settings.killSwitch = it
            }
            HorizontalDivider(Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
            SwitchRow(strings.ipv6, strings.ipv6Help, ipv6, enabled = available) {
                ipv6 = it
                engine.settings.ipv6 = it
            }
            HorizontalDivider(Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
            SwitchRow(
                strings.noPrompts,
                strings.noPromptsHelp,
                noPrompt,
                enabled = available && !noPromptBusy,
            ) { wanted ->
                noPromptBusy = true
                noPromptError = null
                setupScope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            if (wanted) engine.tunnel.privileged.install() else engine.tunnel.privileged.uninstall()
                        }
                        noPrompt = wanted
                    } catch (failure: Throwable) {
                        noPromptError = failure.message ?: strings.setupFailed
                    } finally {
                        noPromptBusy = false
                    }
                }
            }
            noPromptError?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                )
            }
        }
    }
    Spacer(Modifier.height(16.dp))
    Spacer(Modifier.height(16.dp))
    Text(strings.trustedNetworksTitle, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
    TrustedNetworksSettings(engine)
    Spacer(Modifier.height(16.dp))
    SkipVpnSettings(engine, whole && available)
    Spacer(Modifier.height(16.dp))
    AdvancedSettings(engine)
    Spacer(Modifier.height(16.dp))
    Card {
        Column(Modifier.padding(16.dp)) {
            Text(strings.about, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Text(strings.appVersion(System.getProperty("jpackage.app-version") ?: strings.developmentBuild), style = MaterialTheme.typography.bodyMedium)
            Text(
                strings.logFile(ember.engine.appDataDir().resolve("ember.log").toString()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (available) {
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = { engine.restoreInternet() }, shape = RoundedCornerShape(12.dp)) { Text(strings.repairNetwork) }
                Spacer(Modifier.height(4.dp))
                Text(strings.repairNetworkHint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
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

@Composable
private fun GeneralSettings(engine: EmberEngine, theme: String, onThemeChange: (String) -> Unit, language: String, onLanguageChange: (String) -> Unit) {
    val strings = LocalStrings.current
    var keepInTray by remember { mutableStateOf(engine.settings.keepInTray) }
    var autoConnect by remember { mutableStateOf(engine.settings.autoConnect) }
    var autostart by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(true) }
    var failure by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        autostart = withContext(Dispatchers.IO) { Autostart.isEnabled() }
        busy = false
    }
    Card {
        Column {
            Text(strings.general, Modifier.padding(16.dp), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            SwitchRow(strings.keepInTray,
                strings.trayHelp, keepInTray,
                enabled = androidx.compose.ui.window.isTraySupported) {
                keepInTray = it
                engine.settings.keepInTray = it
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SwitchRow(strings.startWithWindows, strings.startQuietly, autostart,
                enabled = Autostart.supported && !busy) { enabled ->
                busy = true
                failure = null
                scope.launch {
                    val saved = withContext(Dispatchers.IO) { Autostart.setEnabled(enabled) }
                    if (saved) autostart = enabled
                    else failure = strings.startupFailed
                    busy = false
                }
            }
            failure?.let { Text(it, Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall) }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SwitchRow(strings.autoConnect, strings.autoConnectHelp, autoConnect) {
                autoConnect = it
                engine.settings.autoConnect = it
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Column(Modifier.padding(16.dp)) {
                Text(strings.appearance, style = MaterialTheme.typography.bodyLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (choice in listOf("Light", "Dark", "System")) {
                        EmberFilterChip(
                            selected = theme == choice,
                            onClick = { onThemeChange(choice) },
                            label = { Text(when (choice) { "Light" -> strings.light; "Dark" -> strings.dark; else -> strings.system }) },
                        )
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Column(Modifier.padding(16.dp)) {
                Text(strings.language, style = MaterialTheme.typography.bodyLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((code, label) in listOf("en" to strings.englishLanguage, "fa" to strings.persianLanguage)) {
                        EmberFilterChip(selected = language == code, onClick = { onLanguageChange(code) }, label = { Text(label) })
                    }
                }
            }
        }
    }
}

@Composable
private fun TrafficCaption(traffic: TrafficStats) {
    val reading by traffic.readings.collectAsState()
    if (reading.available) {
        Spacer(Modifier.height(8.dp))
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Text(
                "↓ ${formatBytesPerSecond(reading.downloadSpeed)}   ↑ ${formatBytesPerSecond(reading.uploadSpeed)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun UpdateBanner(version: String, onDismiss: () -> Unit) {
    val strings = LocalStrings.current
    val scope = rememberCoroutineScope()
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            strings.updateAvailable(version), modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        TextButton(onClick = {
            scope.launch(Dispatchers.IO) {
                runCatching {
                    if (Desktop.isDesktopSupported()) Desktop.getDesktop().browse(URI(UpdateCheck.RELEASES_URL))
                }
            }
        }) { Text(strings.viewUpdate) }
        TextButton(onClick = onDismiss) { Text(strings.dismiss) }
    }
}

private enum class LogFilter { All, Info, Problems }

@Composable
private fun LogsPage() {
    val strings = LocalStrings.current
    val entries by AppLogger.entries.collectAsState()
    var filter by remember { mutableStateOf(LogFilter.All) }
    val clipboard = LocalClipboardManager.current
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
        IconAction("copy", strings.copy, onClick = { clipboard.setText(AnnotatedString(lines.joinToString("\n"))) }, enabled = lines.isNotEmpty())
        IconAction("trash", strings.clear, onClick = { AppLogger.clear() }, enabled = entries.isNotEmpty())
    }
    Spacer(Modifier.height(12.dp))
    Card {
        SelectionContainer {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (lines.isEmpty()) Text(strings.emptyLogs, style = MaterialTheme.typography.bodySmall)
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

@Composable
private fun AdvancedSettings(engine: EmberEngine) {
    val strings = LocalStrings.current
    var mtuText by remember { mutableStateOf(engine.settings.mtu.toString()) }
    var expanded by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    val validMtu = parseMtu(mtuText) != null
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth().pointerHoverIcon(PointerIcon.Hand)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    role = Role.Button,
                ) { expanded = !expanded }.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(strings.advanced, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                EmberIcon(if (expanded) "chevron-up" else "chevron-down", 20.dp, MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (expanded) {
                Text(strings.mtuHelp, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    value = mtuText,
                    onValueChange = { value ->
                        mtuText = value
                        parseMtu(value)?.let { engine.settings.mtu = it }
                    },
                    label = { Text("MTU") },
                    isError = !validMtu,
                    supportingText = { if (!validMtu) Text(strings.mtuValidation(MTU_RANGE.first, MTU_RANGE.last)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(onClick = {
                    engine.settings.mtu = HevSocks5TunnelConfig.TUN_MTU
                    mtuText = engine.settings.mtu.toString()
                }) { Text(strings.resetDefault) }
                Text(strings.nextConnection, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun parseMtu(value: String): Int? =
    value.takeIf { it.isNotEmpty() && it.all { char -> char in '0'..'9' } }
        ?.toIntOrNull()?.takeIf { it in MTU_RANGE }

@Composable
private fun connectingRingAlpha(state: ConnectionState): Float {
    if (state != ConnectionState.CONNECTING) return 0.15f
    val transition = rememberInfiniteTransition()
    val alpha by transition.animateFloat(
        initialValue = 0.12f, targetValue = 0.20f,
        animationSpec = infiniteRepeatable(tween(300), RepeatMode.Reverse),
    )
    return alpha
}
