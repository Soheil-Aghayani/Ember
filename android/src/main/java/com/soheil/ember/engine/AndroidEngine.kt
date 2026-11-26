package com.soheil.ember.engine

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.os.BatteryManager
import android.os.PowerManager
import android.net.VpnService
import com.soheil.ember.backup.EncryptedBackup
import com.soheil.ember.data.AppLogger
import com.soheil.ember.data.model.ConnectionState
import com.soheil.ember.ui.Strings
import com.soheil.ember.ui.stringsFor
import ember.subs.AUTO_NODE
import ember.subs.NodeTester
import ember.subs.ProxyNode
import ember.subs.SiteChecker
import ember.subs.SiteResult
import ember.subs.SubscriptionStore
import ember.subs.resolveIpv4
import java.io.File
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

private const val TAG = "AndroidEngine"

const val SOCKS_PORT = 10808

data class UsageProfile(
    val id: String,
    val name: String,
    val selectedId: String,
    val appMode: String,
    val appPackages: Set<String>,
)

/** What the VPN service is doing, for the screens to show. The service writes it; nothing else does. */
object VpnState {
    val phase = MutableStateFlow(ConnectionState.DISCONNECTED)
    val progress = MutableStateFlow<String?>(null)
    val error = MutableStateFlow<String?>(null)
    val node = MutableStateFlow<ProxyNode?>(null)
    val connectedAt = MutableStateFlow<Long?>(null)
    val uploadBytes = MutableStateFlow(0L)
    val downloadBytes = MutableStateFlow(0L)
}

/** The user's servers, what was measured about them, and what they chose; shared by the screens and the VPN service. */
class AndroidEngine(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = Handler(Looper.getMainLooper())
    private val prefs = context.getSharedPreferences("ember", Context.MODE_PRIVATE)

    val xray = XrayAndroid(context)
    val subscriptions = SubscriptionStore(File(context.filesDir, "subscriptions.json"))
    private val nodeTester = NodeTester(xray)
    private val siteChecker = SiteChecker(xray)

    private val _selectedId = MutableStateFlow(prefs.getString("selected", AUTO_NODE) ?: AUTO_NODE)

    /** The server Connect uses ([AUTO_NODE] for the fastest tested one). */
    val selectedId: StateFlow<String> = _selectedId

    private val _favorites = MutableStateFlow(prefs.getStringSet("favorites", emptySet())?.toSet() ?: emptySet())
    val favorites: StateFlow<Set<String>> = _favorites

    private val _recentIds = MutableStateFlow(
        prefs.getString("recentIds", "").orEmpty().split('\n').filter(String::isNotBlank).take(12),
    )
    val recentIds: StateFlow<List<String>> = _recentIds

    private val _profiles = MutableStateFlow(readProfiles())
    val profiles: StateFlow<List<UsageProfile>> = _profiles

    private val _testProgress = MutableStateFlow<String?>(null)

    /** Plain-language step of a running server test, or null when none runs. */
    val testProgress: StateFlow<String?> = _testProgress
    private var testJob: Job? = null

    private val _notice = MutableStateFlow<String?>(null)

    /** Something to tell the user once (shown as a message at the bottom); call [clearNotice] after showing it. */
    val notice: StateFlow<String?> = _notice

    fun clearNotice() {
        _notice.value = null
    }

    /** Tells the user something the app did by itself (switching servers). */
    fun announce(text: String) {
        _notice.value = text
    }

    /** True once the user has been offered the quick-settings tile (after the first connection). */
    var tileOffered: Boolean
        get() = prefs.getBoolean("tileOffered", false)
        set(value) = prefs.edit().putBoolean("tileOffered", value).apply()

    private val _language = MutableStateFlow(prefs.getString("language", "system") ?: "system")

    /** "system" (follow the phone), "en" or "fa". */
    val language: StateFlow<String> = _language

    fun setLanguage(code: String) {
        prefs.edit().putString("language", code).apply()
        _language.value = code
    }

    /** The words for the language in use; the service and this engine write their messages with it. */
    val strings: Strings get() = stringsFor(_language.value)

    private val _autoFailover = MutableStateFlow(prefs.getBoolean("failover", true))

    /** Switch to the next-best server by itself when the connected one stops passing traffic. */
    val autoFailover: StateFlow<Boolean> = _autoFailover

    fun setAutoFailover(on: Boolean) {
        prefs.edit().putBoolean("failover", on).apply()
        _autoFailover.value = on
    }

    private val _appMode = MutableStateFlow(prefs.getString("appMode", "off") ?: "off")

    /** What the list of apps does: "off", "skip" (they bypass the VPN) or "only" (just they use it). */
    val appMode: StateFlow<String> = _appMode

    fun setAppMode(mode: String) {
        prefs.edit().putString("appMode", mode).apply()
        _appMode.value = mode
    }

    private val _appPackages = MutableStateFlow(prefs.getStringSet("appPackages", emptySet())?.toSet() ?: emptySet())
    val appPackages: StateFlow<Set<String>> = _appPackages

    fun setAppPackages(packages: Set<String>) {
        prefs.edit().putStringSet("appPackages", packages).apply()
        _appPackages.value = packages
    }

    /** What each server answered when asked for [label]'s website (kept until cleared; not saved). */
    data class SiteCheckState(val label: String, val results: Map<String, SiteResult>)

    private val _siteCheck = MutableStateFlow<SiteCheckState?>(null)
    val siteCheck: StateFlow<SiteCheckState?> = _siteCheck

    fun clearSiteCheck() {
        _siteCheck.value = null
    }

    /** Asks every server in [all] for [url]. Only while disconnected, like a speed test. */
    fun checkSite(all: List<ProxyNode>, label: String, url: String) {
        if (VpnState.phase.value != ConnectionState.DISCONNECTED || _testProgress.value != null) return
        _siteCheck.value = SiteCheckState(label, emptyMap())
        _testProgress.value = strings.testStarting
        testJob = scope.launch {
            try {
                siteChecker.run(all, url, { _testProgress.value = it }) { node, result ->
                    _siteCheck.value = _siteCheck.value?.let { it.copy(results = it.results + (node.id to result)) }
                }
            } catch (failure: Throwable) {
                AppLogger.w(TAG, "the website check failed", failure)
                _notice.value = strings.checkFailed(failure.message ?: failure.javaClass.simpleName)
            } finally {
                _testProgress.value = null
            }
        }
    }

    fun chooseServer(id: String) {
        prefs.edit().putString("selected", id).apply()
        _selectedId.value = id
        if (id != AUTO_NODE) recordRecent(id)
    }

    fun recordRecent(id: String) {
        val updated = (listOf(id) + _recentIds.value.filterNot { it == id }).take(12)
        prefs.edit().putString("recentIds", updated.joinToString("\n")).apply()
        _recentIds.value = updated
    }

    fun saveProfile(name: String): UsageProfile {
        val clean = name.trim().take(36)
        require(clean.isNotBlank()) { "Enter a name for this profile." }
        require(_profiles.value.none { it.name.equals(clean, ignoreCase = true) }) { "A profile with that name already exists." }
        val profile = UsageProfile(UUID.randomUUID().toString(), clean, _selectedId.value, _appMode.value, _appPackages.value)
        _profiles.value = _profiles.value + profile
        persistProfiles()
        return profile
    }

    fun applyProfile(id: String): Boolean {
        val profile = _profiles.value.firstOrNull { it.id == id } ?: return false
        val serverExists = profile.selectedId == AUTO_NODE || subscriptions.nodeById(profile.selectedId) != null
        val server = if (serverExists) profile.selectedId else AUTO_NODE
        prefs.edit().putString("selected", server).putString("appMode", profile.appMode).putStringSet("appPackages", profile.appPackages).apply()
        _selectedId.value = server
        _appMode.value = profile.appMode
        _appPackages.value = profile.appPackages
        if (server != AUTO_NODE) recordRecent(server)
        return true
    }

    fun deleteProfile(id: String) {
        _profiles.value = _profiles.value.filterNot { it.id == id }
        persistProfiles()
    }

    fun exportEncryptedBackup(password: String): ByteArray {
        val settings = Properties()
        prefs.all.forEach { (key, value) ->
            if (key == "tileOffered") return@forEach
            when (value) {
                is Set<*> -> settings.setProperty(key, value.filterIsInstance<String>().sorted().joinToString(";"))
                is String, is Boolean, is Int, is Long, is Float -> settings.setProperty(key, value.toString())
            }
        }
        settings.setProperty("selectedNodeId", _selectedId.value)
        settings.setProperty("favoriteNodes", _favorites.value.sorted().joinToString(";"))
        settings.setProperty("appRuleMode", _appMode.value)
        val plain = ByteArrayOutputStream().use { bytes ->
            ZipOutputStream(bytes).use { zip ->
                zip.putNextEntry(ZipEntry("settings.properties"))
                settings.store(zip, null)
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("subscriptions.json"))
                zip.write(subscriptions.backupBytes())
                zip.closeEntry()
            }
            bytes.toByteArray()
        }
        return EncryptedBackup.encrypt(plain, password)
    }

    fun importEncryptedBackup(data: ByteArray, password: String) {
        check(VpnState.phase.value == ConnectionState.DISCONNECTED) { "Disconnect Ember before restoring a backup." }
        val plain = EncryptedBackup.decrypt(data, password)
        val entries = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(plain)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                require(!entry.isDirectory && entry.name in setOf("settings.properties", "subscriptions.json")) { "The backup contains an unexpected file." }
                require(entry.name !in entries) { "The backup contains duplicate files." }
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8_192)
                var total = 0
                while (true) {
                    val count = zip.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= 5_000_000) { "The backup is too large." }
                    output.write(buffer, 0, count)
                }
                entries[entry.name] = output.toByteArray()
                zip.closeEntry()
            }
        }
        val settingsBytes = entries["settings.properties"] ?: error("The backup has no settings file.")
        val subscriptionsBytes = entries["subscriptions.json"] ?: error("The backup has no subscriptions file.")
        val properties = Properties().apply { ByteArrayInputStream(settingsBytes).use(::load) }
        val restoredProfiles = properties.getProperty("profiles", "[]")
        JSONArray(restoredProfiles)

        val localTileOffered = prefs.getBoolean("tileOffered", false)
        val requestedSelected = properties.getProperty("selected", properties.getProperty("selectedNodeId", AUTO_NODE))
        val favoriteNodes = properties.getProperty("favorites", properties.getProperty("favoriteNodes", ""))
            .split(';').filter(String::isNotBlank).toSet()
        val mode = properties.getProperty("appMode", properties.getProperty("appRuleMode", "off"))
            .takeIf { it in setOf("off", "skip", "only") } ?: "off"
        val packages = properties.getProperty("appPackages", "").split(';').filter(String::isNotBlank).toSet()
        val restoredRecent = properties.getProperty("recentIds", "").split('\n').filter(String::isNotBlank).take(12)
        val editor = prefs.edit().clear().putBoolean("tileOffered", localTileOffered)
        properties.stringPropertyNames().forEach { key ->
            val value = properties.getProperty(key)
            when (key) {
                "favorites", "favoriteNodes", "appPackages" -> Unit
                "selected", "selectedNodeId", "appRuleMode" -> Unit
                "failover", "autoFailover" -> editor.putBoolean(key, value.equals("true", ignoreCase = true))
                else -> editor.putString(key, value)
            }
        }
        editor.putStringSet("favorites", favoriteNodes)
            .putString("appMode", mode)
            .putStringSet("appPackages", packages)
            .putString("recentIds", restoredRecent.joinToString("\n"))
            .putString("profiles", restoredProfiles)
            .putBoolean("failover", properties.getProperty("failover", properties.getProperty("autoFailover", "true")).toBoolean())
        val oldSubscriptions = subscriptions.backupBytes()
        subscriptions.restoreBackupBytes(subscriptionsBytes)
        val selected = requestedSelected.takeIf { it == AUTO_NODE || subscriptions.nodeById(it) != null } ?: AUTO_NODE
        val validFavorites = favoriteNodes.filterTo(mutableSetOf()) { subscriptions.nodeById(it) != null }
        val validRecent = restoredRecent.filter { subscriptions.nodeById(it) != null }
        editor.putString("selected", selected)
            .putStringSet("favorites", validFavorites)
            .putString("recentIds", validRecent.joinToString("\n"))
        if (!editor.commit()) {
            subscriptions.restoreBackupBytes(oldSubscriptions)
            error("The backup settings could not be saved; the previous server list was restored.")
        }
        _selectedId.value = selected
        _favorites.value = validFavorites
        _recentIds.value = validRecent
        _profiles.value = readProfiles()
        _appMode.value = mode
        _appPackages.value = packages
        _autoFailover.value = prefs.getBoolean("failover", true)
        _language.value = prefs.getString("language", "system") ?: "system"
    }

    private fun readProfiles(): List<UsageProfile> = runCatching {
        val array = JSONArray(prefs.getString("profiles", "[]"))
        (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optString("id").takeIf(String::isNotBlank) ?: return@mapNotNull null
            val name = item.optString("name").takeIf(String::isNotBlank) ?: return@mapNotNull null
            val packages = item.optJSONArray("appPackages") ?: JSONArray()
            UsageProfile(
                id = id,
                name = name,
                selectedId = item.optString("selectedId", AUTO_NODE),
                appMode = item.optString("appMode", "off").takeIf { it in setOf("off", "skip", "only") } ?: "off",
                appPackages = (0 until packages.length()).mapNotNull { packages.optString(it).takeIf(String::isNotBlank) }.toSet(),
            )
        }
    }.getOrDefault(emptyList())

    private fun persistProfiles() {
        val array = JSONArray()
        _profiles.value.forEach { profile ->
            array.put(JSONObject()
                .put("id", profile.id)
                .put("name", profile.name)
                .put("selectedId", profile.selectedId)
                .put("appMode", profile.appMode)
                .put("appPackages", JSONArray(profile.appPackages.toList())))
        }
        prefs.edit().putString("profiles", array.toString()).apply()
    }

    fun selectedNode(): ProxyNode? =
        if (_selectedId.value == AUTO_NODE) subscriptions.bestNode() else subscriptions.nodeById(_selectedId.value)

    fun toggleFavorite(node: ProxyNode) {
        val updated = if (node.id in _favorites.value) _favorites.value - node.id else _favorites.value + node.id
        prefs.edit().putStringSet("favorites", updated).apply()
        _favorites.value = updated
    }

    /** Runs [block] so it finishes even if the screen that asked is left; [done] is called on the main thread. */
    fun <T> work(block: suspend () -> T, done: (Result<T>) -> Unit) {
        scope.launch {
            val result = try {
                Result.success(block())
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } catch (failure: Throwable) {
                Result.failure(failure)
            }
            main.post { done(result) }
        }
    }

    /** Measures delay and speed of [nodes]. Only while disconnected, or the test would go through the VPN. */
    fun testNodes(nodes: List<ProxyNode>, measureSpeed: Boolean = true) {
        if (VpnState.phase.value != ConnectionState.DISCONNECTED || _testProgress.value != null) return
        val limit = testLimitReason()
        _testProgress.value = strings.testStarting
        testJob = scope.launch {
            try {
                nodeTester.run(
                    nodes.filter { it.protocol != "openvpn" },
                    { _testProgress.value = it },
                    { node, result -> subscriptions.recordResult(node, result) },
                    { node, ip -> subscriptions.rememberAddress(node, ip) },
                    measureSpeed = measureSpeed && limit == null,
                )
                val answered = nodes.count { subscriptions.results.value[it.id]?.pingMs != null }
                if (answered == 0) {
                    AppLogger.w(TAG, "no server answered; Xray said: ${xrayOutput()}")
                    _notice.value = strings.noServerAnswered
                }
            } catch (failure: Throwable) {
                AppLogger.w(TAG, "the server test failed", failure)
                _notice.value = strings.testFailed(failure.message ?: failure.javaClass.simpleName)
            } finally {
                _testProgress.value = null
                subscriptions.saveResults()
            }
        }
    }

    /** A one-server delay probe for the opt-in diagnostics report; never runs alongside a VPN or another test. */
    suspend fun diagnoseServer(node: ProxyNode): Int? {
        if (VpnState.phase.value != ConnectionState.DISCONNECTED || _testProgress.value != null) return null
        var ping: Int? = null
        nodeTester.run(
            listOf(node),
            onProgress = {},
            onResult = { tested, result ->
                subscriptions.recordResult(tested, result)
                ping = result.pingMs
            },
            onAddress = subscriptions::rememberAddress,
            measureSpeed = false,
        )
        subscriptions.saveResults()
        return ping
    }

    /** A full download test uses more battery and data; on battery saver, low charge, or metered networks, test delay only. */
    fun testLimitReason(): String? {
        val battery = runCatching {
            context.getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        }.getOrDefault(-1)
        val powerSaver = runCatching { context.getSystemService(PowerManager::class.java).isPowerSaveMode }.getOrDefault(false)
        val metered = runCatching {
            val manager = context.getSystemService(ConnectivityManager::class.java)
            val capabilities = manager.getNetworkCapabilities(manager.activeNetwork)
            capabilities == null || !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        }.getOrDefault(true)
        return when {
            powerSaver -> "battery_saver"
            battery in 1..24 -> "low_battery"
            metered -> "metered_network"
            else -> null
        }
    }

    fun cancelTest() {
        testJob?.cancel()
        runCatching { xray.stop() }
    }

    /** The server's IPv4 address, from a lookup or (when that fails) the one remembered from earlier. */
    fun resolveAddress(node: ProxyNode): String =
        runCatching { resolveIpv4(node.host) ?: error("no address") }
            .onSuccess { subscriptions.rememberAddress(node, it) }
            .getOrElse { failure ->
                subscriptions.rememberedAddress(node) ?: throw IllegalStateException(strings.lookupFailed(node.host), failure)
            }

    /** The last lines Xray wrote, for the logs. */
    fun xrayOutput(): String =
        runCatching { File(context.filesDir, "xray/xray.out").readLines().takeLast(8).joinToString(" | ") }.getOrDefault("(nothing)").take(700)

    /** A running test owns Xray; the VPN service ends it before it starts its own copy. */
    fun stopTestForConnect() {
        if (_testProgress.value != null) cancelTest()
    }
}
