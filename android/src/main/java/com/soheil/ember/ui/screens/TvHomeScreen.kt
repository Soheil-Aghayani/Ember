package com.soheil.ember.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.soheil.ember.R
import com.soheil.ember.data.formatBytes
import com.soheil.ember.data.model.ConnectionState
import com.soheil.ember.data.model.ProxyCandidate
import com.soheil.ember.ui.components.EmberInlineErrorMessage
import com.soheil.ember.ui.components.EmberTvActionButton
import com.soheil.ember.ui.components.FlagIcon
import com.soheil.ember.ui.theme.EmberStatusColors
import com.soheil.ember.ui.theme.EmberTvDimensions
import com.soheil.ember.ui.theme.ThemeController
import com.soheil.ember.ui.theme.ThemeMode

@Composable
fun TvHomeScreen(
    state: ConnectionState,
    lastError: String?,
    sessionBytes: Long,
    elapsedSeconds: Long,
    pingLatency: Int?,
    ipDisplay: String,
    selectedProxy: ProxyCandidate?,
    quotaText: String,
    quotaFraction: Float,
    ringColor: Color,
    scale: Float,
    statusColors: EmberStatusColors,
    themeController: ThemeController,
    onRequestConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onOpenServers: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenLogs: () -> Unit,
) {
    val connectFocusRequester = remember { FocusRequester() }
    val systemInDarkTheme = isSystemInDarkTheme()
    val isConnected = state == ConnectionState.CONNECTED
    val statusText = when (state) {
        ConnectionState.CONNECTED -> "Connected"
        ConnectionState.CONNECTING -> "Connecting…"
        ConnectionState.DISCONNECTED -> "Disconnected"
    }
    val statusColor = when (state) {
        ConnectionState.CONNECTED -> statusColors.connected
        ConnectionState.CONNECTING -> statusColors.connecting
        ConnectionState.DISCONNECTED -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val locationName = selectedProxy?.let { it.countryName.ifBlank { it.countryCode } } ?: "Recommended"

    LaunchedEffect(connectFocusRequester) {
        connectFocusRequester.requestFocus()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Ember", style = MaterialTheme.typography.headlineLarge) },
                actions = {
                    EmberTvActionButton(
                        onClick = { themeController.toggle(systemInDarkTheme) },
                        modifier = Modifier.widthIn(min = 144.dp),
                        minHeight = 48.dp,
                    ) {
                        Icon(
                            painter = painterResource(
                                when (themeController.mode) {
                                    ThemeMode.SYSTEM -> R.drawable.ic_solar_sun_2
                                    ThemeMode.LIGHT -> R.drawable.ic_solar_sun
                                    ThemeMode.DARK -> R.drawable.ic_solar_moon
                                },
                            ),
                            contentDescription = null,
                            modifier = Modifier.size(EmberTvDimensions.largeIcon),
                        )
                        Text("Theme")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                modifier = Modifier
                    .widthIn(max = EmberTvDimensions.contentMaxWidth)
                    .fillMaxSize()
                    .padding(horizontal = EmberTvDimensions.contentInset, vertical = EmberTvDimensions.sectionGap),
                horizontalArrangement = Arrangement.spacedBy(EmberTvDimensions.columnGap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier
                        .weight(0.9f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .size(EmberTvDimensions.connectionOrb)
                            .clip(CircleShape)
                            .background(ringColor.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .size((EmberTvDimensions.connectionOrb.value * 0.78f * scale).dp)
                                .clip(CircleShape)
                                .background(ringColor.copy(alpha = 0.25f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_solar_power),
                                contentDescription = null,
                                tint = ringColor,
                                modifier = Modifier.size(EmberTvDimensions.largeIcon * 2),
                            )
                        }
                    }
                    Spacer(Modifier.height(EmberTvDimensions.sectionGap))
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Bold),
                        color = statusColor,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = if (isConnected) formatDuration(elapsedSeconds) else "00:00:00",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (lastError != null && state != ConnectionState.CONNECTING) {
                        Spacer(Modifier.height(EmberTvDimensions.sectionGap))
                        EmberInlineErrorMessage(lastError, modifier = Modifier.fillMaxWidth())
                    }
                    Spacer(Modifier.height(EmberTvDimensions.sectionGap))
                    EmberTvActionButton(
                        onClick = { if (state == ConnectionState.DISCONNECTED) onRequestConnect() else onDisconnect() },
                        modifier = Modifier.fillMaxWidth(),
                        focusRequester = connectFocusRequester,
                        primary = true,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_solar_power),
                            contentDescription = null,
                            modifier = Modifier.size(EmberTvDimensions.largeIcon),
                        )
                        Text(
                            when (state) {
                                ConnectionState.CONNECTED -> "Disconnect"
                                ConnectionState.CONNECTING -> "Cancel connection"
                                ConnectionState.DISCONNECTED -> "Connect"
                            },
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                }

                Column(
                    modifier = Modifier
                        .weight(1.1f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(EmberTvDimensions.sectionGap),
                ) {
                    EmberTvActionButton(onClick = onOpenServers, modifier = Modifier.fillMaxWidth()) {
                        Icon(
                            painter = painterResource(R.drawable.ic_solar_global),
                            contentDescription = null,
                            modifier = Modifier.size(EmberTvDimensions.largeIcon),
                        )
                        Column(Modifier.weight(1f)) {
                            Text("Location", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                FlagIcon(selectedProxy?.countryCode.orEmpty(), 24.dp)
                                Spacer(Modifier.width(8.dp))
                                Text(locationName, style = MaterialTheme.typography.titleMedium)
                            }
                        }
                        Icon(
                            painter = painterResource(R.drawable.ic_solar_alt_arrow_right),
                            contentDescription = null,
                            modifier = Modifier.size(EmberTvDimensions.largeIcon),
                        )
                    }

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    ) {
                        Column(Modifier.padding(EmberTvDimensions.sectionGap)) {
                            Text("Current IP", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(6.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    ipDisplay,
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                )
                                StatusBadge(isConnected, statusColors.connected)
                            }
                            HorizontalDivider(
                                modifier = Modifier.padding(vertical = EmberTvDimensions.sectionGap),
                                color = MaterialTheme.colorScheme.outlineVariant,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(EmberTvDimensions.columnGap)) {
                                Metric("Data Used", if (isConnected) formatBytes(sessionBytes) else "0.0 MB", Modifier.weight(1f))
                                Metric("Latency", pingLatency?.let { "$it ms" } ?: "— ms", Modifier.weight(1f))
                            }
                        }
                    }

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    ) {
                        Column(Modifier.padding(EmberTvDimensions.sectionGap)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "Monthly quota remaining",
                                    modifier = Modifier.weight(1f),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(quotaText, fontWeight = FontWeight.SemiBold)
                            }
                            Spacer(Modifier.height(12.dp))
                            LinearProgressIndicator(
                                progress = { quotaFraction },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(3.dp)),
                                color = if (isConnected) statusColors.connected else MaterialTheme.colorScheme.outlineVariant,
                                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            )
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(EmberTvDimensions.sectionGap)) {
                        EmberTvActionButton(
                            onClick = onOpenSettings,
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(painterResource(R.drawable.ic_solar_settings), null, Modifier.size(EmberTvDimensions.largeIcon))
                            Text("Settings")
                        }
                        EmberTvActionButton(
                            onClick = onOpenLogs,
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(painterResource(R.drawable.ic_solar_code), null, Modifier.size(EmberTvDimensions.largeIcon))
                            Text("Logs")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Metric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun StatusBadge(isConnected: Boolean, connectedColor: Color) {
    val color = if (isConnected) {
        connectedColor
    } else {
        MaterialTheme.colorScheme.error
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            if (isConnected) "Protected" else "Exposed",
            color = color,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
        )
    }
}
