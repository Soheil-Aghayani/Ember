package ember.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import ember.engine.EmberEngine
import ember.engine.SpeedResult
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun SpeedTestControl(engine: EmberEngine) {
    val strings = LocalStrings.current
    var attempt by remember { mutableStateOf(0) }
    var testing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<SpeedResult?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }

    // Leaving the connected Home composition cancels this effect and clears its state.
    LaunchedEffect(attempt) {
        if (attempt == 0) return@LaunchedEffect
        try {
            val measured = withContext(Dispatchers.IO) { engine.runSpeedTest() }
            measured.onSuccess { result = it }.onFailure {
                if (it is CancellationException) throw it
                failure = it.message ?: strings.speedTestFailed
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            failure = error.message ?: strings.speedTestFailed
        } finally {
            testing = false
        }
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (testing) {
            Text(strings.testingSpeed, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
        } else {
            result?.let {
                val gigabits = it.downloadBitsPerSecond >= 1_000_000_000L
                val speed = String.format(Locale.US, "%.1f %s",
                    it.downloadBitsPerSecond / (if (gigabits) 1_000_000_000.0 else 1_000_000.0),
                    if (gigabits) "Gbit/s" else "Mbit/s")
                Text(strings.speedSummary(speed, it.pingMs), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            failure?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            TextButton(
                onClick = {
                    testing = true
                    result = null
                    failure = null
                    attempt++
                },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
            ) {
                Text(when {
                    failure != null -> strings.tryAgain
                    result != null -> strings.testAgain
                    else -> strings.testSpeed
                })
            }
        }
    }
}

@Composable
internal fun SkipVpnSettings(engine: EmberEngine, enabled: Boolean) {
    val strings = LocalStrings.current
    var entries by remember { mutableStateOf(engine.settings.excludedAddresses) }
    var input by remember { mutableStateOf("") }
    var invalid by remember { mutableStateOf(false) }
    var atLimit by remember { mutableStateOf(false) }
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.alpha(if (enabled) 1f else 0.38f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(strings.skipVpn, style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(strings.skipVpnHelp, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                for (entry in entries) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(entry, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Ltr))
                        IconButton(enabled = enabled, onClick = {
                            entries = entries - entry
                            engine.settings.excludedAddresses = entries
                            atLimit = false
                        }) {
                            EmberIcon("close", contentDescription = strings.removeAddress)
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        OutlinedTextField(value = input, onValueChange = {
                            input = it
                            invalid = false
                            atLimit = false
                        }, enabled = enabled, singleLine = true, isError = invalid || atLimit,
                            placeholder = { Text(strings.addressPlaceholder) },
                            textStyle = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Ltr),
                            modifier = Modifier.weight(1f))
                    }
                    Button(enabled = enabled, onClick = {
                        val address = normalizeExcludedAddress(input)
                        invalid = address == null
                        atLimit = false
                        if (address != null) {
                            if (entries.any { it.equals(address, ignoreCase = true) }) {
                                input = ""
                            } else if (entries.size >= 50) {
                                atLimit = true
                            } else {
                                entries = entries + address
                                engine.settings.excludedAddresses = entries
                                input = ""
                            }
                        }
                    }) { Text(strings.addAddress) }
                }
                if (invalid || atLimit) Text(if (invalid) strings.addressValidation else strings.addressLimit,
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            if (!enabled) Text(strings.needsWholeDevice, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

internal fun normalizeExcludedAddress(value: String): String? {
    val address = value.trim().lowercase(Locale.ROOT)
    fun ipv4(ip: String): Boolean {
        val parts = ip.split('.')
        return parts.size == 4 && parts.all { part ->
            part.isNotEmpty() && part.length <= 3 && part.all { it in '0'..'9' } &&
                part.toIntOrNull()?.let { it in 0..255 } == true
        }
    }
    if ('/' in address) {
        val parts = address.split('/')
        return address.takeIf {
            parts.size == 2 && ipv4(parts[0]) && parts[1].isNotEmpty() &&
                parts[1].all { it in '0'..'9' } && parts[1].toIntOrNull()?.let { it in 8..32 } == true
        }
    }
    if (ipv4(address)) return address
    // A malformed numeric IPv4 address must not fall through as a domain.
    if (address.all { it in '0'..'9' || it == '.' }) return null
    val labels = address.split('.')
    return address.takeIf {
        address.length <= 253 && labels.size >= 2 && labels.all { label ->
            label.length in 1..63 && label.first() != '-' && label.last() != '-' &&
                label.all { it in 'a'..'z' || it in '0'..'9' || it == '-' }
        }
    }
}
