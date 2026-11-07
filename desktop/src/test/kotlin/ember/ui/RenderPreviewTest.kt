package ember.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.soheil.ember.data.model.ConnectionState
import java.io.File
import kotlin.test.Test
import org.jetbrains.skia.EncodedImageFormat

/**
 * Draws screens that do not need the engine into PNG files, so a change to colours or layout can be looked at without
 * starting the app. Runs only when RENDER_DIR names a folder.
 */
@OptIn(ExperimentalComposeUiApi::class)
class RenderPreviewTest {

    private fun render(dir: File, name: String, dark: Boolean, width: Int = 760, height: Int = 640, content: @Composable () -> Unit) {
        val scene = ImageComposeScene(width, height, Density(1f)) {
            EmberTheme(darkTheme = dark) {
                CompositionLocalProvider(LocalStrings provides EnglishStrings) {
                    GlassBackground { Column(Modifier.fillMaxSize().padding(24.dp)) { content() } }
                }
            }
        }
        // let animations (themes, toasts, appearing rows) reach their end
        var image = scene.render(0)
        for (step in 1..6) image = scene.render(step * 250_000_000L)
        File(dir, "$name.png").writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        scene.close()
    }

    @Test
    fun drawsScreens() {
        val dir = System.getenv("RENDER_DIR")?.let(::File) ?: return
        dir.mkdirs()
        for (dark in listOf(false, true)) {
            val theme = if (dark) "dark" else "light"
            render(dir, "whats-new-$theme", dark) { WhatsNewPage("en") }
            render(dir, "about-$theme", dark) { AboutPage() }
            render(dir, "empty-$theme", dark, height = 420) {
                Text("Servers", style = MaterialTheme.typography.headlineLarge)
                Card { EmptyState("subscriptions", "No servers yet", EnglishStrings.noSubscriptions) {} }
            }
            render(dir, "qr-$theme", dark, width = 360, height = 360) {
                androidx.compose.foundation.Image(
                    ember.subs.QrCodes.encode("vless://11111111-2222-3333-4444-555555555555@vl.example.com:443?type=tcp&security=reality#UK").toComposeImageBitmap(),
                    contentDescription = null, filterQuality = androidx.compose.ui.graphics.FilterQuality.None,
                    modifier = Modifier.size(260.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp)).background(androidx.compose.ui.graphics.Color.White).padding(6.dp),
                )
            }
            for (state in ConnectionState.entries) {
                render(dir, "orb-${state.name.lowercase()}-$theme", dark, width = 420, height = 360) {
                    ConnectOrb(state, "Connect") {}
                }
            }
            render(dir, "toast-$theme", dark, height = 220) {
                val toaster = androidx.compose.runtime.remember { Toaster().apply { show("Added 17 servers", ToastKind.Success); show("Could not read that file", ToastKind.Error) } }
                Text("Settings", style = MaterialTheme.typography.headlineLarge)
                ToastHost(toaster)
            }
        }
    }

    /** The sidebar on the glass backdrop, with the pointer sweeping over its items, to see the hover fade and the divider. */
    @Test
    fun drawsSidebarHover() {
        val dir = System.getenv("RENDER_DIR")?.let(::File) ?: return
        dir.mkdirs()
        for (dark in listOf(false, true)) {
            val scene = ImageComposeScene(520, 420, Density(1f)) {
                EmberTheme(darkTheme = dark) {
                    CompositionLocalProvider(LocalStrings provides EnglishStrings) {
                        GlassBackground { androidx.compose.foundation.layout.Row(Modifier.fillMaxSize()) { Sidebar(Page.Home) {} } }
                    }
                }
            }
            scene.render(0)
            // a fast sweep down the list ends on "Settings"; read the picture straight after, then once the fades are over
            scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Enter, androidx.compose.ui.geometry.Offset(100f, 100f))
            for ((i, y) in listOf(100f, 140f, 188f, 230f).withIndex()) {
                scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Move, androidx.compose.ui.geometry.Offset(100f, y))
                scene.render((i + 1) * 20_000_000L)
            }
            val during = scene.render(6 * 20_000_000L + 30_000_000L)
            File(dir, "sidebar-sweep-${if (dark) "dark" else "light"}.png").writeBytes(during.encodeToData(EncodedImageFormat.PNG)!!.bytes)
            var settled = during
            for (step in 1..8) settled = scene.render(200_000_000L + step * 250_000_000L)
            File(dir, "sidebar-settled-${if (dark) "dark" else "light"}.png").writeBytes(settled.encodeToData(EncodedImageFormat.PNG)!!.bytes)
            scene.close()
        }
    }

    /** The orb through a connect and a disconnect: off, warming up, just connected, settled, draining. */
    @Test
    fun drawsOrbThroughAConnection() {
        val dir = System.getenv("RENDER_DIR")?.let(::File) ?: return
        dir.mkdirs()
        for (dark in listOf(true, false)) {
            var state by androidx.compose.runtime.mutableStateOf(ConnectionState.DISCONNECTED)
            val scene = ImageComposeScene(300, 300, Density(1f)) {
                EmberTheme(darkTheme = dark) {
                    CompositionLocalProvider(LocalStrings provides EnglishStrings) {
                        GlassBackground { ConnectOrb(state, "Connect") {} }
                    }
                }
            }
            var t = 0L
            fun advance(seconds: Double) { repeat((seconds * 20).toInt()) { t += 50_000_000L; scene.render(t) } }
            fun shot(name: String) {
                val image = scene.render(t + 1_000_000L)
                File(dir, "orb-seq-${if (dark) "dark" else "light"}-$name.png").writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
            }
            scene.render(0)
            advance(1.0); shot("1-off")
            state = ConnectionState.CONNECTING
            advance(0.5); shot("2-connecting-a")
            advance(1.0); shot("3-connecting-b")
            state = ConnectionState.CONNECTED
            advance(0.45); shot("4-connected-ripple")
            advance(3.0); shot("5-connected")
            state = ConnectionState.DISCONNECTED
            advance(0.55); shot("6-draining")
            advance(2.0); shot("7-off-again")
            scene.close()
        }
    }
}
