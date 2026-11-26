package com.soheil.ember

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
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
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
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
    private var releaseUpdateJob: Job? = null
    private var pendingImport: MethodChannel.Result? = null
    private data class PendingBackup(val password: String, val result: MethodChannel.Result)
    private var pendingBackup: PendingBackup? = null
    private val releaseUpdatePreferences by lazy { getSharedPreferences("release_updates", MODE_PRIVATE) }
    private val uiPreferences by lazy { getSharedPreferences("ember_ui", MODE_PRIVATE) }

    override fun onStart() {
        super.onStart()
        checkForReleaseUpdates()
    }

    override fun onStop() {
        releaseUpdateJob?.cancel()
        releaseUpdateJob = null
        super.onStop()
    }

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

    private val createBackup = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val request = pendingBackup.also { pendingBackup = null } ?: return@registerForActivityResult
        if (uri == null) request.result.success(false)
        else runWork(request.result, {
            val data = engine.exportEncryptedBackup(request.password)
            contentResolver.openOutputStream(uri, "w")?.use { it.write(data) } ?: error("The backup file could not be opened.")
            true
        }) { it }
    }

    private val pickBackup = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val request = pendingBackup.also { pendingBackup = null } ?: return@registerForActivityResult
        if (uri == null) request.result.success(false)
        else runWork(request.result, {
            val bytes = contentResolver.openInputStream(uri)?.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8_192)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= 10_000_000) { "The backup is too large." }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            } ?: error("The backup file could not be opened.")
            engine.importEncryptedBackup(bytes, request.password)
            true
        }) { it }
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

    private fun checkForReleaseUpdates() {
        releaseUpdateJob?.cancel()
        releaseUpdateJob = scope.launch {
            while (isActive) {
                val version = withContext(Dispatchers.IO) {
                    ReleaseUpdateChecker.check("android", BuildConfig.VERSION_NAME)
                }
                if (version != null && releaseUpdatePreferences.getString(LAST_UPDATE_NOTICE, null) != version) {
                    releaseUpdatePreferences.edit().putString(LAST_UPDATE_NOTICE, version).apply()
                    engine.announce("update_available:$version")
                }
                delay(UPDATE_CHECK_INTERVAL_MS)
            }
        }
    }

    private val isTv: Boolean
        get() = packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) || BuildConfig.TV_PREVIEW

    private fun updates(): Flow<Unit> = merge(
        VpnState.phase.map { Unit },
        VpnState.progress.map { Unit },
        VpnState.error.map { Unit },
        VpnState.node.map { Unit },
        VpnState.connectedAt.map { Unit },
        VpnState.uploadBytes.map { Unit },
        VpnState.downloadBytes.map { Unit },
        engine.selectedId.map { Unit },
        engine.favorites.map { Unit },
        engine.recentIds.map { Unit },
        engine.profiles.map { Unit },
        engine.testProgress.map { Unit },
        engine.notice.map { Unit },
        engine.language.map { Unit },
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
                "uploadBytes" to VpnState.uploadBytes.value,
                "downloadBytes" to VpnState.downloadBytes.value,
                "node" to connected?.let(::nodeMap),
            ),
            "selectedId" to engine.selectedId.value,
            "selectedNode" to engine.selectedNode()?.let(::nodeMap),
            "favorites" to engine.favorites.value.toList(),
            "recentIds" to engine.recentIds.value,
            "profiles" to engine.profiles.value.map { profile ->
                mapOf(
                    "id" to profile.id,
                    "name" to profile.name,
                    "selectedId" to profile.selectedId,
                    "appMode" to profile.appMode,
                    "appPackages" to profile.appPackages.toList(),
                )
            },
            "testProgress" to engine.testProgress.value,
            "testLimitReason" to engine.testLimitReason(),
            "notice" to engine.notice.value,
            "settings" to mapOf(
                "language" to engine.language.value,
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
                mapOf(
                    "pingMs" to result.pingMs,
                    "mbps" to result.mbps,
                    "jitterMs" to result.jitterMs,
                    "successRate" to result.successRate,
                    "testedAt" to result.testedAtMs,
                )
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
            "getAppearance" -> {
                val savedAppearance = uiPreferences.getString("appearance", "mint") ?: "mint"
                val appearance = if (savedAppearance == "dark") "dark_choco" else savedAppearance
                if (appearance != savedAppearance) {
                    uiPreferences.edit().putString("appearance", appearance).apply()
                }
                result.success(appearance)
            }
            "setAppearance" -> {
                val appearance = call.requiredString("appearance", result) ?: return
                if (appearance !in setOf("system", "strawberry", "dark_choco", "mint", "kitkat")) result.error("invalid_appearance", "Choose a supported appearance.", null)
                else {
                    uiPreferences.edit().putString("appearance", appearance).apply()
                    result.success(null)
                }
            }
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
            "previewRefresh" -> {
                val id = call.requiredString("id", result) ?: return
                runWork(result, { engine.subscriptions.previewRefresh(id) }) { preview ->
                    mapOf("token" to preview.token, "added" to preview.added, "removed" to preview.removed, "unchanged" to preview.unchanged, "total" to preview.total)
                }
            }
            "applyRefreshPreview" -> {
                val token = call.requiredString("token", result) ?: return
                runWork(result, { engine.subscriptions.applyRefreshPreview(token) }) { true }
            }
            "discardRefreshPreview" -> {
                val token = call.requiredString("token", result) ?: return
                engine.subscriptions.discardRefreshPreview(token)
                result.success(true)
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
            "saveProfile" -> {
                val name = call.requiredString("name", result) ?: return
                runCatching { engine.saveProfile(name) }
                    .onSuccess { result.success(mapOf("id" to it.id, "name" to it.name)) }
                    .onFailure { result.error("profile_invalid", it.message ?: "The profile could not be saved.", null) }
            }
            "applyProfile" -> {
                val id = call.requiredString("id", result) ?: return
                if (engine.applyProfile(id)) result.success(true)
                else result.error("profile_missing", "That profile is no longer available.", null)
            }
            "deleteProfile" -> {
                val id = call.requiredString("id", result) ?: return
                engine.deleteProfile(id)
                result.success(true)
            }
            "listApps" -> result.success(installedApps())
            "getQr" -> qrBytes(call.requiredString("id", result), result)
            "copyServerLink" -> copyServerLink(call.requiredString("id", result), result)
            "getLogs" -> result.success(logText())
            "runDiagnostics" -> runWork(result, { diagnostics() }) { it }
            "exportBackup" -> {
                val password = call.requiredString("password", result) ?: return
                if (password.codePointCount(0, password.length) !in 8..128) result.error("backup_password", "Use a backup password between 8 and 128 characters.", null)
                else if (pendingBackup != null) result.error("backup_busy", "A backup file is already being selected.", null)
                else {
                    pendingBackup = PendingBackup(password, result)
                    createBackup.launch("Ember-backup.emberbackup")
                }
            }
            "importBackup" -> {
                val password = call.requiredString("password", result) ?: return
                if (VpnState.phase.value != com.soheil.ember.data.model.ConnectionState.DISCONNECTED) result.error("backup_connected", "Disconnect Ember before restoring a backup.", null)
                else if (pendingBackup != null) result.error("backup_busy", "A backup file is already being selected.", null)
                else {
                    pendingBackup = PendingBackup(password, result)
                    pickBackup.launch(arrayOf("*/*"))
                }
            }
            "openContact" -> {
                openUrl("https://t.me/env_soh")
                result.success(null)
            }
            "openUpdates" -> {
                openUrl(ReleaseUpdateChecker.RELEASE_PAGE)
                result.success(null)
            }
            "openFirefoxVpn" -> result.success(openFirefox())
            "openBatterySettings" -> {
                runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                result.success(null)
            }
            "openAlwaysOnSettings" -> {
                runCatching { startActivity(Intent(Settings.ACTION_VPN_SETTINGS)) }
                    .onFailure { result.error("vpn_settings_unavailable", "Android VPN settings could not be opened.", null) }
                    .onSuccess { result.success(null) }
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

    private suspend fun diagnostics(): Map<String, Any?> {
        val connectivity = getSystemService(ConnectivityManager::class.java)
        val network = connectivity.activeNetwork
        val capabilities = network?.let(connectivity::getNetworkCapabilities)
        val internet = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        val unmetered = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true
        val outsideNetwork = (listOfNotNull(network) + connectivity.allNetworks.filter { it != network })
            .filter { candidate ->
                val candidateCapabilities = connectivity.getNetworkCapabilities(candidate)
                candidateCapabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
                    candidateCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN).not()
            }
            .sortedWith(compareBy<Network>(
                { candidate ->
                    if (connectivity.getNetworkCapabilities(candidate)
                            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
                    ) 0 else 1
                },
                { candidate ->
                val candidateCapabilities = connectivity.getNetworkCapabilities(candidate)
                when {
                    candidateCapabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> 0
                    candidateCapabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> 1
                    candidateCapabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> 2
                    else -> 3
                }
                },
            ))
            .firstOrNull()
        val connected = VpnState.phase.value.name == "CONNECTED"
        val selected = engine.selectedNode()
        val dnsResolved = selected?.let { node ->
            runCatching { InetAddress.getAllByName(node.host).isNotEmpty() }.getOrDefault(false)
        }
        val serverPing = if (!connected && selected != null) engine.diagnoseServer(selected) else null
        val directIp = if (internet) outsideNetwork?.let(::publicIp) else null
        val tunnelIp = if (connected) publicIp(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", 10808))) else null
        val logs = (AppLogger.entries.value.takeLast(30).map { "${it.level} [${it.tag}] ${it.message}" } + engine.xrayOutput().lines())
            .map(::redactDiagnostic)
            .filter(String::isNotBlank)
        val report = buildString {
            appendLine("Ember ${BuildConfig.VERSION_NAME}")
            appendLine("Android API ${Build.VERSION.SDK_INT} (${Build.VERSION.RELEASE})")
            appendLine("Internet: ${if (internet) "available" else "unavailable"}; network: ${if (unmetered) "unmetered" else "metered or unknown"}")
            appendLine("VPN permission: ${if (VpnService.prepare(this@MainActivity) == null) "granted" else "not granted"}")
            appendLine("VPN state: ${VpnState.phase.value.name.lowercase(Locale.ROOT)}")
            appendLine("Selected server DNS: ${if (dnsResolved == true) "resolved" else if (selected == null) "no server selected" else "failed"}")
            appendLine("Server reachability: ${when { connected -> if (tunnelIp != null) "traffic passed through Ember" else "tunnel check failed"; serverPing != null -> "answered in ${serverPing} ms"; selected != null -> "no answer or test unavailable"; else -> "not checked" }}")
            appendLine("IP check: outside Ember ${if (directIp == null) "unavailable" else "available"}; through Ember ${if (tunnelIp == null) "not available" else "available"}; ${if (directIp != null && tunnelIp != null) if (directIp == tunnelIp) "same address" else "different addresses" else "comparison incomplete"}")
            appendLine("--- recent redacted logs ---")
            logs.forEach { appendLine(it) }
        }
        return mapOf("report" to report, "directIp" to directIp, "tunnelIp" to tunnelIp, "dnsResolved" to dnsResolved, "serverPingMs" to serverPing)
    }

    private fun publicIp(proxy: Proxy): String? = runCatching {
        val connection = URL("https://www.cloudflare.com/cdn-cgi/trace").openConnection(proxy)
        connection.connectTimeout = 5_000
        connection.readTimeout = 5_000
        connection.getInputStream().bufferedReader().useLines { lines ->
            lines.firstOrNull { it.startsWith("ip=") }?.removePrefix("ip=")?.trim()?.takeIf { it.isNotBlank() }
        }
    }.getOrNull()

    private fun publicIp(network: Network): String? = runCatching {
        val connection = network.openConnection(URL("https://www.cloudflare.com/cdn-cgi/trace"))
        connection.connectTimeout = 5_000
        connection.readTimeout = 5_000
        connection.getInputStream().bufferedReader().useLines { lines ->
            lines.firstOrNull { it.startsWith("ip=") }?.removePrefix("ip=")?.trim()?.takeIf { it.isNotBlank() }
        }
    }.getOrNull()

    private fun redactDiagnostic(text: String): String = text
        .replace(Regex("(?i)\\b(vless|vmess|trojan|ss|hysteria2|hy2|wireguard|socks5?)://\\S+"), "$1://<removed>")
        .replace(Regex("(?i)\\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\b"), "<id>")
        .replace(Regex("\\b\\d{1,3}(\\.\\d{1,3}){3}\\b"), "<ip>")
        .replace(Regex("(?i)(?<![0-9a-f:])(?:[0-9a-f]{1,4}:){2,7}[0-9a-f]{0,4}(?![0-9a-f:])"), "<ip>")
        .replace(Regex("\\b[A-Za-z0-9_\\-+/=]{32,}\\b"), "<token>")

    private fun openUrl(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    private fun openFirefox(): Boolean {
        val packageName = "org.mozilla.firefox"
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        if (launchIntent != null && runCatching { startActivity(launchIntent) }.isSuccess) {
            return true
        }
        openUrl("https://play.google.com/store/apps/details?id=$packageName")
        return false
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
        private const val LAST_UPDATE_NOTICE = "last_android_update_notice"
        private const val UPDATE_CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L
    }
}
