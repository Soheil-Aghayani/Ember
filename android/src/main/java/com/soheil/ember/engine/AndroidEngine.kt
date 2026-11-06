package com.soheil.ember.engine

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.soheil.ember.data.AppLogger
import com.soheil.ember.data.model.ConnectionState
import ember.subs.AUTO_NODE
import ember.subs.NodeTester
import ember.subs.ProxyNode
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
class AndroidEngine(context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = Handler(Looper.getMainLooper())
    private val prefs = context.getSharedPreferences("ember", Context.MODE_PRIVATE)

    val xray = XrayAndroid(context)
    val subscriptions = SubscriptionStore(File(context.filesDir, "subscriptions.json"))
    private val nodeTester = NodeTester(xray)

    private val _selectedId = MutableStateFlow(prefs.getString("selected", AUTO_NODE) ?: AUTO_NODE)

    /** The server Connect uses ([AUTO_NODE] for the fastest tested one). */
    val selectedId: StateFlow<String> = _selectedId

    private val _favorites = MutableStateFlow(prefs.getStringSet("favorites", emptySet())?.toSet() ?: emptySet())
    val favorites: StateFlow<Set<String>> = _favorites

    private val _testProgress = MutableStateFlow<String?>(null)

    /** Plain-language step of a running server test, or null when none runs. */
    val testProgress: StateFlow<String?> = _testProgress
    private var testJob: Job? = null

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
        _testProgress.value = "Starting…"
        testJob = scope.launch {
            try {
                nodeTester.run(
                    nodes.filter { it.protocol != "openvpn" },
                    { _testProgress.value = it },
                    { node, result -> subscriptions.recordResult(node, result) },
                    { node, ip -> subscriptions.rememberAddress(node, ip) },
                )
            } catch (failure: Throwable) {
                AppLogger.w(TAG, "the server test failed", failure)
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
                subscriptions.rememberedAddress(node) ?: throw IllegalStateException("Could not look up ${node.host}.", failure)
            }

    /** A running test owns Xray; the VPN service ends it before it starts its own copy. */
    fun stopTestForConnect() {
        if (_testProgress.value != null) cancelTest()
    }
}
