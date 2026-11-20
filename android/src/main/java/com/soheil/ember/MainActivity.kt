package com.soheil.ember

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.soheil.ember.data.AppLogger
import com.soheil.ember.engine.AndroidEngine
import com.soheil.ember.engine.VpnState
import com.soheil.ember.ui.requestHomeScreenWidget
import com.soheil.ember.ui.requestQuickSettingsTile
import com.soheil.ember.vpn.XrayVpnService
import ember.subs.AUTO_NODE
import ember.subs.ProxyNode
import ember.subs.normalizeSiteUrl
import ember.subs.shareLink
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import io.flutter.embedding.android.FlutterFragmentActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel

/** Flutter owns Android phone and TV screens; AndroidEngine and XrayVpnService remain the data and VPN owners. */
class MainActivity : FlutterFragmentActivity() {

    private val engine: AndroidEngine get() = (application as EmberVpnApp).engine
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var stateJob: Job? = null
    private var pendingImport: MethodChannel.Result? = null

    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) startVpn()
        else VpnState.error.value = engine.strings.vpnPermission
    }

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val pickFile = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val reply = pendingImport.also { pendingImport = null } ?: return@registerForActivityResult
        if (uri == null) {
            reply.success(null)
        } else {
            val text = readText(uri)
            if (text.isNullOrBlank()) reply.error("file_unreadable", engine.strings.fileUnreadable, null)
            else addSubscription(text, reply)
        }
    }

    private val scanQr = registerForActivityResult(ScanContract()) { scanned ->
        val reply = pendingImport.also { pendingImport = null } ?: return@registerForActivityResult
        if (scanned.contents.isNullOrBlank()) reply.success(null) else addSubscription(scanned.contents, reply)
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, APP_CHANNEL).setMethodCallHandler(::handleCall)
        EventChannel(flutterEngine.dartExecutor.binaryMessenger, STATE_CHANNEL).setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(arguments: Any?, events: EventChannel.EventSink) {
                stateJob?.cancel()
                stateJob = scope.launch {
                    updates().collect {
                        if (VpnState.phase.value.name == "CONNECTED" && !isTv && !engine.tileOffered) {
                            engine.tileOffered = true
                            requestQuickSettingsTile(this@MainActivity)
                        }
                        events.success(snapshot())
                    }
                }
            }

            override fun onCancel(arguments: Any?) {
                stateJob?.cancel()
                stateJob = null
            }
        })
    }

    override fun cleanUpFlutterEngine(flutterEngine: FlutterEngine) {
        stateJob?.cancel()
        stateJob = null
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, APP_CHANNEL).setMethodCallHandler(null)
        EventChannel(flutterEngine.dartExecutor.binaryMessenger, STATE_CHANNEL).setStreamHandler(null)
        super.cleanUpFlutterEngine(flutterEngine)
    }

    override fun onDestroy() {
        scope.coroutineContext[Job]?.cancel()
        super.onDestroy()
    }

    private val isTv: Boolean
        get() = packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) || BuildConfig.TV_PREVIEW

    private fun updates(): Flow<Unit> = merge(
        VpnState.phase.map { Unit },
        VpnState.progress.map { Unit },
        VpnState.error.map { Unit },
        VpnState.node.map { Unit },
        VpnState.connectedAt.map { Unit },
        engine.selectedId.map { Unit },
        engine.favorites.map { Unit },
        engine.testProgress.map { Unit },
        engine.notice.map { Unit },
        engine.language.map { Unit },
        engine.appearance.map { Unit },
        engine.autoFailover.map { Unit },
        engine.appMode.map { Unit },
        engine.appPackages.map { Unit },
        engine.siteCheck.map { Unit },
        engine.subscriptions.subscriptions.map { Unit },
        engine.subscriptions.results.map { Unit },
    ).onStart { emit(Unit) }

    private fun snapshot(): Map<String, Any?> {
        val subscriptions = engine.subscriptions.subscriptions.value
        val connected = VpnState.node.value
        return mapOf(
            "isTv" to isTv,
            "version" to BuildConfig.VERSION_NAME,
            "vpn" to mapOf(
                "phase" to VpnState.phase.value.name.lowercase(Locale.ROOT),
                "progress" to VpnState.progress.value,
                "error" to VpnState.error.value,
                "connectedAt" to VpnState.connectedAt.value,
                "node" to connected?.let(::nodeMap),
            ),
            "selectedId" to engine.selectedId.value,
            "selectedNode" to engine.selectedNode()?.let(::nodeMap),
            "favorites" to engine.favorites.value.toList(),
            "testProgress" to engine.testProgress.value,
            "notice" to engine.notice.value,
            "settings" to mapOf(
                "language" to engine.language.value,
                "appearance" to engine.appearance.value,
                "autoFailover" to engine.autoFailover.value,
                "appMode" to engine.appMode.value,
                "appPackages" to engine.appPackages.value.toList(),
            ),
            "subscriptions" to subscriptions.map { subscription ->
                mapOf(
                    "id" to subscription.id,
                    "name" to subscription.name,
                    // Do not send the saved subscription URL or server credential to the Flutter layer.
                    "canRefresh" to subscription.url.isNotBlank(),
                    "updatedAt" to subscription.updatedAtMs,
                    "usage" to subscription.usage?.let {
                        mapOf("used" to it.used, "total" to it.total, "expiresAt" to it.expiresAtEpochSeconds)
                    },
                    "nodes" to subscription.nodes.filterNot { it.isInfo || it.protocol == "openvpn" }.map(::nodeMap),
                )
            },
            "results" to engine.subscriptions.results.value.mapValues { (_, result) ->
                mapOf("pingMs" to result.pingMs, "mbps" to result.mbps, "testedAt" to result.testedAtMs)
            },
            "siteCheck" to engine.siteCheck.value?.let { check ->
                mapOf(
                    "label" to check.label,
                    "results" to check.results.mapValues { (_, result) -> mapOf("code" to result.code, "ms" to result.ms, "ok" to result.ok) },
                )
            },
        )
    }

    private fun nodeMap(node: ProxyNode): Map<String, Any?> = mapOf(
        "id" to node.id,
        "title" to node.title,
        "protocol" to node.protocol,
        "host" to node.host,
        "port" to node.port,
        "flagCode" to node.flagCode,
        "canShare" to (node.shareLink != null),
    )

    private fun handleCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "getState" -> result.success(snapshot())
            "connect" -> connect(result)
            "disconnect" -> {
                XrayVpnService.stop(this)
                result.success(null)
            }
            "selectServer" -> {
                val id = call.requiredString("id", result) ?: return
                engine.chooseServer(id)
                result.success(null)
            }
            "toggleFavorite" -> {
                val id = call.requiredString("id", result) ?: return
                val node = engine.subscriptions.nodeById(id)
                if (node == null) result.error("server_missing", "That server is no longer in the list.", null)
                else {
                    engine.toggleFavorite(node)
                    result.success(null)
                }
            }
            "testServers" -> {
                engine.testNodes(engine.subscriptions.subscriptions.value.flatMap { it.nodes })
                result.success(null)
            }
            "cancelTest" -> {
                engine.cancelTest()
                result.success(null)
            }
            "addSubscription" -> {
                val text = call.requiredString("text", result) ?: return
                val name = call.argument<String>("name").orEmpty()
                addSubscription(text, name, result)
            }
            "addFromClipboard" -> {
                val clip = getSystemService(ClipboardManager::class.java)?.primaryClip
                val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()?.trim().orEmpty()
                if (text.isEmpty()) result.error("clipboard_empty", engine.strings.clipboardEmpty, null)
                else addSubscription(text, result)
            }
            "pickFile" -> launchImport(result) { pickFile.launch("*/*") }
            "scanQr" -> {
                if (isTv) result.error("camera_unavailable", "QR scanning is not available on Android TV.", null)
                else launchImport(result) {
                    scanQr.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setBeepEnabled(false)
                        .setOrientationLocked(false).setPrompt(engine.strings.qrPrompt))
                }
            }
            "addWarp" -> runWork(result, { engine.subscriptions.addWarp() }) { subscription -> mapOf("added" to subscription.nodes.count { !it.isInfo }) }
            "addWarpThroughServer" -> {
                val carrier = engine.selectedNode()?.takeIf { it.protocol !in setOf("openvpn", "wireguard", "warp") }
                if (carrier == null) result.error("choose_server", engine.strings.chooseOwnServerFirst, null)
                else runWork(result, { engine.subscriptions.addWarpChain(carrier) }) { mapOf("node" to it.title) }
            }
            "refreshSubscription" -> {
                val id = call.requiredString("id", result) ?: return
                runWork(result, { engine.subscriptions.refresh(id) }) { null }
            }
            "removeSubscription" -> {
                val id = call.requiredString("id", result) ?: return
                val subscription = engine.subscriptions.subscriptions.value.firstOrNull { it.id == id }
                if (subscription?.nodes?.any { it.id == engine.selectedId.value } == true) engine.chooseServer(AUTO_NODE)
                engine.subscriptions.remove(id)
                result.success(null)
            }
            "checkSite" -> {
                val input = call.requiredString("url", result) ?: return
                val url = normalizeSiteUrl(input)
                if (url == null) result.error("invalid_url", "Enter a website address.", null)
                else {
                    val host = Uri.parse(url).host?.removePrefix("www.") ?: url
                    engine.checkSite(engine.subscriptions.subscriptions.value.flatMap { it.nodes }, host, url)
                    result.success(null)
                }
            }
            "clearSiteCheck" -> {
                engine.clearSiteCheck()
                result.success(null)
            }
            "clearNotice" -> {
                engine.clearNotice()
                result.success(null)
            }
            "setLanguage" -> {
                val code = call.requiredString("code", result) ?: return
                if (code !in setOf("system", "en", "fa")) result.error("invalid_language", "Choose a supported language.", null)
                else {
                    engine.setLanguage(code)
                    result.success(null)
                }
            }
            "setAppearance" -> {
                val flavour = call.requiredString("flavour", result) ?: return
                if (flavour !in setOf("system", "light", "dark", "mint", "kitkat")) result.error("invalid_appearance", "Choose a supported appearance.", null)
                else {
                    engine.setAppearance(flavour)
                    result.success(null)
                }
            }
            "setAutoFailover" -> {
                val enabled = call.argument<Boolean>("enabled") ?: false
                engine.setAutoFailover(enabled)
                result.success(null)
            }
            "setAppMode" -> {
                val mode = call.requiredString("mode", result) ?: return
                if (mode !in setOf("off", "skip", "only")) result.error("invalid_app_mode", "Choose a supported app mode.", null)
                else {
                    engine.setAppMode(mode)
                    result.success(null)
                }
            }
            "setAppPackages" -> {
                val packages = call.argument<List<String>>("packages").orEmpty().toSet() - packageName
                engine.setAppPackages(packages)
                result.success(null)
            }
            "listApps" -> result.success(installedApps())
            "getQr" -> qrBytes(call.requiredString("id", result), result)
            "copyServerLink" -> copyServerLink(call.requiredString("id", result), result)
            "getLogs" -> result.success(logText())
            "openContact" -> {
                openUrl("https://t.me/env_soh")
                result.success(null)
            }
            "openBatterySettings" -> {
                runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                result.success(null)
            }
            "requestTile" -> result.success(requestQuickSettingsTile(this))
            "requestWidget" -> result.success(requestHomeScreenWidget(this))
            "openAppSettings" -> {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                result.success(null)
            }
            else -> result.notImplemented()
        }
    }

    private fun connect(result: MethodChannel.Result) {
        val prepare = VpnService.prepare(this)
        if (prepare != null) vpnPermission.launch(prepare) else startVpn()
        result.success(null)
    }

    private fun startVpn() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        XrayVpnService.start(this)
    }

    private fun launchImport(result: MethodChannel.Result, launch: () -> Unit) {
        if (pendingImport != null) {
            result.error("import_in_progress", "An import is already open.", null)
            return
        }
        pendingImport = result
        launch()
    }

    private fun readText(uri: Uri): String? = runCatching {
        contentResolver.openInputStream(uri)?.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8_192)
            var total = 0
            while (total < 2_000_000) {
                val count = input.read(buffer, 0, minOf(buffer.size, 2_000_000 - total))
                if (count < 0) break
                output.write(buffer, 0, count)
                total += count
            }
            String(output.toByteArray(), Charsets.UTF_8)
        }
    }.getOrNull()

    private fun addSubscription(text: String, result: MethodChannel.Result) = addSubscription(text, "", result)

    private fun addSubscription(text: String, name: String = "", result: MethodChannel.Result) =
        runWork(result, { engine.subscriptions.add(text, name) }) { subscription ->
            mapOf("added" to subscription.nodes.count { !it.isInfo })
        }

    private fun <T> runWork(result: MethodChannel.Result, block: suspend () -> T, encode: (T) -> Any?) {
        engine.work(block) { outcome ->
            outcome.fold(
                onSuccess = { value -> result.success(encode(value)) },
                onFailure = { failure -> result.error("operation_failed", failure.message ?: "The operation could not be completed.", null) },
            )
        }
    }

    private fun qrBytes(id: String?, result: MethodChannel.Result) {
        val node = id?.let(engine.subscriptions::nodeById)
        val link = node?.shareLink
        if (link == null) {
            result.error("qr_unavailable", engine.strings.qrTooLong, null)
            return
        }
        val bytes = runCatching {
            val matrix = QRCodeWriter().encode(
                link, BarcodeFormat.QR_CODE, 520, 520,
                mapOf(EncodeHintType.MARGIN to 2, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.CHARACTER_SET to "UTF-8"),
            )
            val pixels = IntArray(matrix.width * matrix.height) { index ->
                if (matrix[index % matrix.width, index / matrix.width]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
            }
            val bitmap = Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
            ByteArrayOutputStream().use { output ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
                output.toByteArray()
            }
        }.getOrNull()
        if (bytes == null) result.error("qr_too_long", engine.strings.qrTooLong, null) else result.success(bytes)
    }

    private fun copyServerLink(id: String?, result: MethodChannel.Result) {
        val link = id?.let(engine.subscriptions::nodeById)?.shareLink
        if (link == null) result.error("link_unavailable", "This server has no share link.", null)
        else {
            getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Ember server", link))
            result.success(null)
        }
    }

    private fun installedApps(): List<Map<String, String>> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packageManager.queryIntentActivities(intent, 0)
            .mapNotNull { info ->
                val packageName = info.activityInfo?.packageName ?: return@mapNotNull null
                if (packageName == this.packageName) return@mapNotNull null
                mapOf("packageName" to packageName, "label" to info.loadLabel(packageManager).toString())
            }
            .distinctBy { it["packageName"] }
            .sortedBy { it["label"]?.lowercase(Locale.ROOT) }
    }

    private fun logText(): String {
        val format = SimpleDateFormat("HH:mm:ss", Locale.US)
        val entries = AppLogger.entries.value.takeLast(80).joinToString("\n") {
            "${format.format(Date(it.timestampMillis))} ${it.level} ${it.tag}: ${it.message}"
        }
        return entries + "\n\nXray: " + engine.xrayOutput()
    }

    private fun openUrl(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    private fun MethodCall.requiredString(key: String, result: MethodChannel.Result): String? {
        val value = argument<String>(key)
        if (value.isNullOrBlank()) {
            result.error("invalid_argument", "Missing $key.", null)
            return null
        }
        return value
    }

    companion object {
        private const val APP_CHANNEL = "com.soheil.ember/app"
        private const val STATE_CHANNEL = "com.soheil.ember/state"
    }
}
