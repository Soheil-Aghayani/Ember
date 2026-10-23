package ember

import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import ember.ui.EmberApp
import ember.ui.EmberTheme

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "Ember",
        icon = painterResource("ember_logo.png"),
        state = rememberWindowState(width = 960.dp, height = 680.dp),
    ) {
        EmberTheme {
            EmberApp()
        }
    }
}
