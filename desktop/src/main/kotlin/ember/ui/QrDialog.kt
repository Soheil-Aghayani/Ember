package ember.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ember.subs.ProxyNode
import ember.subs.QrCodes
import ember.subs.shareLink

/** A server's share link as a QR code, for importing it on a phone. The code sits on white so scanners can read it in dark mode too. */
@Composable
internal fun QrDialog(node: ProxyNode, onClose: () -> Unit) {
    val strings = LocalStrings.current
    val toaster = LocalToaster.current
    val clipboard = LocalClipboardManager.current
    val link = node.shareLink
    val picture = remember(link) { link?.let { runCatching { QrCodes.encode(it).toComposeImageBitmap() }.getOrNull() } }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(node.title, maxLines = 1) },
        text = {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                if (picture != null) {
                    Image(
                        picture, contentDescription = strings.showQr, filterQuality = FilterQuality.None,
                        modifier = Modifier.size(260.dp).clip(RoundedCornerShape(12.dp)).background(Color.White).padding(6.dp),
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(strings.qrHint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                } else {
                    Text(strings.qrTooLong, style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (link != null) IconAction("copy", strings.copyLink, onClick = {
                    clipboard.setText(AnnotatedString(link))
                    toaster.show(strings.copied, ToastKind.Success)
                })
                TextButton(onClick = onClose) { Text(strings.close) }
            }
        },
    )
}
