package ember.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import ember.engine.CredentialRequest
import ember.engine.EmberEngine
import ember.engine.OpenVpnCredentials

/** Asks for the user name and password an OpenVPN server wants; they are kept only in memory. */
@Composable
internal fun CredentialDialog(engine: EmberEngine) {
    val request by engine.credentialRequest.collectAsState()
    request?.let { Ask(it) }
}

@Composable
private fun Ask(request: CredentialRequest) {
    val strings = LocalStrings.current
    var user by remember(request) { mutableStateOf("") }
    var password by remember(request) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { request.answer.complete(null) },
        title = { Text(if (request.askUser) strings.openVpnSignIn(request.serverName) else strings.keyPasswordFor(request.serverName)) },
        text = {
            Column {
                if (request.askUser) {
                    OutlinedTextField(
                        value = user, onValueChange = { user = it }, label = { Text(strings.username) },
                        singleLine = true, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(12.dp))
                }
                OutlinedTextField(
                    value = password, onValueChange = { password = it }, label = { Text(strings.password) },
                    singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text(strings.credentialsNotSaved, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            Button(
                enabled = password.isNotEmpty() && (!request.askUser || user.isNotBlank()),
                onClick = { request.answer.complete(OpenVpnCredentials(if (request.askUser) user.trim() else null, password)) },
            ) { Text(strings.signIn) }
        },
        dismissButton = { TextButton(onClick = { request.answer.complete(null) }) { Text(strings.cancel) } },
    )
}
