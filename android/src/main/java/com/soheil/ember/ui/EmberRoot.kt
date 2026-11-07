package com.soheil.ember.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.soheil.ember.ui.components.GlassBackground
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import com.soheil.ember.data.model.ConnectionState
import com.soheil.ember.engine.VpnState
import com.soheil.ember.R
import com.soheil.ember.engine.AndroidEngine
import kotlinx.coroutines.launch

/** Home, Servers and About, with a bar at the bottom. [onConnect] asks Android for the VPN permission first. */
@Composable
fun EmberRoot(engine: AndroidEngine, onConnect: () -> Unit, onDisconnect: () -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(if (engine.selectedNode() == null) 1 else 0) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val say: (String) -> Unit = { text -> scope.launch { snackbar.showSnackbar(text) } }
    val notice by engine.notice.collectAsState()
    LaunchedEffect(notice) {
        notice?.let {
            say(it)
            engine.clearNotice()
        }
    }
    val context = LocalContext.current
    val s = LocalStrings.current
    val phase by VpnState.phase.collectAsState()
    LaunchedEffect(phase) {
        // the first time the VPN comes up, offer the tile: one tap from the quick settings next time
        if (phase == ConnectionState.CONNECTED && !engine.tileOffered) {
            engine.tileOffered = true
            requestQuickSettingsTile(context)
        }
    }
    val items = listOf(
        Triple(s.home, R.drawable.ic_solar_power, 0),
        Triple(s.servers, R.drawable.ic_solar_global, 1),
        Triple(s.settings, R.drawable.ic_solar_settings, 2),
        Triple(s.about, R.drawable.ic_solar_info_circle, 3),
    )
    GlassBackground {
        Scaffold(
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onBackground,
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                NavigationBar(containerColor = if (MaterialTheme.colorScheme.background.luminance() < 0.5f) Color(0xFF110D18).copy(alpha = 0.78f) else Color.White.copy(alpha = 0.72f)) {
                    for ((label, icon, index) in items) {
                        NavigationBarItem(
                            selected = tab == index, onClick = { tab = index },
                            icon = { Icon(painterResource(icon), contentDescription = label) },
                            label = { Text(label) },
                        )
                    }
                }
            },
        ) { padding ->
            val modifier = Modifier.padding(padding)
            when (tab) {
                0 -> HomeScreen(engine, onConnect, onDisconnect, onOpenServers = { tab = 1 }, modifier = modifier)
                1 -> ServersScreen(engine, say, onConnect, modifier = modifier)
                2 -> SettingsScreen(engine, modifier = modifier)
                else -> AboutScreen(engine, modifier = modifier)
            }
        }
    }
}
