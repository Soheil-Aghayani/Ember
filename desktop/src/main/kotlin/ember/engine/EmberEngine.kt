package ember.engine

import com.soheil.ember.data.AppLogger
import com.soheil.ember.data.model.ConnectionState
import com.soheil.ember.vpn.ExitCheck
import com.soheil.ember.vpn.IpLocationInfo
import ember.subs.AUTO_NODE
import ember.subs.NodeTester
import ember.subs.ProxyNode
import ember.subs.pickFailover
import ember.subs.SiteChecker
import ember.subs.SiteResult
import ember.subs.SubscriptionStore
import ember.subs.XrayConfig
import ember.subs.XrayRunner
import ember.subs.OpenVpnProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

private const val TAG = "EmberEngine"
private const val REFRESH_AFTER_MS = 12 * 60 * 60 * 1000L
private const val REFRESH_CHECK_MS = 3 * 60 * 60 * 1000L

private fun elapsedMsSince(startedAtNanos: Long): Long =
    (System.nanoTime() - startedAtNanos) / 1_000_000L

const val SOCKS_PORT = 10808
private const val MAX_FAILOVERS = 3
const val HTTP_PORT = 10809
const val LOCAL_BIND = "127.0.0.1"

/** A question for the user while an OpenVPN server connects: [askUser] true asks for a user name too, otherwise just a password. */
class CredentialRequest(val serverName: String, val subject: String, val askUser: Boolean, val answer: CompletableDeferred<OpenVpnCredentials?>)

/** Owns the subscription servers, the Xray connection and the whole-device tunnel for the desktop app. */
class EmberEngine(private val dataDir: File) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val settings = DesktopSettings(File(dataDir, "settings.properties"))
    val tunnel = WindowsTunnel(dataDir)
    val subscriptions = SubscriptionStore(File(dataDir, "subscriptions.json"))
    private val xray = XrayRunner(dataDir)
    private val openVpn = OpenVpnRunner(dataDir, tunnel.privileged)
    private val appRouting = AppRoutingRunner(dataDir, tunnel.privileged)
    private val nodeTester = NodeTester(xray)
    private val siteChecker = SiteChecker(xray)

    private val _state = MutableStateFlow(ConnectionState.DISCONNECTED)
    val state: StateFlow<ConnectionState> = _state

    private val _selectedId = MutableStateFlow(settings.selectedNodeId)

    /** The id of the server Connect uses ([AUTO_NODE] for the fastest one). */
    val selectedId: StateFlow<String> = _selectedId

    private val _favorites = MutableStateFlow(settings.favoriteNodes)
    val favorites: StateFlow<Set<String>> = _favorites

    fun toggleFavorite(node: ProxyNode) {
        val updated = if (node.id in _favorites.value) _favorites.value - node.id else _favorites.value + node.id
        settings.favoriteNodes = updated
        _favorites.value = updated
    }

    /**
     * Picks the server Connect uses. While connected (for example from the tray menu) the connection moves to the new
     * server straight away.
     */
    fun chooseServer(id: String) {
        resetFailover()
        settings.selectedNodeId = id
        _selectedId.value = id
        if (_state.value == ConnectionState.DISCONNECTED) return
        val node = selectedNode() ?: return
        val job = connectJob
        scope.launch {
            if (_usingOpenVpn.value) job?.cancelAndJoin() else job?.cancel()
            teardown()
            connect(node)
        }
    }

    private val _credentialRequest = MutableStateFlow<CredentialRequest?>(null)

    /** Set while an OpenVPN server waits for a user name and password (or a key password); answer it to go on. */
    val credentialRequest: StateFlow<CredentialRequest?> = _credentialRequest

    /** Credentials typed during this run, kept in memory only (never written to disk). */
    private val rememberedCredentials = mutableMapOf<String, OpenVpnCredentials>()

    private val _usingOpenVpn = MutableStateFlow(false)
    private var openVpnGuard = false

    private val _appRules = MutableStateFlow(false)

    /** True while the connection that is up (or coming up) decides per program whether to use the VPN (the Apps setting). */
    val appRules: StateFlow<Boolean> = _appRules

    /** True while the connection that is up (or coming up) is an OpenVPN one: OpenVPN routes the traffic itself. */
    val usingOpenVpn: StateFlow<Boolean> = _usingOpenVpn

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError

    private val _connectedAtMs = MutableStateFlow<Long?>(null)
    val connectedAtMs: StateFlow<Long?> = _connectedAtMs

    /** The network the computer is on right now (see [NetworkIdentity]), refreshed every few seconds. */
    private val _network = MutableStateFlow<String?>(null)
    val currentNetwork: StateFlow<String?> = _network

    /** Marks the network the computer is on as trusted, so [DesktopSettings.autoConnectUntrusted] leaves it alone. */
    fun trustCurrentNetwork(): Boolean {
        val id = _network.value ?: NetworkIdentity.current() ?: return false
        settings.trustedNetworks = (settings.trustedNetworks + id).distinct()
        return true
    }

    fun untrustNetwork(id: String) {
        settings.trustedNetworks = settings.trustedNetworks.filter { it != id }
    }

    /** Non-null while the kill switch is holding traffic after a crash or a dead tunnel. */
    private val _blockedNotice = MutableStateFlow<String?>(null)
    val blockedNotice: StateFlow<String?> = _blockedNotice

    /** Plain-language step shown on Home while connecting. */
    private val _progress = MutableStateFlow<String?>(null)
    val progress: StateFlow<String?> = _progress

    /** The server that is connected (or being connected to). */
    private val _connectedNode = MutableStateFlow<ProxyNode?>(null)
    val connectedNode: StateFlow<ProxyNode?> = _connectedNode

    /** What each server answered when asked for [label]'s website (kept until cleared; not saved). */
    data class SiteCheckState(val label: String, val results: Map<String, SiteResult>)

    private val _siteCheck = MutableStateFlow<SiteCheckState?>(null)
    val siteCheck: StateFlow<SiteCheckState?> = _siteCheck

    fun clearSiteCheck() {
        _siteCheck.value = null
    }

    /** Asks every server in [all] for [url] (a website the user wants to use). Only while disconnected, like a speed test. */
    fun checkSite(all: List<ProxyNode>, label: String, url: String) {
        if (_state.value != ConnectionState.DISCONNECTED || _testProgress.value != null) return
        if (!xray.isAvailable) {
            _lastError.value = "The Xray program is missing from this installation."
            return
        }
        _siteCheck.value = SiteCheckState(label, emptyMap())
        _testProgress.value = "Starting…"
        testJob = scope.launch {
            try {
                siteChecker.run(all, url, { _testProgress.value = it }) { node, result ->
                    _siteCheck.value = _siteCheck.value?.let { it.copy(results = it.results + (node.id to result)) }
                }
            } catch (failure: Throwable) {
                AppLogger.w(TAG, "the website check failed", failure)
                _lastError.value = failure.message ?: failure.javaClass.simpleName
            } finally {
                _testProgress.value = null
            }
        }
    }

    /** Plain-language step of a running server test, or null when none runs. */
    private val _testProgress = MutableStateFlow<String?>(null)
    val testProgress: StateFlow<String?> = _testProgress

    /** Result of asking a public service which IP and country the tunnel exits from. */
    data class ExitStatus(val info: IpLocationInfo? = null, val error: String? = null, val checking: Boolean = false)

    private val _exit = MutableStateFlow(ExitStatus())
    val exit: StateFlow<ExitStatus> = _exit

    /** Whether the connection that is up (or coming up) routes the whole device. */
    private val _wholeDevice = MutableStateFlow(false)
    val wholeDevice: StateFlow<Boolean> = _wholeDevice

    private var connectJob: Job? = null
    private var testJob: Job? = null
    private var xrayWatchJob: Job? = null
    private var watchJob: Job? = null
    private var healthJob: Job? = null
    private var failoverCount = 0
    private val failedNodes = mutableSetOf<String>()

    private val _notice = MutableStateFlow<String?>(null)

    /** A short message about something Ember did by itself (switching servers); show it once, then [clearNotice]. */
    val notice: StateFlow<String?> = _notice

    fun clearNotice() {
        _notice.value = null
    }

    /** A connection the user started: the failover budget and the list of servers that just failed start over. */
    private fun resetFailover() {
        failoverCount = 0
        failedNodes.clear()
    }
    private var netWatchJob: Job? = null

    init {
        AppLogger.sink = FileLogSink(File(dataDir, "ember.log"))
        startNetworkTrustWatch()
        runCatching { xray.killStale() }
        startSubscriptionUpkeep()
        val interrupted = tunnel.isBlocked() || tunnel.isStale()
        if (interrupted && settings.wasConnected && selectedNode() != null) {
            // Ember went away while connected (a crash, or a restart of Windows): the kill switch is still holding the
            // internet, so connect again to the same server instead of leaving the computer cut off.
            _blockedNotice.value = "Ember was closed while connected. Reconnecting to keep you protected…"
            resumeAfterInterruption()
        } else if (tunnel.isBlocked()) {
            _blockedNotice.value = "Ember closed while the kill switch was on, so internet access is still blocked."
        } else if (tunnel.isStale()) {
            _blockedNotice.value = "Ember was closed while the tunnel was on (or Windows restarted). Leftover network rules may still block or redirect traffic."
        }
    }

    /**
     * Keeps the subscription links fresh (usage, new or removed servers) and, in "fastest server" mode, measures the
     * servers once at start so the choice is based on real numbers. Runs quietly; failures only go to the log.
     */
    private fun startSubscriptionUpkeep() {
        scope.launch {
            delay(4_000)
            var first = true
            while (true) {
                val now = System.currentTimeMillis()
                for (subscription in subscriptions.subscriptions.value.filter { it.url.isNotBlank() && now - it.updatedAtMs > REFRESH_AFTER_MS }) {
                    runCatching { subscriptions.refresh(subscription.id) }
                        .onFailure { AppLogger.w(TAG, "could not refresh a subscription (${it.javaClass.simpleName})") }
                }
                if (first) {
                    first = false
                    val all = subscriptions.subscriptions.value.flatMap { it.nodes }.filterNot { it.isInfo }
                    val newest = all.mapNotNull { subscriptions.results.value[it.id]?.testedAtMs }.maxOrNull() ?: 0L
                    if (settings.selectedNodeId == AUTO_NODE && all.isNotEmpty() && now - newest > REFRESH_AFTER_MS / 2 &&
                        _state.value == ConnectionState.DISCONNECTED
                    ) {
                        AppLogger.i(TAG, "measuring the servers in the background (fastest-server mode)")
                        testNodes(all)
                    }
                }
                delay(REFRESH_CHECK_MS)
            }
        }
    }

    /** Reconnects after an interruption, retrying while the network comes back (for example after Windows starts). */
    private fun resumeAfterInterruption() {
        scope.launch {
            delay(2_500)
            repeat(6) {
                if (_state.value != ConnectionState.DISCONNECTED) return@launch
                val node = selectedNode() ?: return@launch
                AppLogger.i(TAG, "resuming the connection that was interrupted")
                connect(node)
                while (_state.value == ConnectionState.CONNECTING) delay(500)
                if (_state.value == ConnectionState.CONNECTED) {
                    _blockedNotice.value = null
                    return@launch
                }
                delay(5_000)
            }
            _blockedNotice.value = "Ember could not reconnect by itself. Internet access may still be blocked."
        }
    }

    /** The server Connect would use: the one picked on the Servers page, or the fastest tested one in "fastest" mode. */
    fun selectedNode(): ProxyNode? =
        if (settings.selectedNodeId == AUTO_NODE) subscriptions.bestNode() else subscriptions.nodeById(settings.selectedNodeId)

    /** Connects to the picked server. */
    fun connectSelected() {
        resetFailover()
        val node = selectedNode()
        if (node == null) {
            _lastError.value = "Choose a server first."
            return
        }
        connect(node)
    }

    /** Connects through one server: Xray runs it as a local SOCKS5 proxy, and the whole-device tunnel (when on) sends everything into it. */
    fun connect(node: ProxyNode) {
        if (_state.value != ConnectionState.DISCONNECTED) return
        if (node.protocol == "openvpn") {
            connectOpenVpn(node)
            return
        }
        val wholeDevice = settings.wholeDevice
        val appRules = wholeDevice && settings.appRuleMode != AppRuleMode.OFF && settings.appRuleApps.isNotEmpty()
        if (appRules && !appRouting.isAvailable) {
            _lastError.value = "App rules are not available in this build. Turn them off in Settings."
            return
        }
        if (wholeDevice && !appRules && !tunnel.isAvailable) {
            _lastError.value = "Whole-device mode is not available in this build. Turn it off in Settings to use proxy mode."
            return
        }
        if (!xray.isAvailable) {
            _lastError.value = "The Xray program is missing from this installation."
            return
        }
        _state.value = ConnectionState.CONNECTING
        _wholeDevice.value = wholeDevice && !appRules
        _appRules.value = appRules
        _lastError.value = null
        _connectedNode.value = node
        _progress.value = "Connecting to " + node.title + "..."

        connectJob = scope.launch {
            val startedAtNanos = System.nanoTime()
            try {
                // A running speed test owns the Xray process; stop it first.
                testJob?.let {
                    it.cancel()
                    xray.stop() // ends the test's blocking requests at once
                    it.join()
                }
                val address = runCatching { resolveIpv4(node.host) }
                    .onSuccess { subscriptions.rememberAddress(node, it) }
                    .getOrElse { failure ->
                        val remembered = subscriptions.rememberedAddress(node) ?: throw failure
                        AppLogger.w(TAG, "could not look up the server's name (${failure.javaClass.simpleName}); using its last known address")
                        remembered
                    }
                val skipped = if (wholeDevice) resolveExclusions() else SkipList(emptyList(), emptyMap())
                xray.start(XrayConfig.single(node, address, SOCKS_PORT, if (wholeDevice) null else HTTP_PORT, xray.logFile, settings.diagnosticLog, if (appRules) emptyMap() else skipped.domains))
                xray.awaitListening(SOCKS_PORT, 30_000) // an antivirus scanning xray.exe on first launch can take well over 10 s
                // Before anything on the computer is changed: does this server pass traffic at all?
                _progress.value = "Checking the server..."
                val serverCheck = probe(proxied = true)
                AppLogger.i(TAG, "server check before the tunnel: " + (serverCheck?.let { "$it ms" } ?: "failed"))
                if (serverCheck == null) {
                    error("This server did not answer. Try another server.")
                }
                if (appRules) {
                    // sing-box decides per program; Xray only carries what it sends in.
                    val exe = appRouting.exe!!
                    if (!appRouting.taskReady(exe)) {
                        _progress.value = "Waiting for Windows permission (one time)…"
                        appRouting.installTask(exe)
                    }
                    _progress.value = "Setting up app rules…"
                    appRouting.start(AppRoutingConfig.build(settings.appRuleMode, settings.appRuleApps, SOCKS_PORT, skipped.cidrs, skipped.domains.keys.toList(), appRouting.logFile))
                    startWatchingAppRules()
                } else if (wholeDevice) {
                    tunnel.start(
                        socksPort = SOCKS_PORT,
                        edgeIps = listOf(address),
                        killSwitch = settings.killSwitch,
                        mtu = settings.mtu,
                        tcpBufferSize = settings.tcpBufferBytes,
                        excludeCidrs = skipped.cidrs,
                        ipv6 = settings.ipv6,
                        onProgress = { _progress.value = it },
                    )
                    startWatchingTunnel()
                }
                _connectedAtMs.value = System.currentTimeMillis()
                _progress.value = null
                _state.value = ConnectionState.CONNECTED
                settings.wasConnected = true
                checkExit()
                probeConnection(address, node.port)
                AppLogger.i(TAG, "connected via a ${node.protocol} server (wholeDevice=$wholeDevice) after ${elapsedMsSince(startedAtNanos)} ms")
                watchXray()
                startNetworkWatch()
                startHealthWatch()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Throwable) {
                AppLogger.e(TAG, "connecting failed after ${elapsedMsSince(startedAtNanos)} ms", failure)
                _lastError.value = failure.message ?: failure.javaClass.simpleName
                teardown()
            }
        }
    }

    /**
     * Connects through an installed OpenVPN. OpenVPN adds its own adapter and routes (as the profile says), so Xray, the
     * Wintun tunnel and the kill switch are not involved; a one-time Windows prompt registers the task that starts it.
     */
    private fun connectOpenVpn(node: ProxyNode) {
        val exe = openVpn.executable()
        val profile = OpenVpnProfile.decode(node.raw)?.second
        if (exe == null) {
            _lastError.value = "OpenVPN is not installed. Get it at openvpn.net/community-downloads."
            return
        }
        if (profile == null) {
            _lastError.value = "This OpenVPN server could not be read. Add its file again."
            return
        }
        _state.value = ConnectionState.CONNECTING
        _wholeDevice.value = false
        _usingOpenVpn.value = true
        _lastError.value = null
        _connectedNode.value = node
        _progress.value = "Connecting to " + node.title + "..."
        connectJob = scope.launch {
            val startedAtNanos = System.nanoTime()
            try {
                testJob?.let {
                    it.cancel()
                    xray.stop()
                    it.join()
                }
                var sessionProfile = profile
                if (settings.killSwitch && tunnel.isAvailable) {
                    // Block the real network except the server before OpenVPN starts, so nothing can leak if it drops.
                    _progress.value = "Setting up the kill switch…"
                    val pinned = OpenVpnProfile.remotes(profile)
                        .mapNotNull { host -> runCatching { resolveIpv4(host) }.getOrNull()?.let { host to it } }.toMap()
                    if (pinned.isEmpty()) error("Could not look up the OpenVPN server's address for the kill switch.")
                    sessionProfile = OpenVpnProfile.pinRemotes(profile, pinned)
                    tunnel.startGuard(pinned.values.distinct()) { _progress.value = it }
                    openVpnGuard = true
                }
                if (!openVpn.taskReady(exe)) {
                    _progress.value = "Waiting for Windows permission (one time)…"
                    openVpn.installTask(exe)
                    _progress.value = "Connecting to " + node.title + "..."
                }
                openVpn.session(
                    sessionProfile,
                    askCredentials = { subject, askUser -> askForCredentials(node, subject, askUser) },
                    onProgress = { _progress.value = it },
                    onConnected = {
                        _connectedAtMs.value = System.currentTimeMillis()
                        _progress.value = null
                        _state.value = ConnectionState.CONNECTED
                        // with the kill switch on, a crash or restart of Ember resumes the connection like whole-device mode
                        settings.wasConnected = openVpnGuard
                        AppLogger.i(TAG, "connected via OpenVPN after ${elapsedMsSince(startedAtNanos)} ms")
                        checkExit()
                    },
                )
                if (_state.value == ConnectionState.CONNECTED) _lastError.value = "OpenVPN stopped."
                teardown()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Throwable) {
                AppLogger.e(TAG, "OpenVPN connecting failed after ${elapsedMsSince(startedAtNanos)} ms", failure)
                if (failure is OpenVpnAuthFailed) rememberedCredentials.keys.removeAll { it.startsWith(node.id) }
                _lastError.value = failure.message ?: failure.javaClass.simpleName
                teardown()
            }
        }
    }

    private suspend fun askForCredentials(node: ProxyNode, subject: String, askUser: Boolean): OpenVpnCredentials? {
        val key = node.id + ":" + subject
        rememberedCredentials[key]?.let { return it }
        val request = CredentialRequest(node.title, subject, askUser, CompletableDeferred())
        _credentialRequest.value = request
        val answer = try {
            request.answer.await()
        } finally {
            _credentialRequest.value = null
        }
        return answer?.also { rememberedCredentials[key] = it }
    }

    /** Measures delay and download speed of the servers in [all]. Only while disconnected, or the test would use the tunnel. */
    fun testNodes(all: List<ProxyNode>) {
        // OpenVPN servers cannot be measured through Xray.
        val nodes = all.filter { it.protocol != "openvpn" }
        if (nodes.isEmpty() || _state.value != ConnectionState.DISCONNECTED || _testProgress.value != null) return
        if (!xray.isAvailable) {
            _lastError.value = "The Xray program is missing from this installation."
            return
        }
        _testProgress.value = "Starting…"
        testJob = scope.launch {
            try {
                nodeTester.run(
                    nodes,
                    { _testProgress.value = it },
                    { node, result -> subscriptions.recordResult(node, result) },
                    { node, ip -> subscriptions.rememberAddress(node, ip) },
                )
            } catch (failure: Throwable) {
                AppLogger.w(TAG, "the server test failed", failure)
                _lastError.value = failure.message ?: failure.javaClass.simpleName
            } finally {
                _testProgress.value = null
                subscriptions.saveResults()
            }
        }
    }

    /** Stops a running speed test. */
    fun cancelTest() {
        testJob?.cancel()
        runCatching { xray.stop() }
    }

    fun disconnect() {
        resetFailover()
        settings.wasConnected = false
        val job = connectJob
        // OpenVPN needs a moment to close; the next connection must not start before it has.
        if (_usingOpenVpn.value) {
            scope.launch {
                job?.cancelAndJoin()
                teardown()
            }
        } else {
            job?.cancel()
            scope.launch { teardown() }
        }
    }

    /** Lifts the kill switch after a crash or a dead tunnel. Shows a Windows administrator prompt. */
    fun restoreInternet() {
        scope.launch {
            try {
                tunnel.restore()
                _blockedNotice.value = null
            } catch (failure: Throwable) {
                _lastError.value = failure.message ?: "Could not restore the network."
            }
        }
    }

    fun shutdown() {
        settings.wasConnected = false
        runBlocking {
            connectJob?.cancelAndJoin()
            teardown()
        }
    }

    /** Turns the user's "skip the VPN" list into IPv4 addresses and ranges (domains are looked up now, on the real network). */
    private class SkipList(val cidrs: List<String>, val domains: Map<String, List<String>>)

    private fun resolveExclusions(): SkipList {
        val cidr = Regex("""^(\d{1,3}\.){3}\d{1,3}(/\d{1,2})?$""")
        val domain = Regex("""^[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?(\.[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?)+$""")
        val result = linkedSetOf<String>()
        val names = linkedMapOf<String, List<String>>()
        for (entry in settings.excludedAddresses) {
            when {
                cidr.matches(entry) -> result.add(entry)
                domain.matches(entry) -> runCatching {
                    val ips = InetAddress.getAllByName(entry).filterIsInstance<Inet4Address>().mapNotNull { it.hostAddress }
                    ips.forEach { result.add("$it/32") }
                    if (ips.isNotEmpty()) names[entry.lowercase()] = ips
                }.onFailure { AppLogger.w(TAG, "could not look up $entry for the skip list", it) }
                else -> AppLogger.w(TAG, "ignored an invalid skip-list entry: $entry")
            }
            if (result.size >= 300) break
        }
        return SkipList(result.toList(), names)
    }

    /** Measures download speed and delay through the running tunnel. */
    suspend fun runSpeedTest(): Result<SpeedResult> {
        if (_state.value != ConnectionState.CONNECTED) return Result.failure(IllegalStateException("Connect first, then test the speed."))
        // OpenVPN routes the computer itself: a plain request is what a program gets.
        if (_usingOpenVpn.value) return SpeedTest.run(null)
        val viaXray = SpeedTest.run(SOCKS_PORT)
        if (!_wholeDevice.value && !_appRules.value) return viaXray
        // In whole-device mode the number that matters is what a program on the computer gets (through the tunnel);
        // the Xray-alone number next to it shows whether the server or the tunnel is the limit.
        val viaTunnel = SpeedTest.run(null)
        AppLogger.i(TAG, "speed test: through Xray=${viaXray.getOrNull()?.let { "${it.downloadBitsPerSecond / 1_000_000} Mbit/s" } ?: "failed"}, " +
            "through the tunnel=${viaTunnel.getOrNull()?.let { "${it.downloadBitsPerSecond / 1_000_000} Mbit/s" } ?: "failed"}")
        return viaTunnel.map { it.copy(serverBitsPerSecond = viaXray.getOrNull()?.downloadBitsPerSecond) }
    }

    private fun resolveIpv4(host: String): String =
        InetAddress.getAllByName(host).filterIsInstance<Inet4Address>().firstOrNull()?.hostAddress
            ?: error("$host has no IPv4 address, which this connection needs.")

    private fun checkExit() {
        _exit.value = ExitStatus(checking = true)
        scope.launch {
            delay(1_500)
            val result = if (_usingOpenVpn.value) ExitCheck().fetchDirectIpInfo() else ExitCheck().fetchProxiedIpInfo(SOCKS_PORT)
            _exit.value = ExitStatus(
                info = result.getOrNull(),
                error = result.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName },
            )
        }
    }

    /**
     * Asks Cloudflare for a tiny page twice: through Xray's local port, and the way any program on the computer would
     * (through the tunnel). The two answers say where a broken connection breaks, and are written to the log.
     */
    private fun probeConnection(address: String, port: Int) {
        scope.launch {
            delay(2_500)
            val viaXray = probe(proxied = true)
            val routed = _wholeDevice.value || _appRules.value
            val viaTunnel = if (routed) probe(proxied = false) else null
            AppLogger.i(TAG, "connection check: through Xray=${viaXray ?: "failed"}, through the tunnel=${if (routed) (viaTunnel ?: "failed") else "not used"}")
            if (viaXray == null) {
                val direct = runCatching { java.net.Socket().use { it.connect(java.net.InetSocketAddress(address, port), 4_000) } }
                AppLogger.w(TAG, "plain connection from Ember to the server: " + (direct.exceptionOrNull()?.let { it.javaClass.simpleName + " " + it.message } ?: "reachable"))
                AppLogger.w(TAG, "Xray reported: " + runCatching { xray.logFile.readLines().takeLast(6).joinToString(" | ") }.getOrDefault("(no log)").take(900))
            }
            if (_state.value != ConnectionState.CONNECTED) return@launch
            when {
                viaXray == null -> _lastError.value = "Connected, but this server did not pass any traffic. Try another server."
                (_wholeDevice.value || _appRules.value) && viaTunnel == null ->
                    _lastError.value = "The server works, but your computer's traffic is not getting through the tunnel. See the Logs page."
            }
        }
    }

    /** Milliseconds a small request took, or null when it failed. */
    private fun probe(proxied: Boolean): Long? = runCatching {
        val client = okhttp3.OkHttpClient.Builder()
            .proxy(
                if (proxied) java.net.Proxy(java.net.Proxy.Type.SOCKS, java.net.InetSocketAddress(LOCAL_BIND, SOCKS_PORT))
                else java.net.Proxy.NO_PROXY,
            )
            .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
        val start = System.nanoTime()
        client.newCall(okhttp3.Request.Builder().url("http://cp.cloudflare.com/generate_204").build()).execute().use { response ->
            check(response.code in 200..399) { "HTTP ${response.code}" }
        }
        client.dispatcher.executorService.shutdown()
        (System.nanoTime() - start) / 1_000_000L
    }.onFailure { AppLogger.w(TAG, "connection check (proxied=$proxied) failed: ${it.javaClass.simpleName} ${it.message}") }.getOrNull()

    /**
     * Xray can be running while the server behind it has stopped passing traffic. Every 15 seconds a tiny request goes
     * through it; when three in a row fail the connection moves to the next-best server (if the user allows it).
     */
    private fun startHealthWatch() {
        healthJob?.cancel()
        if (!settings.autoFailover) return
        healthJob = scope.launch {
            delay(20_000) // let the connection settle
            var failures = 0
            var lastTick = System.currentTimeMillis()
            while (true) {
                delay(15_000)
                val now = System.currentTimeMillis()
                val slept = now - lastTick > 45_000 // the computer was asleep: the old numbers mean nothing
                lastTick = now
                if (_state.value != ConnectionState.CONNECTED) return@launch
                if (slept) {
                    failures = 0
                    continue
                }
                failures = if (probe(proxied = true) != null) 0 else failures + 1
                if (failures >= 3) {
                    failOver()
                    return@launch
                }
            }
        }
    }

    private fun failOver() {
        val current = _connectedNode.value ?: return
        failedNodes += current.id
        val all = subscriptions.subscriptions.value.flatMap { it.nodes }
        val next = if (failoverCount < MAX_FAILOVERS) pickFailover(all, current, failedNodes, subscriptions.results.value, _favorites.value) else null
        if (next == null) {
            AppLogger.w(TAG, "${current.title} stopped passing traffic and there is no other server to switch to")
            _lastError.value = "This server stopped passing traffic. Pick another server."
            return
        }
        failoverCount++
        AppLogger.w(TAG, "${current.title} stopped passing traffic; switching to ${next.title}")
        _notice.value = "${current.title} stopped answering. Switching to ${next.title}."
        scope.launch {
            teardown()
            connect(next)
        }
    }

    /** Ends the connection if the Xray process dies. */
    private fun watchXray() {
        xrayWatchJob?.cancel()
        xrayWatchJob = scope.launch {
            while (true) {
                delay(2_000)
                if (!xray.isRunning()) {
                    AppLogger.e(TAG, "Xray stopped; closing the connection")
                    _lastError.value = "The connection program stopped unexpectedly."
                    teardown()
                    return@launch
                }
            }
        }
    }

    /** Ends the connection if sing-box stops. */
    private fun startWatchingAppRules() {
        watchJob?.cancel()
        watchJob = scope.launch {
            while (true) {
                delay(2_000)
                if (!appRouting.running()) {
                    AppLogger.e(TAG, "the app-rules program stopped; closing the connection")
                    _lastError.value = "The app-rules program stopped unexpectedly."
                    teardown()
                    return@launch
                }
            }
        }
    }

    private fun startWatchingTunnel() {
        watchJob?.cancel()
        watchJob = scope.launch {
            while (true) {
                delay(2_000)
                val status = tunnel.status() ?: continue
                if (status.startsWith("BLOCKED") || status.startsWith("ERROR")) {
                    AppLogger.e(TAG, "tunnel helper reports: $status")
                    _blockedNotice.value = status.substringAfter(':').trim()
                    _lastError.value = "The tunnel stopped. " + status.substringAfter(':').trim()
                    teardown(helperAlreadyBlocked = true)
                    return@launch
                }
            }
        }
    }

    /**
     * Watches which network the computer is on. When it joins a network that is not trusted (and the user turned on
     * "connect on untrusted networks"), Ember connects once; it never reconnects on a network the user left on purpose.
     */
    private fun startNetworkTrustWatch() {
        scope.launch {
            var previous: String? = null
            while (true) {
                val id = NetworkIdentity.current()
                _network.value = id
                val joinedNew = id != null && id != previous
                previous = id
                if (joinedNew && settings.autoConnectUntrusted &&
                    id !in settings.trustedNetworks &&
                    _state.value == ConnectionState.DISCONNECTED &&
                    selectedNode() != null
                ) {
                    AppLogger.i(TAG, "joined an untrusted network (${NetworkIdentity.displayName(id!!)}); connecting")
                    connectSelected()
                }
                delay(5_000)
            }
        }
    }

    /**
     * After the computer wakes from sleep or the physical network changes (Wi-Fi switched, cable plugged in) the
     * helper re-points its route to the server; Xray reconnects by itself.
     */
    private fun startNetworkWatch() {
        netWatchJob?.cancel()
        netWatchJob = scope.launch {
            var lastTick = System.currentTimeMillis()
            var lastSignature = networkSignature()
            while (true) {
                delay(2_000)
                val now = System.currentTimeMillis()
                val resumed = now - lastTick > 20_000
                lastTick = now
                val signature = networkSignature()
                val changed = signature.isNotEmpty() && signature != lastSignature
                if (signature.isNotEmpty()) lastSignature = signature
                if (resumed || changed) {
                    AppLogger.i(TAG, if (resumed) "resumed from sleep; refreshing the route" else "network changed; refreshing the route")
                    delay(3_000) // let Windows and the helper settle
                    if (_state.value == ConnectionState.CONNECTED && _wholeDevice.value) tunnel.requestRefresh()
                }
            }
        }
    }

    /** A fingerprint of the physical network: active adapters and their IPv4 addresses (tunnel and virtual switches ignored). */
    private fun networkSignature(): String = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .filter { ni -> listOf("vethernet", "hyper-v", "vmware", "virtualbox", "wintun", "loopback").none { ni.displayName.contains(it, ignoreCase = true) } }
            .map { ni -> ni.name to ni.inetAddresses.toList().filterIsInstance<Inet4Address>().mapNotNull { it.hostAddress } }
            .filter { (_, addresses) -> addresses.none { it.startsWith("10.8.0.") } }
            .sortedBy { it.first }
            .joinToString(";") { (name, addresses) -> name + "=" + addresses.sorted().joinToString(",") }
    }.getOrDefault("")

    /**
     * Stops everything. It runs to the end even when the job that called it is cancelled (the Xray watcher calls it
     * from its own job, and cancelling that job first used to stop the cleanup before the tunnel helper was told to stop).
     */
    private suspend fun teardown(helperAlreadyBlocked: Boolean = false) = withContext(NonCancellable) {
        if (_wholeDevice.value && !helperAlreadyBlocked) {
            runCatching { tunnel.stop() }.onFailure { AppLogger.w(TAG, "stopping the tunnel helper failed", it) }
        }
        if (openVpnGuard) {
            runCatching { tunnel.stop() }.onFailure { AppLogger.w(TAG, "stopping the kill switch failed", it) }
            openVpnGuard = false
        }
        if (_appRules.value) runCatching { appRouting.stop() }.onFailure { AppLogger.w(TAG, "stopping the app-rules program failed", it) }
        runCatching { xray.stop() }
        xrayWatchJob?.cancel()
        xrayWatchJob = null
        healthJob?.cancel()
        healthJob = null
        watchJob?.cancel()
        watchJob = null
        netWatchJob?.cancel()
        netWatchJob = null
        _connectedAtMs.value = null
        _connectedNode.value = null
        _progress.value = null
        _exit.value = ExitStatus()
        _wholeDevice.value = false
        _usingOpenVpn.value = false
        _appRules.value = false
        _state.value = ConnectionState.DISCONNECTED
    }
}
