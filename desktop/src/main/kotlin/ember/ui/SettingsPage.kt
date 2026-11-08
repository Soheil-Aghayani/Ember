package ember.ui

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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.soheil.ember.vpn.tun.HevSocks5TunnelConfig
import ember.engine.Autostart
import ember.engine.EmberEngine
import ember.engine.MTU_RANGE
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class Section { Startup, Protection, Apps, Skip, Trusted, Backup, Advanced }

/** Settings as a short list of groups; each row opens its own page (or a menu, for language and theme). */
@Composable
internal fun SettingsPage(
    engine: EmberEngine,
    theme: String,
    onThemeChange: (String) -> Unit,
    language: String,
    onLanguageChange: (String) -> Unit,
) {
    val strings = LocalStrings.current
    var section by remember { mutableStateOf<Section?>(null) }
    val available = engine.tunnel.isAvailable

    section?.let { open ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconAction("chevron-left", strings.back, onClick = { section = null })
            Spacer(Modifier.width(8.dp))
            Text(
                when (open) {
                    Section.Startup -> strings.startupAndTray
                    Section.Protection -> strings.protection
                    Section.Apps -> strings.appRules
                    Section.Skip -> strings.skipVpn
                    Section.Trusted -> strings.trustedNetworksTitle
                    Section.Backup -> strings.backupTitle
                    Section.Advanced -> strings.advanced
                },
                style = MaterialTheme.typography.headlineMedium,
            )
        }
        Spacer(Modifier.height(16.dp))
        when (open) {
            Section.Startup -> StartupSettings(engine)
            Section.Protection -> ProtectionSettings(engine)
            Section.Apps -> AppRulesSettings(engine)
            Section.Skip -> SkipVpnSettings(engine, engine.settings.wholeDevice && available)
            Section.Trusted -> TrustedNetworksSettings(engine)
            Section.Backup -> BackupSettings(engine::shutdown)
            Section.Advanced -> AdvancedSettings(engine)
        }
        return
    }

    Text(strings.settings, style = MaterialTheme.typography.headlineLarge)
    Spacer(Modifier.height(8.dp))

    GroupLabel(strings.settingsInterface)
    Card {
        Column {
            ChoiceRow(
                strings.language,
                listOf("en" to strings.englishLanguage, "fa" to strings.persianLanguage),
                language, onLanguageChange,
            )
            RowDivider()
            ChoiceRow(
                strings.appearance,
                listOf("Light" to strings.light, "Dark" to strings.dark, "System" to strings.system),
                theme, onThemeChange,
            )
            RowDivider()
            NavRow(strings.startupAndTray, null) { section = Section.Startup }
        }
    }

    GroupLabel(strings.settingsConnection)
    Card {
        Column {
            NavRow(
                strings.protection,
                if (engine.settings.wholeDevice && available) strings.wholeDeviceShort else strings.proxyOnlyShort,
            ) { section = Section.Protection }
            RowDivider()
            NavRow(strings.appRules, strings.appRulesShort(engine.settings.appRuleMode)) { section = Section.Apps }
            RowDivider()
            NavRow(strings.skipVpn, strings.entriesCount(engine.settings.excludedAddresses.size)) { section = Section.Skip }
            RowDivider()
            NavRow(strings.trustedNetworksTitle, strings.entriesCount(engine.settings.trustedNetworks.size)) { section = Section.Trusted }
        }
    }

    GroupLabel(strings.settingsMore)
    Card {
        Column {
            NavRow(strings.backupTitle, null) { section = Section.Backup }
            RowDivider()
            NavRow(strings.advanced, null) { section = Section.Advanced }
        }
    }
}

@Composable
private fun GroupLabel(text: String) {
    Text(
        text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 20.dp, bottom = 8.dp),
    )
}

@Composable
internal fun RowDivider() = HorizontalDivider(Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)

/** A row that opens another page; [value] is what is set now. */
@Composable
private fun NavRow(title: String, value: String?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().pointerHoverIcon(PointerIcon.Hand).clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        value?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
        }
        EmberIcon("chevron-right", 16.dp, MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A row that shows the current choice and opens a menu of the others. [options] pairs a stored value with its label. */
@Composable
private fun ChoiceRow(title: String, options: List<Pair<String, String>>, selected: String, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.fillMaxWidth().pointerHoverIcon(PointerIcon.Hand).clickable(role = Role.Button) { open = true }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Text(
                options.firstOrNull { it.first == selected }?.second ?: selected,
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(8.dp))
            EmberIcon("chevron-down", 16.dp, MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(
            expanded = open, onDismissRequest = { open = false },
            shape = RoundedCornerShape(EmberDesktopMetrics.countryPopoverRadius),
            containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp, shadowElevation = 6.dp,
        ) {
            for ((value, label) in options) {
                DropdownMenuItem(
                    text = { Text(label) },
                    trailingIcon = { if (value == selected) EmberIcon("check", 20.dp, MaterialTheme.colorScheme.primary) },
                    onClick = { open = false; onPick(value) },
                )
            }
        }
    }
}

@Composable
private fun StartupSettings(engine: EmberEngine) {
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
            SwitchRow(strings.keepInTray, strings.trayHelp, keepInTray, enabled = androidx.compose.ui.window.isTraySupported) {
                keepInTray = it
                engine.settings.keepInTray = it
            }
            RowDivider()
            SwitchRow(strings.startWithWindows, strings.startQuietly, autostart, enabled = Autostart.supported && !busy) { enabled ->
                busy = true
                failure = null
                scope.launch {
                    val saved = withContext(Dispatchers.IO) { Autostart.setEnabled(enabled) }
                    if (saved) autostart = enabled else failure = strings.startupFailed
                    busy = false
                }
            }
            failure?.let {
                Text(it, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall)
            }
            RowDivider()
            SwitchRow(strings.autoConnect, strings.autoConnectHelp, autoConnect) {
                autoConnect = it
                engine.settings.autoConnect = it
            }
        }
    }
}

@Composable
private fun ProtectionSettings(engine: EmberEngine) {
    val strings = LocalStrings.current
    var whole by remember { mutableStateOf(engine.settings.wholeDevice) }
    var killSwitch by remember { mutableStateOf(engine.settings.killSwitch) }
    var ipv6 by remember { mutableStateOf(engine.settings.ipv6) }
    var failover by remember { mutableStateOf(engine.settings.autoFailover) }
    val available = engine.tunnel.isAvailable
    val scope = rememberCoroutineScope()
    var noPrompt by remember { mutableStateOf(false) }
    var noPromptBusy by remember { mutableStateOf(true) }
    var noPromptError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        noPrompt = withContext(Dispatchers.IO) { available && engine.tunnel.privileged.isInstalled() }
        noPromptBusy = false
    }
    Card {
        Column {
            SwitchRow(
                strings.wholeDevice,
                if (available) strings.wholeDeviceHelp else strings.wholeDeviceUnavailable,
                whole && available,
                enabled = available,
            ) {
                whole = it
                engine.settings.wholeDevice = it
            }
            RowDivider()
            SwitchRow(strings.killSwitch, strings.killSwitchHelp, killSwitch, enabled = available) {
                killSwitch = it
                engine.settings.killSwitch = it
            }
            RowDivider()
            SwitchRow(strings.ipv6, strings.ipv6Help, ipv6, enabled = available) {
                ipv6 = it
                engine.settings.ipv6 = it
            }
            RowDivider()
            SwitchRow(strings.autoFailover, strings.autoFailoverHelp, failover) {
                failover = it
                engine.settings.autoFailover = it
            }
            RowDivider()
            SwitchRow(strings.noPrompts, strings.noPromptsHelp, noPrompt, enabled = available && !noPromptBusy) { wanted ->
                noPromptBusy = true
                noPromptError = null
                scope.launch {
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
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp))
            }
        }
    }
}

@Composable
private fun AdvancedSettings(engine: EmberEngine) {
    val strings = LocalStrings.current
    var mtuText by remember { mutableStateOf(engine.settings.mtu.toString()) }
    var diagnostic by remember { mutableStateOf(engine.settings.diagnosticLog) }
    val validMtu = parseMtu(mtuText) != null
    Card {
        Column(Modifier.padding(bottom = 16.dp)) {
            SwitchRow(strings.diagnosticLog, strings.diagnosticLogHelp, diagnostic) {
                diagnostic = it
                engine.settings.diagnosticLog = it
            }
            RowDivider()
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
            if (engine.tunnel.isAvailable) {
                RowDivider()
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedButton(onClick = { engine.restoreInternet() }, shape = RoundedCornerShape(12.dp)) { Text(strings.repairNetwork) }
                    Text(strings.repairNetworkHint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

private fun parseMtu(value: String): Int? =
    value.takeIf { it.isNotEmpty() && it.all { char -> char in '0'..'9' } }
        ?.toIntOrNull()?.takeIf { it in MTU_RANGE }
