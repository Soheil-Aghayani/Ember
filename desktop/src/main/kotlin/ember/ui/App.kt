package ember.ui

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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.soheil.ember.data.AppLogger
import com.soheil.ember.data.FxaAuthRepository
import com.soheil.ember.data.LogLevel
import com.soheil.ember.data.RECOMMENDED_COUNTRY_CODE
import com.soheil.ember.data.ServerListClient
import com.soheil.ember.data.SessionStatus
import com.soheil.ember.data.formatBytes
import com.soheil.ember.data.formatBytesPerSecond
import com.soheil.ember.data.model.ConnectionState
import com.soheil.ember.data.model.ProxyCandidate
import com.soheil.ember.data.model.VpnCountry
import com.soheil.ember.data.model.emojiFlag
import ember.engine.Autostart
import ember.engine.EmberEngine
import ember.engine.HTTP_PORT
import ember.engine.LOCAL_BIND
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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private enum class Page(val label: String, val icon: String) {
    Home("Home", "home"),
    Locations("Locations", "locations"),
    Settings("Settings", "settings"),
    Logs("Logs", "logs"),
}

private enum class Session { Checking, SignedOut, SignedIn }

private val CardShape = RoundedCornerShape(16.dp)

@Composable
fun EmberApp(
    engine: EmberEngine,
    theme: String,
    onThemeChange: (String) -> Unit,
    update: String?,
    onDismissUpdate: () -> Unit,
) {
    var session by remember { mutableStateOf(Session.Checking) }

    LaunchedEffect(Unit) {
        val status = withContext(Dispatchers.IO) { runCatching { engine.auth.restoreSession() }.getOrNull() }
        session = if (status == SessionStatus.NEEDS_LOGIN || status == null) Session.SignedOut else Session.SignedIn
    }

    var startupHandled by remember { mutableStateOf(false) }
    var locationReady by remember { mutableStateOf(false) }
    var restoredLocation by remember { mutableStateOf<ProxyCandidate?>(null) }
    LaunchedEffect(session) {
        if (session != Session.SignedIn || startupHandled) return@LaunchedEffect
        startupHandled = true
        val country = engine.settings.lastCountry
        if (country.isNotBlank()) {
            val countries = withContext(Dispatchers.IO) {
                try {
                    withTimeoutOrNull(10_000) { engine.servers.fetchCountries() }
                        ?: engine.servers.loadCachedCountries()
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    engine.servers.loadCachedCountries()
                }
            }
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
            } else MainShell(engine, restoredLocation, theme, onThemeChange, update, onDismissUpdate) {
                engine.disconnect()
                engine.tokenStore.clear()
                session = Session.SignedOut
            }
        }
    }
}

@Composable
private fun Card(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
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
            Text("Sign in", style = MaterialTheme.typography.headlineLarge)
            Spacer(Modifier.height(8.dp))
            Text(
                "Use your Firefox account. Ember runs on the free 50 GB of monthly VPN traffic Mozilla includes with it.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))

            if (!needsCode) {
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("Firefox account email") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
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
                                .onFailure { error = it.message ?: "Sign-in failed" }
                        }
                    },
                    enabled = !busy && email.isNotBlank() && password.isNotBlank(),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                ) { Text(if (busy) "Signing in..." else "Continue") }
            } else {
                Text("Enter the confirmation code sent to your email", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it },
                    label = { Text("Confirmation code") },
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
                                error = it.message ?: "Verification failed"
                                if (auth.pendingSessionToken == null) needsCode = false
                            }
                        }
                    },
                    enabled = !busy && code.isNotBlank(),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                ) { Text(if (busy) "Verifying..." else "Verify") }
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
    restoredLocation: ProxyCandidate?,
    theme: String,
    onThemeChange: (String) -> Unit,
    update: String?,
    onDismissUpdate: () -> Unit,
    onSignOut: () -> Unit,
) {
    var page by remember { mutableStateOf(Page.Home) }
    var selected by remember(restoredLocation) { mutableStateOf(restoredLocation) }

    Row(Modifier.fillMaxSize()) {
        Sidebar(page) { page = it }
        Box(Modifier.width(1.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant))
        Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 640.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(32.dp),
            ) {
                when (page) {
                    Page.Home -> HomePage(engine, selected, update, onDismissUpdate) { page = Page.Locations }
                    Page.Locations -> LocationsPage(engine.servers, selected, engine.settings.lastCity) { candidate, country, city ->
                        engine.settings.lastCountry = country
                        engine.settings.lastCity = city
                        selected = candidate
                        page = Page.Home
                    }
                    Page.Settings -> SettingsPage(engine, theme, onThemeChange, onSignOut)
                    Page.Logs -> LogsPage()
                }
            }
        }
    }
}

@Composable
private fun Sidebar(selected: Page, onSelect: (Page) -> Unit) {
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
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (active) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                    .clickable { onSelect(p) }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                val contentColor = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                Row(verticalAlignment = Alignment.CenterVertically) {
                    EmberIcon(p.icon, 20.dp, contentColor)
                    Spacer(Modifier.width(12.dp))
                    Text(p.label, style = MaterialTheme.typography.titleSmall, color = contentColor)
                }
            }
        }
    }
}

@Composable
private fun HomePage(
    engine: EmberEngine, selected: ProxyCandidate?, update: String?, onDismissUpdate: () -> Unit,
    onOpenLocations: () -> Unit,
) {
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
    val status = statusColors()
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

    val ring = when (state) {
        ConnectionState.CONNECTED -> status.connected
        ConnectionState.CONNECTING -> status.connecting
        ConnectionState.DISCONNECTED -> MaterialTheme.colorScheme.primary
    }

    if (showAdminNotice) {
        AlertDialog(
            onDismissRequest = { showAdminNotice = false },
            title = { Text("Allow administrator access") },
            text = {
                Text(
                    "To protect your whole device, Ember sets up a secure network adapter and routing rules, " +
                        "which needs administrator access. Windows will ask next, naming \"Windows PowerShell\" " +
                        "(that runs Ember's network helper). Choose Yes to continue.",
                )
            },
            confirmButton = {
                Button(onClick = {
                    engine.settings.adminNoticeSeen = true
                    showAdminNotice = false
                    engine.connect(selected)
                }) { Text("Continue") }
            },
            dismissButton = { TextButton(onClick = { showAdminNotice = false }) { Text("Cancel") } },
        )
    }

    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        if (update != null) {
            UpdateBanner(update, onDismissUpdate)
            Spacer(Modifier.height(12.dp))
        }
        traffic?.let { TrafficCard(it) }
        Spacer(Modifier.height(16.dp))
        Box(
            Modifier.size(180.dp).clip(CircleShape).background(ring.copy(alpha = 0.15f)).clickable {
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
                        ConnectionState.DISCONNECTED -> "Connect"
                        ConnectionState.CONNECTING -> "Cancel"
                        ConnectionState.CONNECTED -> "Disconnect"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = ring,
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(
            when (state) {
                ConnectionState.CONNECTED -> if (whole) "Connected" else "Proxy active"
                ConnectionState.CONNECTING -> "Connecting…"
                ConnectionState.DISCONNECTED -> "Disconnected"
            },
            style = MaterialTheme.typography.headlineMedium,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            when (state) {
                ConnectionState.CONNECTED -> formatDuration(elapsed)
                ConnectionState.CONNECTING -> progress ?: "Connecting..."
                ConnectionState.DISCONNECTED -> "00:00:00"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        blocked?.let { notice ->
            Spacer(Modifier.height(12.dp))
            Card {
                Column(Modifier.padding(16.dp)) {
                    Text("Internet is blocked by the kill switch", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(4.dp))
                    Text(notice, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { engine.restoreInternet() }, shape = RoundedCornerShape(12.dp)) { Text("Restore internet") }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        val connectedTarget = target
        if (state == ConnectionState.CONNECTED && connectedTarget != null) {
            Card {
                Column(Modifier.padding(16.dp)) {
                    Text("Connected to", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    // "Recommended" is not a country: show where the connection really exits once that is known.
                    val isRecommended = connectedTarget.countryCode.equals("REC", ignoreCase = true) || connectedTarget.countryCode.isBlank()
                    val exitCode = exit.info?.countryCode.orEmpty()
                    val shownCode = if (isRecommended) exitCode else connectedTarget.countryCode
                    val shownName = when {
                        !isRecommended -> connectedTarget.countryName.ifBlank { connectedTarget.countryCode }
                        exitCode.length == 2 -> java.util.Locale("", exitCode).getDisplayCountry(java.util.Locale.ENGLISH)
                        else -> "Recommended location"
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FlagIcon(shownCode, 32.dp)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(shownName, style = MaterialTheme.typography.titleMedium)
                            val detail = when {
                                isRecommended -> "Recommended server"
                                connectedTarget.cityCode.isNotBlank() -> connectedTarget.cityCode
                                else -> null
                            }
                            if (detail != null) {
                                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    val info = exit.info
                    when {
                        exit.checking -> Text("Checking your connection...", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        info != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                            if (info.countryCode.isNotBlank()) {
                                FlagIcon(info.countryCode, 16.dp)
                                Spacer(Modifier.width(6.dp))
                            }
                            Text("Exit IP ${info.ip}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        exit.error != null -> Text(
                            "Could not verify the connection: ${exit.error}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        } else {
            Card(Modifier.clickable(onClick = onOpenLocations)) {
                Column(Modifier.padding(16.dp)) {
                    Text("Location", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (selected != null) {
                            FlagIcon(selected.countryCode, 24.dp)
                            Spacer(Modifier.width(10.dp))
                        }
                        Text(
                            selected?.let { it.countryName.ifBlank { it.countryCode } } ?: "Recommended",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Card {
            Column(Modifier.padding(16.dp)) {
                Text("Monthly data", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                val q = quota
                val remaining = q?.remaining
                val max = q?.max
                if (q == null || (remaining == null && max == null)) {
                    Text("Not reported yet", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text(
                        buildString {
                            if (remaining != null) append("${formatBytes(remaining)} left")
                            if (max != null) append(if (remaining != null) " of ${formatBytes(max)}" else "Limit ${formatBytes(max)}")
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    if (remaining != null && max != null && max > 0) {
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { (remaining.toFloat() / max.toFloat()).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                            trackColor = MaterialTheme.colorScheme.surfaceVariant,
                        )
                    }
                }
                q?.resetEpochSeconds?.let { reset ->
                    Spacer(Modifier.height(8.dp))
                    Text(resetLabel(reset), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        if (!(whole || (state == ConnectionState.DISCONNECTED && wholeSetting))) Card {
            Column(Modifier.padding(16.dp)) {
                Text("Local proxy", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                Text("SOCKS5  $LOCAL_BIND:$SOCKS_PORT", style = MaterialTheme.typography.bodyMedium)
                Text("HTTP  $LOCAL_BIND:$HTTP_PORT", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Point an app or browser at this proxy to use it. Other apps are not protected in proxy mode. Turn on whole-device mode in Settings to route everything.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun LocationsPage(
    servers: ServerListClient,
    selected: ProxyCandidate?,
    selectedCity: String,
    onPick: (ProxyCandidate?, String, String) -> Unit,
) {
    var countries by remember { mutableStateOf<List<VpnCountry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var failure by remember { mutableStateOf<String?>(null) }
    var expanded by remember { mutableStateOf(setOf<String>()) }

    LaunchedEffect(Unit) {
        val cached = withContext(Dispatchers.IO) { servers.loadCachedCountries() }
        if (cached.isNotEmpty()) {
            countries = cached
            loading = false
        }
        runCatching { servers.fetchCountries() }
            .onSuccess { countries = it }
            .onFailure { if (countries.isEmpty()) failure = it.message ?: "Could not load the server list" }
        loading = false
    }

    Text("Choose location", style = MaterialTheme.typography.headlineLarge)
    Spacer(Modifier.height(16.dp))

    if (loading) {
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
            LocationRow("Recommended", "Automatically chosen for the best performance", selected == null) { onPick(null, "", "") }
            for (country in ordered) {
                HorizontalDivider(Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                val open = country.code in expanded
                LocationRow(
                    country.name,
                    plural(country.cities.size, "city", "cities"),
                    selected?.countryCode == country.code && selectedCity.isBlank(),
                    flagCode = country.code,
                    expanded = open,
                    onToggle = { expanded = if (open) expanded - country.code else expanded + country.code },
                ) {
                    onPick(ServerListClient.candidatesForCountry(countries, country.code).randomOrNull(), country.code, "")
                }
                if (open) {
                    for (city in country.cities) {
                        HorizontalDivider(Modifier.padding(start = 32.dp), color = MaterialTheme.colorScheme.outlineVariant)
                        LocationRow(
                            city.name.ifBlank { city.code },
                            plural(city.servers.size, "server", "servers"),
                            selected?.countryCode == country.code && selectedCity == city.code,
                            indent = 16,
                        ) {
                            onPick(ServerListClient.candidatesForCity(countries, country.code, city.code).randomOrNull(), country.code, city.code)
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
        if (isSelected) Text("Selected", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
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
                    if (expanded) "Hide cities" else "Show cities",
                )
            }
        }
    }
}

@Composable
private fun SettingsPage(engine: EmberEngine, theme: String, onThemeChange: (String) -> Unit, onSignOut: () -> Unit) {
    var whole by remember { mutableStateOf(engine.settings.wholeDevice) }
    var killSwitch by remember { mutableStateOf(engine.settings.killSwitch) }
    val available = engine.tunnel.isAvailable

    Text("Settings", style = MaterialTheme.typography.headlineLarge)
    Spacer(Modifier.height(16.dp))
    GeneralSettings(engine, theme, onThemeChange)
    Spacer(Modifier.height(16.dp))
    Card {
        Column {
            SwitchRow(
                "Whole-device mode (experimental)",
                if (available) "Route all apps through Ember. Asks for administrator permission each time you connect."
                else "Not available in this build. It needs the Windows tunnel components.",
                whole && available,
                enabled = available,
            ) {
                whole = it
                engine.settings.wholeDevice = it
            }
            HorizontalDivider(Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
            SwitchRow(
                "Kill switch",
                "If the tunnel drops, block internet access instead of leaking your real traffic. Applies to whole-device mode.",
                killSwitch,
                enabled = available,
            ) {
                killSwitch = it
                engine.settings.killSwitch = it
            }
        }
    }
    Spacer(Modifier.height(16.dp))
    Card {
        Column(Modifier.padding(16.dp)) {
            Text("About", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Text("Ember ${System.getProperty("jpackage.app-version") ?: "development build"}", style = MaterialTheme.typography.bodyMedium)
            Text(
                "Log file: ${ember.engine.appDataDir().resolve("ember.log")}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    Spacer(Modifier.height(16.dp))
    Card {
        Column(Modifier.padding(16.dp)) {
            Text("Account", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onSignOut, shape = RoundedCornerShape(12.dp)) { Text("Sign out") }
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

private fun plural(n: Int, one: String, many: String) = if (n == 1) "1 $one" else "$n $many"

private fun resetLabel(resetEpochSeconds: Long): String {
    val secondsLeft = resetEpochSeconds - System.currentTimeMillis() / 1000
    val days = Math.ceil(secondsLeft / 86_400.0).toLong()
    return when {
        secondsLeft <= 0 -> "Data allowance resets today"
        days == 1L -> "Resets in 1 day"
        else -> "Resets in $days days"
    }
}

private fun formatDuration(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
}

@Composable
private fun GeneralSettings(engine: EmberEngine, theme: String, onThemeChange: (String) -> Unit) {
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
            Text("General", Modifier.padding(16.dp), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            SwitchRow("Keep running in the tray when the window is closed",
                "Open Ember again from the icon near the Windows clock.", keepInTray,
                enabled = androidx.compose.ui.window.isTraySupported) {
                keepInTray = it
                engine.settings.keepInTray = it
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SwitchRow("Start Ember when I sign in to Windows", "Start quietly in the tray.", autostart,
                enabled = Autostart.supported && !busy) { enabled ->
                busy = true
                failure = null
                scope.launch {
                    val saved = withContext(Dispatchers.IO) { Autostart.setEnabled(enabled) }
                    if (saved) autostart = enabled
                    else failure = "Could not change Windows startup. Try again from the installed Ember app."
                    busy = false
                }
            }
            failure?.let { Text(it, Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall) }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SwitchRow("Connect automatically when Ember starts", "Use your last chosen location after signing in.", autoConnect) {
                autoConnect = it
                engine.settings.autoConnect = it
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Column(Modifier.padding(16.dp)) {
                Text("Appearance", style = MaterialTheme.typography.bodyLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (choice in listOf("Light", "Dark", "System")) {
                        FilterChip(selected = theme == choice, onClick = { onThemeChange(choice) }, label = { Text(choice) })
                    }
                }
            }
        }
    }
}

@Composable
private fun TrafficCard(traffic: TrafficStats) {
    val reading by traffic.readings.collectAsState()
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Live traffic", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (reading.available) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text("Download", style = MaterialTheme.typography.bodySmall)
                        Text(formatBytesPerSecond(reading.downloadSpeed), style = MaterialTheme.typography.titleMedium)
                    }
                    Column {
                        Text("Upload", style = MaterialTheme.typography.bodySmall)
                        Text(formatBytesPerSecond(reading.uploadSpeed), style = MaterialTheme.typography.titleMedium)
                    }
                }
                Text("This session: ${formatBytes(reading.downloaded)} downloaded, ${formatBytes(reading.uploaded)} uploaded",
                    style = MaterialTheme.typography.bodySmall)
            } else Text("Waiting for traffic readings...", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun UpdateBanner(version: String, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    Card {
        Column(Modifier.padding(16.dp)) {
            Text("Ember $version is available", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = {
                    scope.launch(Dispatchers.IO) {
                        runCatching {
                            if (Desktop.isDesktopSupported()) Desktop.getDesktop().browse(URI(UpdateCheck.RELEASES_URL))
                        }
                    }
                }) { Text("View update") }
                TextButton(onClick = onDismiss) { Text("Dismiss") }
            }
        }
    }
}

private enum class LogFilter { All, Info, Problems }

@Composable
private fun LogsPage() {
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
    Text("Logs", style = MaterialTheme.typography.headlineLarge)
    Spacer(Modifier.height(16.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        for (choice in LogFilter.entries) {
            FilterChip(selected = filter == choice, onClick = { filter = choice }, label = { Text(choice.name) })
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { clipboard.setText(AnnotatedString(lines.joinToString("\n"))) }, enabled = lines.isNotEmpty()) { Text("Copy") }
        OutlinedButton(onClick = { AppLogger.clear() }, enabled = entries.isNotEmpty()) { Text("Clear") }
    }
    Spacer(Modifier.height(12.dp))
    Card {
        SelectionContainer {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (lines.isEmpty()) Text("No log entries to show.", style = MaterialTheme.typography.bodySmall)
                for (line in lines) Text(line, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
        }
    }
}
