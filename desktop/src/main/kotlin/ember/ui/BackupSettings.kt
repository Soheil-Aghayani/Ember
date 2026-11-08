package ember.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ember.engine.Backup
import ember.engine.appDataDir
import java.awt.FileDialog
import java.io.File
import kotlin.system.exitProcess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Saves the subscriptions and settings to one file, and puts them back from one (on this or another computer). */
@Composable
internal fun BackupSettings(stopApp: () -> Unit) {
    val strings = LocalStrings.current
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    var chosen by remember { mutableStateOf<File?>(null) }

    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(strings.backupHint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = {
                scope.launch {
                    val target = withContext(Dispatchers.IO) { chooseBackupFile(strings.saveBackup, save = true) } ?: return@launch
                    message = runCatching { withContext(Dispatchers.IO) { Backup.write(appDataDir(), target) } }
                        .fold({ strings.backupSaved(target.name) }, { strings.backupFailed(it.message.orEmpty()) })
                }
            }, shape = RoundedCornerShape(12.dp)) { Text(strings.saveBackup) }
            OutlinedButton(onClick = {
                scope.launch { chosen = withContext(Dispatchers.IO) { chooseBackupFile(strings.restoreBackup, save = false) } }
            }, shape = RoundedCornerShape(12.dp)) { Text(strings.restoreBackup) }
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }

    chosen?.let { file ->
        AlertDialog(
            onDismissRequest = { chosen = null },
            title = { Text(strings.restoreBackup) },
            text = { Text(strings.restoreConfirm(file.name)) },
            confirmButton = {
                TextButton(onClick = {
                    chosen = null
                    scope.launch {
                        // Stop everything first: the running app would write its own copies of these files over the restored ones.
                        withContext(Dispatchers.IO) { stopApp() }
                        val result = runCatching { withContext(Dispatchers.IO) { Backup.restore(appDataDir(), file) } }
                        result.exceptionOrNull()?.let { message = strings.backupFailed(it.message.orEmpty()) }
                        // Start again either way: the app is stopped now, and a failed restore left the old files as they were.
                        ProcessHandle.current().info().command().ifPresent { ProcessBuilder(it).start() }
                        exitProcess(0)
                    }
                }) { Text(strings.restoreAndRestart) }
            },
            dismissButton = { TextButton(onClick = { chosen = null }) { Text(strings.cancel) } },
        )
    }
}

/** The system file dialog; call it off the UI thread (it blocks until the user is done). */
private fun chooseBackupFile(title: String, save: Boolean): File? {
    val dialog = FileDialog(null as java.awt.Frame?, title, if (save) FileDialog.SAVE else FileDialog.LOAD)
    dialog.file = if (save) "Ember-backup.zip" else "*.zip"
    dialog.isVisible = true
    val name = dialog.file ?: return null
    return File(dialog.directory, name)
}
