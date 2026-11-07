package com.soheil.ember.ui

import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import com.soheil.ember.ui.components.Card
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.soheil.ember.engine.AndroidEngine
import com.soheil.ember.vpn.AppMode

/** One app the user can pick: its package name and the name people know it by. */
private class InstalledApp(val packageName: String, val label: String)

/** Failover, which apps use the VPN, and keeping it alive in the background. */
@Composable
fun SettingsScreen(engine: AndroidEngine, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val s = LocalStrings.current
    val language by engine.language.collectAsState()
    val failover by engine.autoFailover.collectAsState()
    val mode by engine.appMode.collectAsState()
    val packages by engine.appPackages.collectAsState()
    var picking by remember { mutableStateOf(false) }
    val installed = remember {
        val pm = context.packageManager
        pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { InstalledApp(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
            .filter { it.packageName != context.packageName }
            .distinctBy { it.packageName }.sortedBy { it.label.lowercase() }
    }
    fun label(packageName: String) = installed.firstOrNull { it.packageName == packageName }?.label ?: packageName

    if (picking) {
        var query by remember { mutableStateOf("") }
        val choices = installed.filter { it.packageName !in packages && it.label.contains(query, ignoreCase = true) }
        AlertDialog(
            onDismissRequest = { picking = false },
            title = { Text(s.addApp) },
            text = {
                Column {
                    OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text(s.search) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        items(choices, key = { it.packageName }) { app ->
                            Text(
                                app.label, Modifier.fillMaxWidth().clickable {
                                    engine.setAppPackages(packages + app.packageName)
                                    picking = false
                                }.padding(vertical = 12.dp),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { picking = false }) { Text(s.cancel) } },
        )
    }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(s.settings, style = MaterialTheme.typography.headlineMedium)

        Text(s.languageTitle, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Card(Modifier.fillMaxWidth()) {
            Column {
                for ((code, name) in listOf("system" to s.languageSystem, "en" to "English", "fa" to "فارسی")) {
                    Row(
                        Modifier.fillMaxWidth().selectable(selected = language == code, role = Role.RadioButton) { engine.setLanguage(code) }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = language == code, onClick = null)
                        Spacer(Modifier.width(12.dp))
                        Text(name, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(s.autoSwitch, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        s.autoSwitchHelp,
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(checked = failover, onCheckedChange = { engine.setAutoFailover(it) })
            }
        }

        Text(s.appsHeader, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Card(Modifier.fillMaxWidth()) {
            Column {
                val options = listOf(
                    Triple(AppMode.OFF, s.appsAll, null),
                    Triple(AppMode.SKIP, s.appsSkip, s.appsSkipHelp),
                    Triple(AppMode.ONLY, s.appsOnly, s.appsOnlyHelp),
                )
                for ((value, title, help) in options) {
                    Row(
                        Modifier.fillMaxWidth().selectable(selected = mode == value, role = Role.RadioButton) { engine.setAppMode(value) }.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = mode == value, onClick = null)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(title, style = MaterialTheme.typography.bodyLarge)
                            help?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                }
            }
        }
        Text(s.appsApply, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(s.appsInList, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FilledIconButton(onClick = { picking = true }) { Icon(Icons.Filled.Add, contentDescription = s.addApp) }
        }
        Card(Modifier.fillMaxWidth()) {
            if (packages.isEmpty()) {
                Text(s.noApps, Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Column {
                    for (packageName in packages.sortedBy { label(it).lowercase() }) {
                        Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(label(packageName), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                            IconButton(onClick = { engine.setAppPackages(packages - packageName) }) { Icon(Icons.Filled.Delete, contentDescription = s.remove) }
                        }
                    }
                }
            }
        }

        Card(
            Modifier.fillMaxWidth().clickable {
                if (!requestQuickSettingsTile(context)) {
                    Toast.makeText(context, s.addTileManual, Toast.LENGTH_LONG).show()
                }
            },
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(s.addTileTitle, style = MaterialTheme.typography.bodyLarge)
                Text(
                    s.addTileHelp,
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Card(
            Modifier.fillMaxWidth().clickable {
                if (!requestHomeScreenWidget(context)) Toast.makeText(context, s.addWidgetManual, Toast.LENGTH_LONG).show()
            },
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(s.addWidgetTitle, style = MaterialTheme.typography.bodyLarge)
                Text(s.addWidgetHelp, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Card(
            Modifier.fillMaxWidth().clickable {
                runCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            },
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(s.keepBgTitle, style = MaterialTheme.typography.bodyLarge)
                Text(
                    s.keepBgHelp,
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
