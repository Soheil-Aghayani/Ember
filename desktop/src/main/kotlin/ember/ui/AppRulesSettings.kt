package ember.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import ember.engine.AppRuleMode
import ember.engine.EmberEngine
import java.io.File

/** Choose programs that skip the VPN (or the only ones that use it). Needs whole-device mode. */
@Composable
internal fun AppRulesSettings(engine: EmberEngine) {
    val strings = LocalStrings.current
    var mode by remember { mutableStateOf(engine.settings.appRuleMode) }
    var apps by remember { mutableStateOf(engine.settings.appRuleApps) }
    var menu by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf<List<String>>(emptyList()) }
    val enabled = engine.settings.wholeDevice && engine.tunnel.isAvailable

    fun addApp(name: String) {
        apps = (apps + name).distinctBy { it.lowercase() }
        engine.settings.appRuleApps = apps
    }

    Column(Modifier.alpha(if (enabled) 1f else 0.5f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card {
            Column(Modifier.selectableGroup()) {
                val options = listOf(
                    Triple(AppRuleMode.OFF, strings.appRulesOff, null),
                    Triple(AppRuleMode.SKIP, strings.appRulesSkip, strings.appRulesSkipHelp),
                    Triple(AppRuleMode.ONLY, strings.appRulesOnly, strings.appRulesOnlyHelp),
                )
                options.forEachIndexed { index, (value, title, help) ->
                    if (index > 0) RowDivider()
                    Row(
                        Modifier.fillMaxWidth()
                            .selectable(selected = mode == value, enabled = enabled, role = Role.RadioButton) {
                                mode = value
                                engine.settings.appRuleMode = value
                            }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = mode == value, onClick = null, enabled = enabled)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(title, style = MaterialTheme.typography.bodyLarge)
                            help?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                }
            }
        }
        Text(
            if (enabled) strings.appRulesNote else strings.appRulesNeedsWhole,
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(strings.appsList, Modifier.weight(1f).padding(start = 4.dp), style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Box {
                IconAction("add", strings.addApp, onClick = {
                    running = runningPrograms()
                    menu = true
                }, enabled = enabled, filled = true)
                DropdownMenu(
                    expanded = menu, onDismissRequest = { menu = false },
                    modifier = Modifier.heightIn(max = 360.dp),
                    shape = RoundedCornerShape(EmberDesktopMetrics.countryPopoverRadius),
                    containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp, shadowElevation = 6.dp,
                ) {
                    DropdownMenuItem(
                        text = { Text(strings.browseForApp) },
                        leadingIcon = { EmberIcon("import-file", 20.dp, MaterialTheme.colorScheme.onSurfaceVariant) },
                        onClick = {
                            menu = false
                            chooseProgram(strings.chooseAppTitle)?.let { addApp(it) }
                        },
                    )
                    RowDivider()
                    Text(strings.addRunningApp, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val offered = running.filter { name -> apps.none { it.equals(name, ignoreCase = true) } }
                    if (offered.isEmpty()) {
                        Text(strings.noRunningApps, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    for (name in offered) {
                        DropdownMenuItem(text = { Text(name) }, onClick = { menu = false; addApp(name) })
                    }
                }
            }
        }
        Card {
            if (apps.isEmpty()) {
                Text(strings.noApps, Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Column {
                    apps.forEachIndexed { index, name ->
                        if (index > 0) RowDivider()
                        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                            IconAction("trash", strings.removeApp, onClick = {
                                apps = apps - name
                                engine.settings.appRuleApps = apps
                            })
                        }
                    }
                }
            }
        }
    }
}

/** File names of the programs running for this user, outside the Windows folder (the ones a person would pick). */
private fun runningPrograms(): List<String> = runCatching {
    val windows = System.getenv("SystemRoot")?.lowercase().orEmpty()
    val me = System.getProperty("user.name")
    ProcessHandle.allProcesses().use { stream ->
        stream.map { it.info() }
            .filter { info -> info.user().map { it.substringAfterLast('\\').equals(me, ignoreCase = true) }.orElse(false) }
            .map { it.command().orElse("") }
            .filter { it.isNotEmpty() && (windows.isEmpty() || !it.lowercase().startsWith(windows)) }
            .map { File(it).name }
            .filter { it.endsWith(".exe", ignoreCase = true) && !it.equals("Ember.exe", true) && !it.equals("xray.exe", true) && !it.equals("sing-box.exe", true) }
            .toList()
    }.distinctBy { it.lowercase() }.sortedBy { it.lowercase() }
}.getOrDefault(emptyList())

/** The system file dialog for a program; only its file name is kept. */
private fun chooseProgram(title: String): String? {
    val dialog = java.awt.FileDialog(null as java.awt.Frame?, title, java.awt.FileDialog.LOAD)
    dialog.file = "*.exe"
    dialog.isVisible = true
    return dialog.file?.takeIf { it.endsWith(".exe", ignoreCase = true) }
}
