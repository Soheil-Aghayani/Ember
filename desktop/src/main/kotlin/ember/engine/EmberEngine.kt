package ember.engine

import com.soheil.ember.data.AppLogger
import com.soheil.ember.data.model.ConnectionState
import com.soheil.ember.vpn.ExitCheck
import com.soheil.ember.vpn.IpLocationInfo
import ember.subs.AUTO_NODE
import ember.subs.NodeTester
import ember.subs.ProxyNode
import ember.subs.SubscriptionStore
import ember.subs.XrayConfig
import ember.subs.XrayRunner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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

private fun elapsedMsSince(startedAtNanos: Long): Long =
    (System.nanoTime() - startedAtNanos) / 1_000_000L

const val SOCKS_PORT = 10808
const val HTTP_PORT = 10809
const val LOCAL_BIND = "127.0.0.1"

/** Owns the subscription servers, the Xray connection and the whole-device tunnel for the desktop app. */
class EmberEngine(private val dataDir: File) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val settings = DesktopSettings(File(dataDir, "settings.properties"))
    val tunnel = WindowsTunnel(dataDir)
    val subscriptions = SubscriptionStore(File(dataDir, "subscriptions.json"))
    private val xray = XrayRunner(dataDir)
    private val nodeTester = NodeTester(xray)

    private val _state = MutableStateFlow(ConnectionState.DISCONNECTED)
    val state: StateFlow<ConnectionState> = _state

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
    private var xrayWatchJob: Job? = null
    private var watchJob: Job? = null
    private var netWatchJob: Job? = null

    init {
        AppLogger.sink = FileLogSink(File(dataDir, "ember.log"))
        startNetworkTrustWatch()
        runCatching { xray.killStale() }
        if (tunnel.isBlocked()) {
            _blockedNotice.value = "Ember closed while the kill switch was on, so internet access is still blocked."
        } else if (tunnel.isStale()) {
            _blockedNotice.value = "Ember was closed while the tunnel was on (or Windows restarted). Leftover network rules may still block or redirect traffic."
        }
    }

    /** The server Connect would use: the one picked on the Servers page, or the fastest tested one in "fastest" mode. */
    fun selectedNode(): ProxyNode? =
        if (settings.selectedNodeId == AUTO_NODE) subscriptions.bestNode() else subscriptions.nodeById(settings.selectedNodeId)

    /** Connects to the picked server. */
    fun connectSelected() {
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
        val wholeDevice = settings.wholeDevice
        if (wholeDevice && !tunnel.isAvailable) {
            _lastError.value = "Whole-device mode is not available in this build. Turn it off in Settings to use proxy mode."
            return
        }
        if (!xray.isAvailable) {
            _lastError.value = "The Xray program is missing from this installation."
            return
        }
        _state.value = ConnectionState.CONNECTING
        _wholeDevice.value = wholeDevice
        _lastError.value = null
        _connectedNode.value = node
        _progress.value = "Connecting to " + node.title + "..."

        connectJob = scope.launch {
            val startedAtNanos = System.nanoTime()
            try {
                val address = runCatching { resolveIpv4(node.host) }
                    .onSuccess { subscriptions.rememberAddress(node, it) }
                    .getOrElse { failure ->
                        val remembered = subscriptions.rememberedAddress(node) ?: throw failure
                        AppLogger.w(TAG, "could not look up the server's name (${failure.javaClass.simpleName}); using its last known address")
                        remembered
                    }
                xray.start(XrayConfig.single(node, address, SOCKS_PORT, if (wholeDevice) null else HTTP_PORT, xray.logFile))
                xray.awaitListening(SOCKS_PORT)
                // Before anything on the computer is changed: does this server pass traffic at all?
                _progress.value = "Checking the server..."
                val serverCheck = probe(proxied = true)
                AppLogger.i(TAG, "server check before the tunnel: " + (serverCheck?.let { "$it ms" } ?: "failed"))
                if (serverCheck == null) {
                    error("This server did not answer. Try another server.")
                }
                if (wholeDevice) {
                    tunnel.start(
                        socksPort = SOCKS_PORT,
                        edgeIps = listOf(address),
                        killSwitch = settings.killSwitch,
                        mtu = settings.mtu,
                        tcpBufferSize = settings.tcpBufferBytes,
                        excludeCidrs = resolveExclusions(),
                        onProgress = { _progress.value = it },
                    )
                    startWatchingTunnel()
                }
                _connectedAtMs.value = System.currentTimeMillis()
                _progress.value = null
                _state.value = ConnectionState.CONNECTED
                checkExit()
                probeConnection(address, node.port)
                AppLogger.i(TAG, "connected via a ${node.protocol} server (wholeDevice=$wholeDevice) after ${elapsedMsSince(startedAtNanos)} ms")
                watchXray()
                startNetworkWatch()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Throwable) {
                AppLogger.e(TAG, "connecting failed after ${elapsedMsSince(startedAtNanos)} ms", failure)
                _lastError.value = failure.message ?: failure.javaClass.simpleName
                teardown()
            }
        }
    }

    /** Measures delay and download speed of the servers in [nodes]. Only while disconnected, or the test would use the tunnel. */
    fun testNodes(nodes: List<ProxyNode>) {
        if (_state.value != ConnectionState.DISCONNECTED || _testProgress.value != null) return
        if (!xray.isAvailable) {
            _lastError.value = "The Xray program is missing from this installation."
            return
        }
        _testProgress.value = "Starting…"
        scope.launch {
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

    fun disconnect() {
        connectJob?.cancel()
        scope.launch { teardown() }
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
        connectJob?.cancel()
        runBlocking { teardown() }
    }

    /** Turns the user's "skip the VPN" list into IPv4 addresses and ranges (domains are looked up now, on the real network). */
    private fun resolveExclusions(): List<String> {
        val cidr = Regex("""^(\d{1,3}\.){3}\d{1,3}(/\d{1,2})?$""")
        val domain = Regex("""^[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?(\.[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?)+$""")
        val result = linkedSetOf<String>()
        for (entry in settings.excludedAddresses) {
            when {
                cidr.matches(entry) -> result.add(entry)
                domain.matches(entry) -> runCatching {
                    InetAddress.getAllByName(entry).filterIsInstance<Inet4Address>().forEach { result.add(it.hostAddress + "/32") }
                }.onFailure { AppLogger.w(TAG, "could not look up $entry for the skip list", it) }
                else -> AppLogger.w(TAG, "ignored an invalid skip-list entry: $entry")
            }
            if (result.size >= 300) break
        }
        return result.toList()
    }

    /** Measures download speed and delay through the running tunnel. */
    suspend fun runSpeedTest(): Result<SpeedResult> =
        if (_state.value == ConnectionState.CONNECTED) SpeedTest.run(SOCKS_PORT)
        else Result.failure(IllegalStateException("Connect first, then test the speed."))

    private fun resolveIpv4(host: String): String =
        InetAddress.getAllByName(host).filterIsInstance<Inet4Address>().firstOrNull()?.hostAddress
            ?: error("$host has no IPv4 address, which this connection needs.")

    private fun checkExit() {
        _exit.value = ExitStatus(checking = true)
        scope.launch {
            delay(1_500)
            val result = ExitCheck().fetchProxiedIpInfo(SOCKS_PORT)
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
            val viaTunnel = if (_wholeDevice.value) probe(proxied = false) else null
            AppLogger.i(TAG, "connection check: through Xray=${viaXray ?: "failed"}, through the tunnel=${if (_wholeDevice.value) (viaTunnel ?: "failed") else "not used"}")
            if (viaXray == null) {
                val direct = runCatching { java.net.Socket().use { it.connect(java.net.InetSocketAddress(address, port), 4_000) } }
                AppLogger.w(TAG, "plain connection from Ember to the server: " + (direct.exceptionOrNull()?.let { it.javaClass.simpleName + " " + it.message } ?: "reachable"))
                AppLogger.w(TAG, "Xray reported: " + runCatching { xray.logFile.readLines().takeLast(6).joinToString(" | ") }.getOrDefault("(no log)").take(900))
            }
            if (_state.value != ConnectionState.CONNECTED) return@launch
            when {
                viaXray == null -> _lastError.value = "Connected, but this server did not pass any traffic. Try another server."
                _wholeDevice.value && viaTunnel == null ->
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
        runCatching { xray.stop() }
        xrayWatchJob?.cancel()
        xrayWatchJob = null
        watchJob?.cancel()
        watchJob = null
        netWatchJob?.cancel()
        netWatchJob = null
        _connectedAtMs.value = null
        _connectedNode.value = null
        _progress.value = null
        _exit.value = ExitStatus()
        _wholeDevice.value = false
        _state.value = ConnectionState.DISCONNECTED
    }
}
