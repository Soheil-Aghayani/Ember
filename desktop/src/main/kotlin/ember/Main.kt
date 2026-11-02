package ember

import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.isTraySupported
import androidx.compose.ui.window.rememberWindowState
import com.soheil.ember.data.model.ConnectionState
import ember.engine.EmberEngine
import ember.engine.UpdateCheck
import ember.engine.appDataDir
import ember.ui.EmberApp
import ember.ui.EmberDesktopMetrics
import ember.ui.EmberMacWindowTitleBar
import ember.ui.EmberTheme
import ember.ui.EnglishStrings
import ember.ui.LocalStrings
import ember.ui.PersianStrings
import java.awt.GraphicsDevice
import java.awt.GraphicsEnvironment
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.geom.RoundRectangle2D

fun main(args: Array<String>) = application {
    val engine = remember { EmberEngine(appDataDir()) }
    var visible by remember { mutableStateOf(!args.contains("--minimized") || !isTraySupported) }
    var theme by remember { mutableStateOf(engine.settings.theme) }
    var language by remember { mutableStateOf(engine.settings.language) }
    val strings = if (language == "fa") PersianStrings else EnglishStrings
    var update by remember { mutableStateOf<String?>(null) }
    val state by engine.state.collectAsState()
    val windowState = rememberWindowState(width = 960.dp, height = 680.dp)
    val useMacStyleChrome = System.getProperty("os.name").startsWith("Windows")
    val supportsRoundedWindow = remember(useMacStyleChrome) {
        if (!useMacStyleChrome) false else runCatching {
            val device = GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice
            device.isWindowTranslucencySupported(GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSPARENT) &&
                device.isWindowTranslucencySupported(GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSLUCENT)
        }.getOrDefault(false)
    }
    val showRoundedFrame = useMacStyleChrome && windowState.placement == WindowPlacement.Floating
    val quit = {
        engine.shutdown()
        exitApplication()
    }
    val closeWindow = {
        if (isTraySupported && engine.settings.keepInTray) visible = false else quit()
    }
    LaunchedEffect(Unit) { update = UpdateCheck.latestVersion() }
    if (isTraySupported) {
        Tray(painterResource("ember_logo.png"), tooltip = "Ember", onAction = {
            windowState.isMinimized = false
            visible = true
        }) {
            Item(strings.openEmber, onClick = { windowState.isMinimized = false; visible = true })
            Item(if (state == ConnectionState.DISCONNECTED) strings.connect else strings.disconnect, onClick = {
                if (state == ConnectionState.DISCONNECTED) engine.connectSelected() else engine.disconnect()
            })
            Separator()
            Item(strings.quit, onClick = quit)
        }
    }
    Window(
        onCloseRequest = closeWindow,
        title = "Ember",
        icon = painterResource("ember_logo.png"),
        state = windowState,
        visible = visible,
        undecorated = useMacStyleChrome,
        transparent = supportsRoundedWindow,
        resizable = true,
    ) {
        val density = LocalDensity.current
        LaunchedEffect(density) {
            window.minimumSize = java.awt.Dimension(
                with(density) { 760.dp.roundToPx() },
                with(density) { 560.dp.roundToPx() },
            )
        }
        DisposableEffect(window, supportsRoundedWindow, density, showRoundedFrame) {
            if (!supportsRoundedWindow || !showRoundedFrame) {
                if (supportsRoundedWindow) window.shape = null
                return@DisposableEffect onDispose {}
            }
            val cornerDiameter = with(density) { EmberDesktopMetrics.windowCornerRadius.toPx() * 2 }
            fun updateWindowShape() {
                if (window.width > 0 && window.height > 0) {
                    runCatching {
                        window.shape = RoundRectangle2D.Double(
                            0.0,
                            0.0,
                            window.width.toDouble(),
                            window.height.toDouble(),
                            cornerDiameter.toDouble(),
                            cornerDiameter.toDouble(),
                        )
                    }
                }
            }
            val resizeListener = object : ComponentAdapter() {
                override fun componentResized(event: ComponentEvent) = updateWindowShape()
            }
            window.addComponentListener(resizeListener)
            updateWindowShape()
            onDispose {
                window.removeComponentListener(resizeListener)
                window.shape = null
            }
        }
        EmberTheme(darkTheme = when (theme) {
            "Light" -> false
            "Dark" -> true
            else -> isSystemInDarkTheme()
        }) {
            CompositionLocalProvider(LocalStrings provides strings) {
                val frameShape = RoundedCornerShape(EmberDesktopMetrics.windowCornerRadius)
                Box(Modifier.fillMaxSize()) {
                    Column(
                        Modifier.fillMaxSize().then(if (showRoundedFrame) Modifier.clip(frameShape) else Modifier),
                    ) {
                        if (useMacStyleChrome) {
                            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                                EmberMacWindowTitleBar(
                                    isMaximized = windowState.placement == WindowPlacement.Maximized,
                                    onClose = closeWindow,
                                    onMinimize = { windowState.isMinimized = true },
                                    onZoom = {
                                        windowState.placement = if (windowState.placement == WindowPlacement.Maximized) {
                                            WindowPlacement.Floating
                                        } else {
                                            WindowPlacement.Maximized
                                        }
                                    },
                                )
                            }
                        }
                        Box(Modifier.weight(1f).fillMaxSize()) {
                            CompositionLocalProvider(
                                LocalLayoutDirection provides if (language == "fa") LayoutDirection.Rtl else LayoutDirection.Ltr,
                            ) {
                                EmberApp(engine, theme, { theme = it; engine.settings.theme = it },
                                    language, { language = it; engine.settings.language = it }, update, { update = null })
                            }
                        }
                    }
                    if (showRoundedFrame) {
                        Box(
                            Modifier.fillMaxSize().border(
                                EmberDesktopMetrics.windowBorderWidth,
                                MaterialTheme.colorScheme.outlineVariant,
                                frameShape,
                            ),
                        )
                    }
                }
            }
        }
    }
}
