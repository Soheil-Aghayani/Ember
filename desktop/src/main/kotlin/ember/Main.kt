package ember

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.isTraySupported
import androidx.compose.ui.window.rememberWindowState
import com.soheil.ember.data.model.ConnectionState
import ember.engine.EmberEngine
import ember.engine.UpdateCheck
import ember.engine.appDataDir
import ember.ui.EmberApp
import ember.ui.EmberTheme

fun main(args: Array<String>) = application {
    val engine = remember { EmberEngine(appDataDir()) }
    var visible by remember { mutableStateOf(!args.contains("--minimized") || !isTraySupported) }
    var theme by remember { mutableStateOf(engine.settings.theme) }
    var update by remember { mutableStateOf<String?>(null) }
    val state by engine.state.collectAsState()
    val windowState = rememberWindowState(width = 960.dp, height = 680.dp)
    val quit = {
        engine.shutdown()
        exitApplication()
    }
    LaunchedEffect(Unit) { update = UpdateCheck.latestVersion() }
    if (isTraySupported) {
        Tray(painterResource("ember_logo.png"), tooltip = "Ember", onAction = {
            windowState.isMinimized = false
            visible = true
        }) {
            Item("Open Ember", onClick = { windowState.isMinimized = false; visible = true })
            Item(if (state == ConnectionState.DISCONNECTED) "Connect" else "Disconnect", onClick = {
                if (state == ConnectionState.DISCONNECTED) engine.connect(null) else engine.disconnect()
            })
            Separator()
            Item("Quit", onClick = quit)
        }
    }
    Window(
        onCloseRequest = {
            if (isTraySupported && engine.settings.keepInTray) visible = false else quit()
        },
        title = "Ember",
        icon = painterResource("ember_logo.png"),
        state = windowState,
        visible = visible,
    ) {
        EmberTheme(darkTheme = when (theme) {
            "Light" -> false
            "Dark" -> true
            else -> isSystemInDarkTheme()
        }) {
            EmberApp(engine, theme, { theme = it; engine.settings.theme = it }, update, { update = null })
        }
    }
}
