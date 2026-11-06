package com.soheil.ember.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
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
    val items = listOf(
        Triple("Home", R.drawable.ic_solar_power, 0),
        Triple("Servers", R.drawable.ic_solar_global, 1),
        Triple("About", R.drawable.ic_solar_info_circle, 2),
    )
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar {
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
            1 -> ServersScreen(engine, say, modifier = modifier)
            else -> AboutScreen(modifier = modifier)
        }
    }
}
