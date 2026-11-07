package com.soheil.ember.ui

import android.graphics.Bitmap
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.soheil.ember.R
import ember.subs.ProxyNode
import ember.subs.shareLink

/** [text] as a black-on-white QR picture, with the quiet border scanners need; null when it is too long for a QR code. */
private fun qrBitmap(text: String, size: Int = 600): Bitmap? = runCatching {
    val matrix = QRCodeWriter().encode(
        text, BarcodeFormat.QR_CODE, size, size,
        mapOf(EncodeHintType.MARGIN to 2, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.CHARACTER_SET to "UTF-8"),
    )
    val pixels = IntArray(matrix.width * matrix.height) { i -> if (matrix[i % matrix.width, i / matrix.width]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
    Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
}.getOrNull()

/** A server's share link as a QR code, for adding it on another phone or computer. The code sits on white so scanners can read it in dark mode too. */
@Composable
fun QrDialog(node: ProxyNode, say: (String) -> Unit, onClose: () -> Unit) {
    val s = LocalStrings.current
    val clipboard = LocalClipboardManager.current
    val link = node.shareLink
    val picture = remember(link) { link?.let { qrBitmap(it) } }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(node.title, maxLines = 1) },
        text = {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                if (picture != null) {
                    Image(
                        picture.asImageBitmap(), contentDescription = s.showQr, filterQuality = FilterQuality.None,
                        modifier = Modifier.size(260.dp).clip(RoundedCornerShape(12.dp)).background(Color.White).padding(6.dp),
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(s.qrHint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                } else {
                    Text(s.qrTooLong, style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (link != null) IconButton(onClick = {
                    clipboard.setText(AnnotatedString(link))
                    say(s.copied)
                }) { Icon(painterResource(R.drawable.ic_solar_copy), contentDescription = s.copyLink) }
                TextButton(onClick = onClose) { Text(s.close) }
            }
        },
    )
}
