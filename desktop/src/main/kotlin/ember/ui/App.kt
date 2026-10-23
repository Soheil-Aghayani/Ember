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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp

private enum class Page(val label: String) { Home("Home"), Locations("Locations"), Settings("Settings") }

private val CardShape = RoundedCornerShape(16.dp)

@Composable
fun EmberApp() {
    var page by remember { mutableStateOf(Page.Home) }
    var location by remember { mutableStateOf("Recommended") }

    Row(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Sidebar(page) { page = it }
        Box(Modifier.width(1.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant))
        Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier
                    .widthIn(max = 640.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(32.dp),
            ) {
                when (page) {
                    Page.Home -> HomePage(location) { page = Page.Locations }
                    Page.Locations -> LocationsPage(location) { location = it; page = Page.Home }
                    Page.Settings -> SettingsPage()
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
private fun HomePage(location: String, onOpenLocations: () -> Unit) {
    var notice by remember { mutableStateOf(false) }
    val ring = MaterialTheme.colorScheme.primary

    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(16.dp))
        Box(
            Modifier.size(180.dp).clip(CircleShape).background(ring.copy(alpha = 0.15f)).clickable { notice = true },
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(140.dp).clip(CircleShape).background(ring.copy(alpha = 0.25f)), contentAlignment = Alignment.Center) {
                Text("Connect", style = MaterialTheme.typography.titleMedium, color = ring)
            }
        }
        Spacer(Modifier.height(16.dp))
        Text("Disconnected", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            if (notice) "The desktop tunnel is not built yet. Nothing is being routed." else "00:00:00",
            style = MaterialTheme.typography.bodyMedium,
            color = if (notice) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        Card(Modifier.clickable(onClick = onOpenLocations)) {
            Column(Modifier.padding(16.dp)) {
                Text("Location", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                Text(location, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

private val sampleLocations = listOf("Recommended", "Germany", "Netherlands", "United Kingdom", "United States", "Japan")

@Composable
private fun LocationsPage(selected: String, onPick: (String) -> Unit) {
    Text("Choose location", style = MaterialTheme.typography.headlineLarge)
    Spacer(Modifier.height(16.dp))
    Card {
        Column {
            sampleLocations.forEachIndexed { i, name ->
                if (i > 0) HorizontalDivider(Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                Row(
                    Modifier.fillMaxWidth().clickable { onPick(name) }.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    if (name == selected) {
                        Text("Selected", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
    Text(
        "Sample list. The live server list will come from the same service as the Android app.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, top = 12.dp),
    )
}

@Composable
private fun SettingsPage() {
    var proxyOnly by remember { mutableStateOf(false) }
    var verifyExit by remember { mutableStateOf(true) }
    Text("Settings", style = MaterialTheme.typography.headlineLarge)
    Spacer(Modifier.height(16.dp))
    Card {
        Column {
            SwitchRow("Verify exit location", "Check that the tunnel exits in the selected country", verifyExit) { verifyExit = it }
            HorizontalDivider(Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
            SwitchRow("Proxy-only mode", "Run local SOCKS5/HTTP proxies without a VPN interface", proxyOnly) { proxyOnly = it }
        }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
