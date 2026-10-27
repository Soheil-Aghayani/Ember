package ember.ui

import androidx.compose.animation.animateColorAsState
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
import androidx.compose.ui.unit.dp
import com.soheil.ember.data.AppLogger
import com.soheil.ember.data.FxaAuthRepository
import com.soheil.ember.data.LogLevel
import com.soheil.ember.data.RECOMMENDED_COUNTRY_CODE
import com.soheil.ember.data.ServerListClient
import com.soheil.ember.data.SessionStatus
import com.soheil.ember.data.TlsSplitMode
import com.soheil.ember.data.formatBytes
import com.soheil.ember.data.formatBytesPerSecond
import com.soheil.ember.data.model.ConnectionState
import com.soheil.ember.data.model.ProxyCandidate
import com.soheil.ember.data.model.VpnCountry
import com.soheil.ember.vpn.tun.HevSocks5TunnelConfig
import ember.engine.Autostart
import ember.engine.EmberEngine
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
    Locations("locations"),
    Settings("settings"),
    Logs("logs"),
}

private enum class Session { Checking, SignedOut, SignedIn }

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
    var session by remember { mutableStateOf(Session.Checking) }

    LaunchedEffect(Unit) {
        val status = withContext(Dispatchers.IO) { runCatching { engine.auth.restoreSession() }.getOrNull() }
        session = if (status == SessionStatus.NEEDS_LOGIN || status == null) Session.SignedOut else Session.SignedIn
    }

    var countries by remember { mutableStateOf<List<VpnCountry>>(emptyList()) }
    var countriesLoading by remember { mutableStateOf(true) }
    var countriesFailure by remember { mutableStateOf<String?>(null) }
    val strings = LocalStrings.current
    LaunchedEffect(engine) {
        try {
            countries = withContext(Dispatchers.IO) { engine.servers.loadCachedCountries() }
            countries = withContext(Dispatchers.IO) {
                withTimeoutOrNull(10_000) { engine.servers.fetchCountries() } ?: countries
            }
            if (countries.isEmpty()) countriesFailure = strings.serverListFailed
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            if (countries.isEmpty()) countriesFailure = failure.message ?: strings.serverListFailed
        } finally {
            countriesLoading = false
        }
    }

    var startupHandled by remember { mutableStateOf(false) }
    var locationReady by remember { mutableStateOf(false) }
    var restoredLocation by remember { mutableStateOf<ProxyCandidate?>(null) }
    LaunchedEffect(session, countriesLoading, countries.isEmpty()) {
        if (session != Session.SignedIn || startupHandled) return@LaunchedEffect
        if (engine.settings.lastCountry.isNotBlank() && countriesLoading && countries.isEmpty()) return@LaunchedEffect
        startupHandled = true
        val country = engine.settings.lastCountry
        if (country.isNotBlank()) {
            restoredLocation = if (engine.settings.lastCity.isBlank()) {
                ServerListClient.candidatesForCountry(countries, country).randomOrNull()
            } else {
                ServerListClient.candidatesForCity(countries, country, engine.settings.lastCity).randomOrNull()
            }
        }
        locationReady = true
        if (engine.settings.autoConnect && engine.state.value == ConnectionState.DISCONNECTED) {
            engine.connect(restoredLocation)
        }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        when (session) {
            Session.Checking -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
            }
            Session.SignedOut -> LoginPage(engine.auth) { session = Session.SignedIn }
            Session.SignedIn -> if (!locationReady) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
                }
            } else MainShell(engine, countries, countriesLoading, countriesFailure, restoredLocation, theme, onThemeChange, language, onLanguageChange, update, onDismissUpdate) {
                engine.disconnect()
                engine.tokenStore.clear()
                session = Session.SignedOut
            }
        }
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
private fun LoginPage(auth: FxaAuthRepository, onSignedIn: () -> Unit) {
    val strings = LocalStrings.current
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var needsCode by remember { mutableStateOf(auth.pendingSessionToken != null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 420.dp).fillMaxWidth().padding(32.dp)) {
            Image(painterResource("ember_logo.png"), contentDescription = null, modifier = Modifier.size(72.dp))
            Spacer(Modifier.height(12.dp))
            Text(strings.signIn, style = MaterialTheme.typography.headlineLarge)
            Spacer(Modifier.height(8.dp))
            Text(
                strings.loginIntro,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))

            if (!needsCode) {
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text(strings.email) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(strings.password) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = {
                        error = null
                        busy = true
                        scope.launch {
                            val result = auth.startLogin(FxaAuthRepository.normalizeEmail(email), password)
                            busy = false
                            result.onSuccess { verify -> if (verify) needsCode = true else onSignedIn() }
                                .onFailure { error = it.message ?: strings.signInFailed }
                        }
                    },
                    enabled = !busy && email.isNotBlank() && password.isNotBlank(),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                ) { Text(if (busy) strings.signingIn else strings.continueLabel) }
            } else {
                Text(strings.codePrompt, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it },
                    label = { Text(strings.confirmationCode) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = {
                        error = null
                        busy = true
                        scope.launch {
                            val result = auth.submitTwoFactorCode(code)
                            busy = false
                            result.onSuccess { onSignedIn() }.onFailure {
                                error = it.message ?: strings.verificationFailed
                                if (auth.pendingSessionToken == null) needsCode = false
                            }
                        }
                    },
                    enabled = !busy && code.isNotBlank(),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                ) { Text(if (busy) strings.verifying else strings.verify) }
            }

            error?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun MainShell(
    engine: EmberEngine,
    countries: List<VpnCountry>,
    countriesLoading: Boolean,
    countriesFailure: String?,
    restoredLocation: ProxyCandidate?,
    theme: String,
    onThemeChange: (String) -> Unit,
    language: String,
    onLanguageChange: (String) -> Unit,
    update: String?,
    onDismissUpdate: () -> Unit,
    onSignOut: () -> Unit,
) {
    var page by remember { mutableStateOf(Page.Home) }
    var selected by remember(restoredLocation) { mutableStateOf(restoredLocation) }

    val state by engine.state.collectAsState()
    val pickLocation: (ProxyCandidate?, String, String) -> Unit = { candidate, country, city ->
        if (engine.state.value == ConnectionState.DISCONNECTED) {
            engine.settings.lastCountry = country
            engine.settings.lastCity = city
            selected = candidate
            page = Page.Home
        }
    }

    Row(Modifier.fillMaxSize()) {
        Sidebar(page) { page = it }
        Box(Modifier.width(1.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant))
        Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 640.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(32.dp),
            ) {
                when (page) {
                    Page.Home -> HomePage(engine, selected, countries, countriesLoading, countriesFailure,
                        pickLocation, update, onDismissUpdate) { page = Page.Locations }
                    Page.Locations -> LocationsPage(countries, countriesLoading, countriesFailure,
                        state == ConnectionState.DISCONNECTED, selected, engine.settings.lastCity, pickLocation)
                    Page.Settings -> SettingsPage(engine, theme, onThemeChange, language, onLanguageChange, onSignOut)
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
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(when {
                        active -> MaterialTheme.colorScheme.primaryContainer
                        hovered -> MaterialTheme.colorScheme.surfaceVariant
                        else -> MaterialTheme.colorScheme.surface
                    })
                    .hoverable(interaction)
                    .pointerHoverIcon(PointerIcon.Hand)
                    .clickable(role = Role.Button) { onSelect(p) }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                val contentColor = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                Row(verticalAlignment = Alignment.CenterVertically) {
                    EmberIcon(p.icon, 20.dp, contentColor)
                    Spacer(Modifier.width(12.dp))
                    Text(when (p) { Page.Home -> strings.home; Page.Locations -> strings.locations; Page.Settings -> strings.settings; Page.Logs -> strings.logs }, style = MaterialTheme.typography.titleSmall, color = contentColor)
                }
            }
        }
    }
}

@Composable
private fun HomePage(
    engine: EmberEngine, selected: ProxyCandidate?, countries: List<VpnCountry>,
    countriesLoading: Boolean, countriesFailure: String?,
    onPick: (ProxyCandidate?, String, String) -> Unit, update: String?, onDismissUpdate: () -> Unit,
    onOpenLocations: () -> Unit,
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
    val target by engine.connectedTarget.collectAsState()
    val exit by engine.exit.collectAsState()
    val quota by engine.quota.collectAsState()
    LaunchedEffect(Unit) { engine.refreshQuota() }
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
            text = {
                Text(
                    strings.adminHelp,
                )
            },
            confirmButton = {
                Button(onClick = {
                    engine.settings.adminNoticeSeen = true
                    showAdminNotice = false
                    engine.connect(selected)
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
                    wholeSetting && engine.tunnel.isAvailable && !engine.settings.adminNoticeSeen -> showAdminNotice = true
                    else -> engine.connect(selected)
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
        if (connected) SpeedTestControl(engine)
        Spacer(Modifier.height(32.dp))

        val connected = state == ConnectionState.CONNECTED
        val location = if (connected) target else selected
        val exitCode = if (connected) exit.info?.countryCode.orEmpty() else ""
        val isRecommended = location == null || location.countryCode.equals(RECOMMENDED_COUNTRY_CODE, ignoreCase = true) || location.countryCode.isBlank()
        val shownCode = when {
            exitCode.length == 2 -> exitCode
            isRecommended -> ""
            else -> location?.countryCode.orEmpty()
        }
        val shownName = if (shownCode.length == 2) {
            localizedCountry(shownCode, location?.countryName.orEmpty(), strings)
        } else strings.recommended
        Box(Modifier.fillMaxWidth()) {
            Card {
                Row(
                    Modifier.fillMaxWidth().background(
                        if (hovered && canPick) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface,
                    ).hoverable(interaction, enabled = canPick)
                        .pointerHoverIcon(if (canPick) PointerIcon.Hand else PointerIcon.Default)
                        .clickable(enabled = canPick, role = Role.Button) { menuOpen = !menuOpen }
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (shownCode.length == 2) FlagIcon(shownCode, 24.dp) else EmberIcon("locations", 24.dp, MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(12.dp))
                    Text(shownName, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    if (canPick) {
                        Spacer(Modifier.width(12.dp))
                        EmberIcon(if (menuOpen) "chevron-up" else "chevron-down", 16.dp, MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            DropdownMenu(expanded = menuOpen && canPick, onDismissRequest = { menuOpen = false },
                modifier = Modifier.heightIn(max = 360.dp).widthIn(min = 260.dp)) {
                DropdownMenuItem(
                    text = { Text(strings.recommended) },
                    leadingIcon = { EmberIcon("locations", 24.dp, MaterialTheme.colorScheme.onSurfaceVariant) },
                    trailingIcon = { if (isRecommended) Text(strings.selected, color = MaterialTheme.colorScheme.primary) },
                    onClick = { onPick(null, "", ""); menuOpen = false },
                )
                for (country in countries.filter { it.code.isNotBlank() && it.code != RECOMMENDED_COUNTRY_CODE }
                    .sortedBy { localizedCountry(it.code, it.name, strings) }) {
                    DropdownMenuItem(
                        text = { Text(localizedCountry(country.code, country.name, strings)) },
                        leadingIcon = { FlagIcon(country.code) },
                        trailingIcon = { if (selected?.countryCode == country.code) Text(strings.selected, color = MaterialTheme.colorScheme.primary) },
                        onClick = {
                            onPick(ServerListClient.candidatesForCountry(countries, country.code).randomOrNull(), country.code, "")
                            menuOpen = false
                        },
                    )
                }
                if (countriesLoading && countries.isEmpty()) {
                    CircularProgressIndicator(Modifier.padding(16.dp).size(24.dp), strokeWidth = 2.dp)
                }
                countriesFailure?.let { Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                DropdownMenuItem(text = { Text(strings.moreLocations) }, onClick = { menuOpen = false; onOpenLocations() })
            }
        }
        quota?.remaining?.let { remaining ->
            Spacer(Modifier.height(12.dp))
            val reset = quota?.resetEpochSeconds?.let { resetLabel(it, strings) }
            Text(
                strings.quotaFooter(formatBytes(remaining), reset),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
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
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (connected) exit.error?.let {
            Spacer(Modifier.height(12.dp))
            Text(strings.connectionCheckFailed(it), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
private fun LocationsPage(
    countries: List<VpnCountry>,
    loading: Boolean,
    failure: String?,
    enabled: Boolean,
    selected: ProxyCandidate?,
    selectedCity: String,
    onPick: (ProxyCandidate?, String, String) -> Unit,
) {
    val strings = LocalStrings.current
    var expanded by remember { mutableStateOf(setOf<String>()) }
    val pick: (ProxyCandidate?, String, String) -> Unit = { candidate, country, city ->
        if (enabled) onPick(candidate, country, city)
    }

    Text(strings.chooseLocation, style = MaterialTheme.typography.headlineLarge)
    Spacer(Modifier.height(16.dp))

    if (loading && countries.isEmpty()) {
        CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
        return
    }
    failure?.let {
        Text(it, color = MaterialTheme.colorScheme.error)
        return
    }

    val ordered = countries.filter { it.code != RECOMMENDED_COUNTRY_CODE && it.code.isNotBlank() }.sortedBy { it.name }

    Card {
        Column {
            LocationRow(strings.recommended, strings.automaticLocation, selected == null) { pick(null, "", "") }
            for (country in ordered) {
                HorizontalDivider(Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                val open = country.code in expanded
                LocationRow(
                    localizedCountry(country.code, country.name, strings),
                    strings.cities(country.cities.size),
                    selected?.countryCode == country.code && selectedCity.isBlank(),
                    flagCode = country.code,
                    expanded = open,
                    onToggle = { expanded = if (open) expanded - country.code else expanded + country.code },
                ) {
                    pick(ServerListClient.candidatesForCountry(countries, country.code).randomOrNull(), country.code, "")
                }
                if (open) {
                    for (city in country.cities) {
                        HorizontalDivider(Modifier.padding(start = 32.dp), color = MaterialTheme.colorScheme.outlineVariant)
                        LocationRow(
                            city.name.ifBlank { city.code },
                            strings.servers(city.servers.size),
                            selected?.countryCode == country.code && selectedCity == city.code,
                            indent = 16,
                        ) {
                            pick(ServerListClient.candidatesForCity(countries, country.code, city.code).randomOrNull(), country.code, city.code)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LocationRow(
    title: String,
    subtitle: String,
    isSelected: Boolean,
    indent: Int = 0,
    flagCode: String? = null,
    expanded: Boolean = false,
    onToggle: (() -> Unit)? = null,
    onPick: () -> Unit,
) {
    val strings = LocalStrings.current
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onPick).padding(start = (16 + indent).dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (flagCode != null) {
            FlagIcon(flagCode, 28.dp)
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (isSelected) Text(strings.selected, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        if (onToggle != null) {
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier.size(36.dp).clip(CircleShape).clickable(onClick = onToggle),
                contentAlignment = Alignment.Center,
            ) {
                EmberIcon(
                    if (expanded) "chevron-up" else "chevron-down",
                    20.dp,
                    MaterialTheme.colorScheme.onSurfaceVariant,
                    if (expanded) strings.hideCities else strings.showCities,
                )
            }
        }
    }
}

@Composable
private fun SettingsPage(engine: EmberEngine, theme: String, onThemeChange: (String) -> Unit, language: String, onLanguageChange: (String) -> Unit, onSignOut: () -> Unit) {
    val strings = LocalStrings.current
    var whole by remember { mutableStateOf(engine.settings.wholeDevice) }
    var fastestServer by remember { mutableStateOf(engine.settings.fastestServer) }
    var killSwitch by remember { mutableStateOf(engine.settings.killSwitch) }
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
            HorizontalDivider(Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
            SwitchRow(strings.fastestServer, strings.fastestServerHelp, fastestServer) {
                fastestServer = it
                engine.settings.fastestServer = it
            }
            noPromptError?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                )
            }
        }
    }
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
    Spacer(Modifier.height(16.dp))
    Card {
        Column(Modifier.padding(16.dp)) {
            Text(strings.account, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onSignOut, shape = RoundedCornerShape(12.dp)) { Text(strings.signOut) }
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

private fun resetLabel(resetEpochSeconds: Long, strings: Strings): String {
    val secondsLeft = resetEpochSeconds - System.currentTimeMillis() / 1000
    val days = Math.ceil(secondsLeft / 86_400.0).toLong()
    return when {
        secondsLeft <= 0 -> strings.resetToday
        days == 1L -> strings.resetsInDays(1)
        else -> strings.resetsInDays(days.toInt())
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
            failure?.let { Text(it, Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error,
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
                        FilterChip(selected = theme == choice, onClick = { onThemeChange(choice) }, label = { Text(when (choice) { "Light" -> strings.light; "Dark" -> strings.dark; else -> strings.system }) })
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Column(Modifier.padding(16.dp)) {
                Text(strings.language, style = MaterialTheme.typography.bodyLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((code, label) in listOf("en" to strings.englishLanguage, "fa" to strings.persianLanguage)) {
                        FilterChip(selected = language == code, onClick = { onLanguageChange(code) }, label = { Text(label) })
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
            FilterChip(selected = filter == choice, onClick = { filter = choice }, label = { Text(when (choice) { LogFilter.All -> strings.allLogs; LogFilter.Info -> strings.infoLogs; LogFilter.Problems -> strings.problemLogs }) })
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { clipboard.setText(AnnotatedString(lines.joinToString("\n"))) }, enabled = lines.isNotEmpty()) { Text(strings.copy) }
        OutlinedButton(onClick = { AppLogger.clear() }, enabled = entries.isNotEmpty()) { Text(strings.clear) }
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

private fun localizedCountry(code: String, fallback: String, strings: Strings): String =
    if (code.length == 2) Locale("", code).getDisplayCountry(Locale(strings.languageCode)).ifBlank { fallback }
    else fallback.ifBlank { code }

@Composable
private fun AdvancedSettings(engine: EmberEngine) {
    val strings = LocalStrings.current
    var mode by remember { mutableStateOf(engine.settings.tlsSplitMode) }
    var mtuText by remember { mutableStateOf(engine.settings.mtu.toString()) }
    var expanded by remember { mutableStateOf(false) }
    val validMtu = parseMtu(mtuText) != null
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth().pointerHoverIcon(PointerIcon.Hand)
                .clickable(role = Role.Button) { expanded = !expanded }.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(strings.advanced, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                EmberIcon(if (expanded) "chevron-up" else "chevron-down", 20.dp, MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (expanded) {
                Text(strings.tlsFragmentation, style = MaterialTheme.typography.bodyLarge)
                Text(strings.tlsHelp, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Column(Modifier.selectableGroup()) {
                    for (choice in TlsSplitMode.entries) {
                        Row(
                            Modifier.fillMaxWidth().selectable(selected = mode == choice, role = Role.RadioButton, onClick = {
                                mode = choice
                                engine.settings.tlsSplitMode = choice
                            }).padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = mode == choice, onClick = null)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(strings.tlsLabel(choice), style = MaterialTheme.typography.bodyLarge)
                                Text(strings.tlsDescription(choice), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
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
