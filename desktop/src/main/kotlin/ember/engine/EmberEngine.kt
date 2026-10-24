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
import kotlinx.coroutines.withTimeout
import java.io.File
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
class EmberEngine(dataDir: File) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val tokenStore: AuthTokenStore = DesktopTokenStore(File(dataDir, "session.bin"))
    val auth = FxaAuthRepository(tokenStore)
    val servers = ServerListClient(dataDir)
    private val guardian = GuardianClient()

    private val _state = MutableStateFlow(ConnectionState.DISCONNECTED)
    val state: StateFlow<ConnectionState> = _state

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError

    private val _connectedAtMs = MutableStateFlow<Long?>(null)
    val connectedAtMs: StateFlow<Long?> = _connectedAtMs

    @Volatile private var session: H2UpstreamSession? = null
    @Volatile private var target: ProxyCandidate? = null
    private var socks: LocalSocks5Server? = null
    private var http: LocalHttpProxyServer? = null
    private var connectJob: Job? = null
    private var renewJob: Job? = null
    private val redialing = AtomicBoolean(false)

    init {
        NettyLoggingBridge.install()
        UpstreamTrust.init { EmberEngine::class.java.getResourceAsStream("/globalsign_roots.pem") }
    }

    fun connect(candidate: ProxyCandidate?) {
        if (_state.value != ConnectionState.DISCONNECTED) return
        _state.value = ConnectionState.CONNECTING
        _lastError.value = null
        connectJob = scope.launch {
            try {
                val chosen = candidate ?: recommendedCandidate()
                target = chosen
                EdgeAddressResolver.invalidate()
                val pass = mintPass()
                session = dial(chosen, pass)
                startLocalProxies()
                _connectedAtMs.value = System.currentTimeMillis()
                _state.value = ConnectionState.CONNECTED
                AppLogger.i(TAG, "connected via ${chosen.authority}")
                scheduleRenewal(pass)
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

    fun shutdown() {
        connectJob?.cancel()
        teardownBlocking()
    }

    private suspend fun recommendedCandidate(): ProxyCandidate {
        val countries = runCatching { withTimeout(SERVER_LIST_TIMEOUT_MS) { servers.fetchCountries() } }
            .getOrElse { servers.loadCachedCountries() }
        return ServerListClient.candidatesForCountry(countries, RECOMMENDED_COUNTRY_CODE).randomOrNull()
            ?: countries.firstOrNull { it.code.isNotBlank() }
                ?.let { ServerListClient.candidatesForCountry(countries, it.code).randomOrNull() }
            ?: error("No servers are available. Check your connection and try again.")
    }

    private suspend fun mintPass(): ProxyPass {
        val account = auth.ensureFreshAccessToken()
        return try {
            guardian.fetchProxyPass(GUARDIAN_ENDPOINT_DEFAULT, account.accessToken)
        } catch (invalid: TokenInvalidError) {
            AppLogger.w(TAG, "proxy pass rejected; activating the VPN entitlement and retrying", invalid)
            guardian.activateGuardian(GUARDIAN_ENDPOINT_DEFAULT, account.accessToken)
            guardian.fetchProxyPass(GUARDIAN_ENDPOINT_DEFAULT, account.accessToken)
        }
    }

    private suspend fun dial(candidate: ProxyCandidate, pass: ProxyPass): H2UpstreamSession {
        val upstream = H2UpstreamSession(candidate.host, candidate.port, pass.token)
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

    private fun scheduleRenewal(initial: ProxyPass) {
        renewJob?.cancel()
        renewJob = scope.launch {
            var expiry = initial.expiresAtEpochSeconds
            while (true) {
                val waitMs = expiry?.let { (it - RENEW_MARGIN_SECONDS) * 1000L - System.currentTimeMillis() }
                    ?: FALLBACK_PASS_RENEW_MS
                delay(waitMs.coerceAtLeast(30_000L))
                val pass = runCatching { mintPass() }.getOrElse {
                    AppLogger.w(TAG, "could not renew the proxy pass", it)
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
                    val result = runCatching { dial(chosen, mintPass()) }
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

    private fun teardown() = teardownBlocking()

    private fun teardownBlocking() {
        renewJob?.cancel()
        renewJob = null
        runCatching { socks?.stop() }
        runCatching { http?.stop() }
        socks = null
        http = null
        runCatching { session?.close() }
        session = null
        _connectedAtMs.value = null
        _state.value = ConnectionState.DISCONNECTED
    }
}
