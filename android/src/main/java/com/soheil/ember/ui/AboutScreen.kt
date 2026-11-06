package com.soheil.ember.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import com.soheil.ember.BuildConfig
import com.soheil.ember.data.AppLogger
import com.soheil.ember.engine.AndroidEngine
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Version, components and how to reach the developer. */
@Composable
fun AboutScreen(engine: AndroidEngine, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var showLogs by remember { mutableStateOf(false) }
    if (showLogs) {
        val entries by AppLogger.entries.collectAsState()
        val text = remember(entries) {
            val format = SimpleDateFormat("HH:mm:ss", Locale.US)
            entries.takeLast(80).joinToString("\n") { "${format.format(Date(it.timestampMillis))} ${it.level} ${it.tag}: ${it.message}" } +
                "\n\nXray: " + engine.xrayOutput()
        }
        AlertDialog(
            onDismissRequest = { showLogs = false },
            title = { Text("Logs") },
            text = { Text(text, Modifier.verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall) },
            confirmButton = { TextButton(onClick = { clipboard.setText(AnnotatedString(text)) }) { Text("Copy") } },
            dismissButton = { TextButton(onClick = { showLogs = false }) { Text("Close") } },
        )
    }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("About", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Row("Version", BuildConfig.VERSION_NAME)
                Row("Engine", "Xray-core 26.10.31")
                Row("Tunnel", "hev-socks5-tunnel")
            }
        }
        Spacer(Modifier.height(12.dp))
        Card(
            Modifier.fillMaxWidth().clickable {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/env_soh"))) }
            },
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("Contact", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Telegram  @env_soh", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
            }
        }
        Spacer(Modifier.height(12.dp))
        Card(Modifier.fillMaxWidth().clickable { showLogs = true }) {
            Text("Logs", Modifier.padding(16.dp), style = MaterialTheme.typography.bodyLarge)
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "Ember opens your own subscription links, server files and QR codes. Open-source parts: Xray-core (MPL-2.0), hev-socks5-tunnel (MIT), Inter (OFL), Solar icons (CC BY 4.0), circle-flags (MIT).",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Row(label: String, value: String) {
    androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(label, Modifier.weight(0.4f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, Modifier.weight(0.6f), style = MaterialTheme.typography.bodyMedium)
    }
}
