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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.soheil.ember.data.FxaAuthRepository
import com.soheil.ember.data.RECOMMENDED_COUNTRY_CODE
import com.soheil.ember.data.ServerListClient
import com.soheil.ember.data.SessionStatus
import com.soheil.ember.data.model.ConnectionState
import com.soheil.ember.data.model.ProxyCandidate
import com.soheil.ember.data.model.VpnCountry
import com.soheil.ember.data.model.emojiFlag
import ember.engine.EmberEngine
import ember.engine.HTTP_PORT
import ember.engine.LOCAL_BIND
import ember.engine.SOCKS_PORT
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

private enum class Page(val label: String) { Home("Home"), Locations("Locations"), Settings("Settings") }

private enum class Session { Checking, SignedOut, SignedIn }

private val CardShape = RoundedCornerShape(16.dp)

@Composable
fun EmberApp(engine: EmberEngine) {
    var session by remember { mutableStateOf(Session.Checking) }

    LaunchedEffect(Unit) {
        val status = withContext(Dispatchers.IO) { runCatching { engine.auth.restoreSession() }.getOrNull() }
        session = if (status == SessionStatus.NEEDS_LOGIN || status == null) Session.SignedOut else Session.SignedIn
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        when (session) {
            Session.Checking -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
            }
            Session.SignedOut -> LoginPage(engine.auth) { session = Session.SignedIn }
            Session.SignedIn -> MainShell(engine) {
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
private fun MainShell(engine: EmberEngine, onSignOut: () -> Unit) {
    var page by remember { mutableStateOf(Page.Home) }
    var selected by remember { mutableStateOf<ProxyCandidate?>(null) }

    Row(Modifier.fillMaxSize()) {
        Sidebar(page) { page = it }
        Box(Modifier.width(1.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant))
        Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 640.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(32.dp),
            ) {
                when (page) {
                    Page.Home -> HomePage(engine, selected) { page = Page.Locations }
                    Page.Locations -> LocationsPage(engine.servers, selected) {
                        selected = it
                        page = Page.Home
                    }
                    Page.Settings -> SettingsPage(engine, onSignOut)
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
                Text(
                    p.label,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun HomePage(engine: EmberEngine, selected: ProxyCandidate?, onOpenLocations: () -> Unit) {
    val state by engine.state.collectAsState()
    val error by engine.lastError.collectAsState()
    val connectedAt by engine.connectedAtMs.collectAsState()
    val blocked by engine.blockedNotice.collectAsState()
    val whole by engine.wholeDevice.collectAsState()
    val wholeSetting = engine.settings.wholeDevice
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

    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(16.dp))
        Box(
            Modifier.size(180.dp).clip(CircleShape).background(ring.copy(alpha = 0.15f)).clickable {
                if (state == ConnectionState.DISCONNECTED) engine.connect(selected) else engine.disconnect()
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
            if (state == ConnectionState.CONNECTED) formatDuration(elapsed) else "00:00:00",
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
        Card(Modifier.clickable(onClick = onOpenLocations)) {
            Column(Modifier.padding(16.dp)) {
                Text("Location", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                Text(
                    selected?.let { "${it.emojiFlag} ${it.countryName.ifBlank { it.countryCode }}" } ?: "Recommended",
                    style = MaterialTheme.typography.bodyLarge,
                )
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
                    "Point an app or browser at this proxy to use it. Whole-device mode (all apps, with a kill switch) is not available yet, so this does not protect other traffic.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun LocationsPage(servers: ServerListClient, selected: ProxyCandidate?, onPick: (ProxyCandidate?) -> Unit) {
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
            LocationRow("Recommended", "Automatically chosen for the best performance", selected == null) { onPick(null) }
            for (country in ordered) {
                HorizontalDivider(Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                val open = country.code in expanded
                LocationRow(
                    "${country.emojiFlag} ${country.name}",
                    "${country.cities.size} cities",
                    selected?.countryCode == country.code && selected.cityCode.isBlank(),
                    onToggle = { expanded = if (open) expanded - country.code else expanded + country.code },
                ) {
                    onPick(ServerListClient.candidatesForCountry(countries, country.code).randomOrNull())
                }
                if (open) {
                    for (city in country.cities) {
                        HorizontalDivider(Modifier.padding(start = 32.dp), color = MaterialTheme.colorScheme.outlineVariant)
                        LocationRow(
                            city.name.ifBlank { city.code },
                            "${city.servers.size} servers",
                            selected?.countryCode == country.code && selected.cityCode == city.code,
                            indent = 16,
                        ) {
                            onPick(ServerListClient.candidatesForCity(countries, country.code, city.code).randomOrNull())
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
    onToggle: (() -> Unit)? = null,
    onPick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onPick).padding(start = (16 + indent).dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (isSelected) Text("Selected", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        if (onToggle != null) {
            Spacer(Modifier.width(12.dp))
            Text(
                "Cities",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onToggle).padding(8.dp),
            )
        }
    }
}

@Composable
private fun SettingsPage(engine: EmberEngine, onSignOut: () -> Unit) {
    var whole by remember { mutableStateOf(engine.settings.wholeDevice) }
    var killSwitch by remember { mutableStateOf(engine.settings.killSwitch) }
    val available = engine.tunnel.isAvailable

    Text("Settings", style = MaterialTheme.typography.headlineLarge)
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

private fun formatDuration(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
}
