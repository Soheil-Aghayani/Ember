package com.soheil.ember.engine

import android.content.Context
import android.os.Handler
import android.os.Looper
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

private const val TAG = "AndroidEngine"

const val SOCKS_PORT = 10808

/** What the VPN service is doing, for the screens to show. The service writes it; nothing else does. */
object VpnState {
    val phase = MutableStateFlow(ConnectionState.DISCONNECTED)
    val progress = MutableStateFlow<String?>(null)
    val error = MutableStateFlow<String?>(null)
    val node = MutableStateFlow<ProxyNode?>(null)
    val connectedAt = MutableStateFlow<Long?>(null)
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
    fun testNodes(nodes: List<ProxyNode>) {
        if (VpnState.phase.value != ConnectionState.DISCONNECTED || _testProgress.value != null) return
        _testProgress.value = strings.testStarting
        testJob = scope.launch {
            try {
                nodeTester.run(
                    nodes.filter { it.protocol != "openvpn" },
                    { _testProgress.value = it },
                    { node, result -> subscriptions.recordResult(node, result) },
                    { node, ip -> subscriptions.rememberAddress(node, ip) },
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
