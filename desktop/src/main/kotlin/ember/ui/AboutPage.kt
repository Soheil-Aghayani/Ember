package ember.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import ember.engine.appDataDir
import java.awt.Desktop
import java.net.URI

// Keep in step with the pinned downloads in desktop/build.gradle.kts.
private const val XRAY_VERSION = "26.10.31"
private const val TUNNEL_VERSION = "hev-socks5-tunnel 2.19.1 · Wintun 0.14.1"

private const val CONTACT_HANDLE = "@env_soh"
private const val CONTACT_LINK = "https://t.me/env_soh"

private val Licenses = listOf(
    "Xray-core" to "MPL-2.0",
    "hev-socks5-tunnel" to "MIT",
    "Wintun" to "WireGuard LLC prebuilt binaries licence",
    "Inter (font)" to "SIL Open Font License 1.1",
    "Solar icons (480 Design)" to "CC BY 4.0",
    "circle-flags" to "MIT",
    "Kotlin, Compose Multiplatform" to "Apache-2.0",
)

@Composable
internal fun AboutPage() {
    val strings = LocalStrings.current
    var showLicenses by remember { mutableStateOf(false) }
    val device = listOfNotNull(
        "${System.getProperty("os.name")} ${System.getProperty("os.version")}",
        System.getenv("COMPUTERNAME"),
    ).joinToString(" · ")

    Text(strings.about, style = MaterialTheme.typography.headlineLarge)
    Spacer(Modifier.height(16.dp))
    Card {
        SelectionContainer {
            Column {
                InfoRow(strings.aboutProgramVersion, System.getProperty("jpackage.app-version") ?: strings.developmentBuild)
                InfoRow(strings.aboutXray, XRAY_VERSION)
                InfoRow(strings.aboutTunnel, TUNNEL_VERSION)
                InfoRow(strings.aboutDevice, device)
                InfoRow(strings.aboutLog, appDataDir().resolve("ember.log").toString())
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    Card {
        Column {
            Row(
                Modifier.fillMaxWidth().pointerHoverIcon(PointerIcon.Hand).clickable(role = Role.Button) {
                    runCatching { Desktop.getDesktop().browse(URI(CONTACT_LINK)) }
                }.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                EmberIcon("contact", 20.dp, MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(strings.aboutContact, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Telegram  $CONTACT_HANDLE", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
                }
            }
            HorizontalDivider(Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                Modifier.fillMaxWidth().pointerHoverIcon(PointerIcon.Hand).clickable(role = Role.Button) { showLicenses = !showLicenses }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(strings.aboutLicenses, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                Text(if (showLicenses) strings.hide else strings.show, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            }
            if (showLicenses) {
                for ((name, licence) in Licenses) {
                    HorizontalDivider(Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    InfoRow(name, licence)
                }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.Top) {
        Text(label, Modifier.weight(0.4f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, Modifier.weight(0.6f), style = MaterialTheme.typography.bodyMedium)
    }
}
