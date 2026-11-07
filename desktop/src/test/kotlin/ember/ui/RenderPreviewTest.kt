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
                    ConnectOrb(state, MaterialTheme.colorScheme.primary, 1f, "Connect") {}
                }
            }
            render(dir, "toast-$theme", dark, height = 220) {
                val toaster = androidx.compose.runtime.remember { Toaster().apply { show("Added 17 servers", ToastKind.Success); show("Could not read that file", ToastKind.Error) } }
                Text("Settings", style = MaterialTheme.typography.headlineLarge)
                ToastHost(toaster)
            }
        }
    }
}
