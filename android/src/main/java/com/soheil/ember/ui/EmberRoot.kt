package com.soheil.ember.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import com.soheil.ember.ui.components.GlassBackground
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import com.soheil.ember.data.model.ConnectionState
import com.soheil.ember.engine.VpnState
import com.soheil.ember.R
import com.soheil.ember.ui.components.tvFocusOutline
import com.soheil.ember.ui.components.tvDpadActivate
import com.soheil.ember.engine.AndroidEngine
import kotlinx.coroutines.launch

/** Home, Servers and About, with a bar at the bottom. [onConnect] asks Android for the VPN permission first. */
@Composable
fun EmberRoot(engine: AndroidEngine, onConnect: () -> Unit, onDisconnect: () -> Unit, isTv: Boolean = false) {
    var tab by rememberSaveable { mutableIntStateOf(if (isTv) 0 else if (engine.selectedNode() == null) 1 else 0) }
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
        if (!isTv && phase == ConnectionState.CONNECTED && !engine.tileOffered) {
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
    val navFocusRequesters = remember { List(items.size) { FocusRequester() } }
    val pageFocusRequesters = remember { List(items.size) { FocusRequester() } }
    LaunchedEffect(isTv) {
        if (isTv) navFocusRequesters[tab].requestFocus()
    }
    GlassBackground {
        if (isTv) {
            Row(Modifier.fillMaxSize()) {
                TvNavigationRail(tab, items, navFocusRequesters, pageFocusRequesters) { tab = it }
                Spacer(Modifier.fillMaxHeight().width(1.dp).background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)))
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    AppPage(
                        tab, engine, say, onConnect, onDisconnect, { tab = it }, isTv,
                        navFocusRequesters[tab], pageFocusRequesters[tab], Modifier.fillMaxSize(),
                    )
                    SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(24.dp))
                }
            }
        } else {
            Scaffold(
                containerColor = Color.Transparent,
                contentColor = MaterialTheme.colorScheme.onBackground,
                snackbarHost = { SnackbarHost(snackbar) },
                bottomBar = {
                    NavigationBar(containerColor = if (MaterialTheme.colorScheme.background.luminance() < 0.5f) Color(0xFF110D18).copy(alpha = 0.78f) else Color.White.copy(alpha = 0.72f)) {
                        for ((label, icon, index) in items) {
                            NavigationBarItem(
                                selected = tab == index, onClick = { tab = index },
                                icon = { Icon(painterResource(icon), contentDescription = null) },
                                label = { Text(label) },
                            )
                        }
                    }
                },
            ) { padding ->
                AppPage(
                    tab, engine, say, onConnect, onDisconnect, { tab = it }, isTv,
                    navFocusRequesters[tab], pageFocusRequesters[tab], Modifier.padding(padding),
                )
            }
        }
    }
}

@Composable
private fun AppPage(
    tab: Int,
    engine: AndroidEngine,
    say: (String) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onNavigate: (Int) -> Unit,
    isTv: Boolean,
    tvNavigationFocusRequester: FocusRequester,
    tvPageFocusRequester: FocusRequester,
    modifier: Modifier,
) {
    when (tab) {
        0 -> HomeScreen(
            engine, onConnect, onDisconnect, onOpenServers = { onNavigate(1) }, modifier = modifier,
            isTv = isTv, tvHomeNavigationFocusRequester = tvNavigationFocusRequester,
            tvEntryFocusRequester = tvPageFocusRequester,
        )
        1 -> ServersScreen(engine, say, onConnect, modifier = modifier, isTv = isTv, tvNavigationFocusRequester = tvNavigationFocusRequester, tvEntryFocusRequester = tvPageFocusRequester)
        2 -> SettingsScreen(engine, modifier = modifier, isTv = isTv, tvNavigationFocusRequester = tvNavigationFocusRequester, tvEntryFocusRequester = tvPageFocusRequester)
        else -> AboutScreen(engine, modifier = modifier, isTv = isTv, tvNavigationFocusRequester = tvNavigationFocusRequester, tvEntryFocusRequester = tvPageFocusRequester)
    }
}

@Composable
private fun TvNavigationRail(
    selectedTab: Int,
    items: List<Triple<String, Int, Int>>,
    navFocusRequesters: List<FocusRequester>,
    pageFocusRequesters: List<FocusRequester>,
    onSelect: (Int) -> Unit,
) {
    Column(
        Modifier.width(232.dp).fillMaxHeight().padding(horizontal = 16.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, bottom = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(painterResource(R.drawable.ember_logo), contentDescription = null, modifier = Modifier.width(42.dp).height(42.dp))
            Spacer(Modifier.width(12.dp))
            Text("Ember", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        }
        for ((label, icon, index) in items) {
            val shape = RoundedCornerShape(18.dp)
            Row(
                Modifier.fillMaxWidth().height(68.dp).clip(shape)
                    .background(if (selectedTab == index) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                    .tvFocusOutline(true, shape)
                    .tvDpadActivate(true) { onSelect(index) }
                    .focusProperties {
                        if (index > 0) up = navFocusRequesters[index - 1]
                        if (index < navFocusRequesters.lastIndex) down = navFocusRequesters[index + 1]
                        if (index == selectedTab) right = pageFocusRequesters[index]
                    }
                    .focusRequester(navFocusRequesters[index])
                    .selectable(selected = selectedTab == index, role = Role.Tab, onClick = { onSelect(index) })
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painterResource(icon), contentDescription = null,
                    tint = if (selectedTab == index) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(28.dp).height(28.dp),
                )
                Spacer(Modifier.width(16.dp))
                Text(
                    label,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (selectedTab == index) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (selectedTab == index) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}
