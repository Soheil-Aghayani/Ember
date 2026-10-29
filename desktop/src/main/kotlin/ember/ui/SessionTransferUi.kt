package ember.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.soheil.ember.data.FxaAuthRepository
import com.soheil.ember.data.SessionTransfer
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.awt.image.BufferedImage
import kotlinx.coroutines.launch

private fun qrBitmap(text: String): ImageBitmap {
    val matrix = QRCodeWriter().encode(
        text, BarcodeFormat.QR_CODE, 0, 0,
        mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 2),
    )
    val scale = 6
    val image = BufferedImage(matrix.width * scale, matrix.height * scale, BufferedImage.TYPE_INT_RGB)
    for (y in 0 until image.height) for (x in 0 until image.width) {
        image.setRGB(x, y, if (matrix[x / scale, y / scale]) 0x1F1A17 else 0xFFFFFF)
    }
    return image.toComposeImageBitmap()
}

/** Settings card: shows a one-time code that signs another device in without the account password. */
@Composable
internal fun SessionTransferCard(auth: FxaAuthRepository) {
    val strings = LocalStrings.current
    val clipboard = LocalClipboardManager.current
    var created by remember { mutableStateOf<SessionTransfer.Created?>(null) }

    Card {
        Column(Modifier.padding(16.dp)) {
            Text(strings.transferTitle, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Text(strings.transferHelp, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            val pair = created
            if (pair == null) {
                OutlinedButton(
                    onClick = { created = auth.refreshTokenForTransfer()?.let(SessionTransfer::create) },
                    shape = RoundedCornerShape(12.dp),
                ) { Text(strings.transferCreate) }
            } else {
                Text(strings.transferPassphrase, style = MaterialTheme.typography.labelMedium)
                SelectionContainer {
                    Text(pair.passphrase, style = MaterialTheme.typography.headlineSmall, fontFamily = FontFamily.Monospace)
                }
                Spacer(Modifier.height(8.dp))
                Text(strings.transferQr, style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(4.dp))
                Image(
                    remember(pair.qrPayload) { qrBitmap(pair.qrPayload) },
                    contentDescription = strings.transferQr,
                    modifier = Modifier.size(220.dp).clip(RoundedCornerShape(8.dp)),
                )
                Spacer(Modifier.height(8.dp))
                Text(strings.transferCode, style = MaterialTheme.typography.labelMedium)
                SelectionContainer {
                    Text(pair.code, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
                Spacer(Modifier.height(8.dp))
                Text(strings.transferValid(SessionTransfer.VALID_MINUTES.toInt()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { clipboard.setText(AnnotatedString(pair.code)) }, shape = RoundedCornerShape(12.dp)) { Text(strings.transferCopyCode) }
                    TextButton(onClick = { created = null }) { Text(strings.transferHide) }
                }
            }
        }
    }
}

/** Sign-in form that takes the code and passphrase from [SessionTransferCard] on another device. */
@Composable
internal fun CodeSignIn(auth: FxaAuthRepository, onSignedIn: () -> Unit, onBack: () -> Unit) {
    val strings = LocalStrings.current
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Text(strings.transferEnterIntro, style = MaterialTheme.typography.bodyMedium)
    Spacer(Modifier.height(12.dp))
    OutlinedTextField(
        value = code,
        onValueChange = { code = it },
        label = { Text(strings.transferCode) },
        shape = RoundedCornerShape(12.dp),
        maxLines = 4,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(12.dp))
    OutlinedTextField(
        value = passphrase,
        onValueChange = { passphrase = it },
        label = { Text(strings.transferPassphrase) },
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(20.dp))
    Button(
        onClick = {
            error = null
            busy = true
            scope.launch {
                val opened = SessionTransfer.open(code, passphrase)
                val result = opened.map { auth.signInWithRefreshToken(it) }
                busy = false
                val failure = opened.exceptionOrNull() ?: result.getOrNull()?.exceptionOrNull()
                if (failure == null) onSignedIn() else error = failure.message ?: strings.signInFailed
            }
        },
        enabled = !busy && code.isNotBlank() && passphrase.isNotBlank(),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().height(48.dp),
    ) { Text(if (busy) strings.signingIn else strings.continueLabel) }
    TextButton(onClick = onBack) { Text(strings.transferBack) }
    error?.let {
        Spacer(Modifier.height(8.dp))
        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
}
