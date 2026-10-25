package ember.engine

import com.soheil.ember.data.AppLogger
import com.soheil.ember.data.AuthTokenStore
import com.soheil.ember.data.FxaAuthRepository
import com.soheil.ember.data.GUARDIAN_ENDPOINT_DEFAULT
import com.soheil.ember.data.GuardianClient
import com.soheil.ember.data.ProxyPass
import com.soheil.ember.data.RECOMMENDED_COUNTRY_CODE
import com.soheil.ember.data.ServerListClient
import com.soheil.ember.data.TokenInvalidError
import com.soheil.ember.data.model.ConnectionState
import com.soheil.ember.data.model.ProxyCandidate
import com.soheil.ember.vpn.ExitCheck
import com.soheil.ember.vpn.IpLocationInfo
import com.soheil.ember.vpn.socks.LocalHttpProxyServer
import com.soheil.ember.vpn.socks.LocalSocks5Server
import com.soheil.ember.vpn.upstream.EdgeAddressResolver
import com.soheil.ember.vpn.upstream.H2UpstreamSession
import com.soheil.ember.vpn.upstream.NettyLoggingBridge
import com.soheil.ember.vpn.upstream.UpstreamTrust
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "EmberEngine"
private const val DIAL_TIMEOUT_MS = 30_000L
private const val SERVER_LIST_TIMEOUT_MS = 10_000L
private const val FALLBACK_PASS_RENEW_MS = 20 * 60_000L
private const val RENEW_MARGIN_SECONDS = 120L
private const val MAX_REDIAL_ATTEMPTS = 5

const val SOCKS_PORT = 10808
const val HTTP_PORT = 10809
const val LOCAL_BIND = "127.0.0.1"

/** Owns the account session, the server list and the tunnel to Mozilla's edge for the desktop app. */
class EmberEngine(private val dataDir: File) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val tokenStore: AuthTokenStore = DesktopTokenStore(File(dataDir, "session.bin"))
    val auth = FxaAuthRepository(tokenStore)
    val servers = ServerListClient(dataDir)
    val settings = DesktopSettings(File(dataDir, "settings.properties"))
    val tunnel = WindowsTunnel(dataDir)
    private val guardian = GuardianClient()

    private val _state = MutableStateFlow(ConnectionState.DISCONNECTED)
    val state: StateFlow<ConnectionState> = _state

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError

    private val _connectedAtMs = MutableStateFlow<Long?>(null)
    val connectedAtMs: StateFlow<Long?> = _connectedAtMs

    /** Non-null while the kill switch is holding traffic after a crash or a dead tunnel. */
    private val _blockedNotice = MutableStateFlow<String?>(null)
    val blockedNotice: StateFlow<String?> = _blockedNotice

    /** Plain-language step shown on Home while connecting. */
    private val _progress = MutableStateFlow<String?>(null)
    val progress: StateFlow<String?> = _progress

    private val _connectedTarget = MutableStateFlow<ProxyCandidate?>(null)
    val connectedTarget: StateFlow<ProxyCandidate?> = _connectedTarget

    private val _quota = MutableStateFlow(settings.quota)
    val quota: StateFlow<QuotaInfo?> = _quota

    /** Result of asking a public service which IP and country the tunnel exits from. */
    data class ExitStatus(val info: IpLocationInfo? = null, val error: String? = null, val checking: Boolean = false)

    private val _exit = MutableStateFlow(ExitStatus())
    val exit: StateFlow<ExitStatus> = _exit

    /** Whether the connection that is up (or coming up) routes the whole device. */
    private val _wholeDevice = MutableStateFlow(false)
    val wholeDevice: StateFlow<Boolean> = _wholeDevice

    @Volatile private var session: H2UpstreamSession? = null
    @Volatile private var target: ProxyCandidate? = null
    @Volatile private var edgeIp: String? = null
    @Volatile private var lastPass: ProxyPass? = null
    private var socks: LocalSocks5Server? = null
    private var http: LocalHttpProxyServer? = null
    private var connectJob: Job? = null
    private var renewJob: Job? = null
    private var watchJob: Job? = null
    private var netWatchJob: Job? = null
    private val redialing = AtomicBoolean(false)

    init {
        AppLogger.sink = FileLogSink(File(dataDir, "ember.log"))
        NettyLoggingBridge.install()
        UpstreamTrust.init { EmberEngine::class.java.getResourceAsStream("/globalsign_roots.pem") }
        if (tunnel.isBlocked()) {
            _blockedNotice.value = "Ember closed while the kill switch was on, so internet access is still blocked."
        }
    }

    fun connect(candidate: ProxyCandidate?) {
        if (_state.value != ConnectionState.DISCONNECTED) return
        val wholeDevice = settings.wholeDevice
        if (wholeDevice && !tunnel.isAvailable) {
            _lastError.value = "Whole-device mode is not available in this build. Turn it off in Settings to use proxy mode."
            return
        }
        _state.value = ConnectionState.CONNECTING
        _wholeDevice.value = wholeDevice
        _lastError.value = null
        _progress.value = "Choosing a server..."

        connectJob = scope.launch {
            try {
                val chosen = candidate ?: recommendedCandidate()
                target = chosen
                EdgeAddressResolver.invalidate()
                _progress.value = "Connecting to " + chosen.countryName.ifBlank { chosen.countryCode } + "..."
                val pass = mintPass()
                edgeIp = if (wholeDevice) resolveIpv4(chosen.host) else null
                session = dial(chosen, pass)
                if (wholeDevice) {
                    val s = LocalSocks5Server(LOCAL_BIND, SOCKS_PORT, true, null, { session }) { onUnhealthy() }
                    s.start()
                    socks = s
                    tunnel.start(SOCKS_PORT, listOf(edgeIp!!), settings.killSwitch, settings.mtu, settings.tcpBufferBytes, onProgress = { _progress.value = it })
                    startWatchingTunnel()
                } else {
                    startLocalProxies()
                }
                _connectedAtMs.value = System.currentTimeMillis()
                _connectedTarget.value = chosen
                _progress.value = null
                _state.value = ConnectionState.CONNECTED
                checkExit()
                AppLogger.i(TAG, "connected via ${chosen.authority} (wholeDevice=$wholeDevice)")
                scheduleRenewal(pass)
                startNetworkWatch()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Throwable) {
                AppLogger.e(TAG, "connect failed", failure)
                _lastError.value = failure.message ?: failure.javaClass.simpleName
                teardown()
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

    private suspend fun recommendedCandidate(): ProxyCandidate {
        val countries = runCatching { withTimeout(SERVER_LIST_TIMEOUT_MS) { servers.fetchCountries() } }
            .getOrElse { servers.loadCachedCountries() }
        return ServerListClient.candidatesForCountry(countries, RECOMMENDED_COUNTRY_CODE).randomOrNull()
            ?: countries.firstOrNull { it.code.isNotBlank() }
                ?.let { ServerListClient.candidatesForCountry(countries, it.code).randomOrNull() }
            ?: error("No servers are available. Check your connection and try again.")
    }

    private fun resolveIpv4(host: String): String =
        InetAddress.getAllByName(host).filterIsInstance<Inet4Address>().firstOrNull()?.hostAddress
            ?: error("$host has no IPv4 address, which whole-device mode needs.")

    private suspend fun mintPass(): ProxyPass {
        val account = auth.ensureFreshAccessToken()
        val pass = try {
            guardian.fetchProxyPass(GUARDIAN_ENDPOINT_DEFAULT, account.accessToken)
        } catch (invalid: TokenInvalidError) {
            AppLogger.w(TAG, "proxy pass rejected; activating the VPN entitlement and retrying", invalid)
            guardian.activateGuardian(GUARDIAN_ENDPOINT_DEFAULT, account.accessToken)
            guardian.fetchProxyPass(GUARDIAN_ENDPOINT_DEFAULT, account.accessToken)
        }
        lastPass = pass
        recordQuota(pass)
        return pass
    }

    private fun recordQuota(pass: ProxyPass) {
        if (pass.quotaRemaining == null && pass.quotaMax == null && pass.quotaReset == null) return
        val info = QuotaInfo(pass.quotaRemaining, pass.quotaMax, pass.quotaReset)
        settings.quota = info
        _quota.value = settings.quota
    }

    /** Fetches fresh quota numbers from the service (also used when the app is disconnected). */
    fun refreshQuota() {
        scope.launch { runCatching { mintPass() }.onFailure { AppLogger.w(TAG, "quota refresh failed", it) } }
    }

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

    /** The last pass if it is still comfortably valid, so a redial does not need the (tunnelled) network. */
    private suspend fun passForRedial(): ProxyPass {
        val cached = lastPass
        val expiry = cached?.expiresAtEpochSeconds
        if (cached != null && expiry != null && expiry - System.currentTimeMillis() / 1000 > 30) return cached
        return mintPass()
    }

    private suspend fun dial(candidate: ProxyCandidate, pass: ProxyPass): H2UpstreamSession {
        val upstream = H2UpstreamSession(candidate.host, candidate.port, pass.token, null, edgeIp, settings.tlsSplitMode)
        try {
            withTimeout(DIAL_TIMEOUT_MS) { upstream.connect() }
        } catch (failure: Throwable) {
            runCatching { upstream.close() }
            throw failure
        }
        return upstream
    }

    private fun startLocalProxies() {
        val s = LocalSocks5Server(LOCAL_BIND, SOCKS_PORT, false, null, { session }) { onUnhealthy() }
        s.start()
        socks = s
        val h = LocalHttpProxyServer(LOCAL_BIND, HTTP_PORT, { session }) { onUnhealthy() }
        h.start()
        http = h
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
     * Reconnects the tunnel to Mozilla after the computer wakes from sleep or the physical network changes
     * (Wi-Fi switched, cable plugged in). In whole-device mode the helper re-points its own routes.
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
                    AppLogger.i(TAG, if (resumed) "resumed from sleep; re-establishing the tunnel" else "network changed; re-establishing the tunnel")
                    delay(3_000) // let Windows and the helper settle
                    onUnhealthy()
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

    private fun scheduleRenewal(initial: ProxyPass) {
        renewJob?.cancel()
        renewJob = scope.launch {
            var expiry = initial.expiresAtEpochSeconds
            while (true) {
                val waitMs = expiry?.let { (it - RENEW_MARGIN_SECONDS) * 1000L - System.currentTimeMillis() }
                    ?: FALLBACK_PASS_RENEW_MS
                delay(waitMs.coerceAtLeast(30_000L))
                val pass = try {
                    mintPass()
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (failure: Throwable) {
                    AppLogger.w(TAG, "could not renew the proxy pass", failure)
                    delay(30_000L)
                    continue
                }
                session?.updateBearerToken(pass.token)
                expiry = pass.expiresAtEpochSeconds
            }
        }
    }

    private fun onUnhealthy() {
        if (_state.value != ConnectionState.CONNECTED) return
        if (!redialing.compareAndSet(false, true)) return
        scope.launch {
            try {
                val chosen = target ?: return@launch
                repeat(MAX_REDIAL_ATTEMPTS) { attempt ->
                    runCatching { session?.close() }
                    val result = runCatching { dial(chosen, passForRedial()) }
                    val fresh = result.getOrNull()
                    if (fresh != null) {
                        session = fresh
                        AppLogger.i(TAG, "re-established the upstream tunnel")
                        return@launch
                    }
                    AppLogger.w(TAG, "redial attempt ${attempt + 1} failed", result.exceptionOrNull())
                    delay(1_000L * (attempt + 1))
                }
                _lastError.value = "The VPN connection was lost and could not be restored."
                teardown()
            } finally {
                redialing.set(false)
            }
        }
    }

    private suspend fun teardown(helperAlreadyBlocked: Boolean = false) {
        renewJob?.cancel()
        renewJob = null
        watchJob?.cancel()
        watchJob = null
        netWatchJob?.cancel()
        netWatchJob = null
        if (_wholeDevice.value && !helperAlreadyBlocked) {
            runCatching { tunnel.stop() }.onFailure { AppLogger.w(TAG, "stopping the tunnel helper failed", it) }
        }
        runCatching { socks?.stop() }
        runCatching { http?.stop() }
        socks = null
        http = null
        runCatching { session?.close() }
        session = null
        edgeIp = null
        _connectedAtMs.value = null
        _connectedTarget.value = null
        _progress.value = null
        _exit.value = ExitStatus()
        _wholeDevice.value = false
        _state.value = ConnectionState.DISCONNECTED
    }
}
