package com.soheil.ember.ui

import android.content.Intent
import android.provider.Settings
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
import androidx.compose.material3.Card
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
            title = { Text("Add an app") },
            text = {
                Column {
                    OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("Search") }, singleLine = true, modifier = Modifier.fillMaxWidth())
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
            dismissButton = { TextButton(onClick = { picking = false }) { Text("Cancel") } },
        )
    }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)

        Card(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Switch servers automatically", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "If the connected server stops passing traffic for about a minute, Ember connects to the next-fastest one.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(checked = failover, onCheckedChange = { engine.setAutoFailover(it) })
            }
        }

        Text("Apps", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Card(Modifier.fillMaxWidth()) {
            Column {
                val options = listOf(
                    Triple(AppMode.OFF, "All apps use the VPN", null),
                    Triple(AppMode.SKIP, "VPN off for the apps below", "Those apps go straight to the internet."),
                    Triple(AppMode.ONLY, "VPN only for the apps below", "Everything else goes straight to the internet."),
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
        Text("Changes apply the next time you connect.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Apps in the list", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FilledIconButton(onClick = { picking = true }) { Icon(Icons.Filled.Add, contentDescription = "Add an app") }
        }
        Card(Modifier.fillMaxWidth()) {
            if (packages.isEmpty()) {
                Text("No apps yet. Add Firefox, for example.", Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Column {
                    for (packageName in packages.sortedBy { label(it).lowercase() }) {
                        Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(label(packageName), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                            IconButton(onClick = { engine.setAppPackages(packages - packageName) }) { Icon(Icons.Filled.Delete, contentDescription = "Remove") }
                        }
                    }
                }
            }
        }

        Card(
            Modifier.fillMaxWidth().clickable {
                runCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            },
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("Keep running in the background", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "If the connection drops when the screen is locked, set Ember to \"Unrestricted\" or \"Don't optimise\" here.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
