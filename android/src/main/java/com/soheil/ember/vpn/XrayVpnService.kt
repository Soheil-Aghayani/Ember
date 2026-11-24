package com.soheil.ember.vpn

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.soheil.ember.EmberVpnApp
import com.soheil.ember.MainActivity
import com.soheil.ember.R
import com.soheil.ember.data.AppLogger
import com.soheil.ember.widget.ConnectWidget
import com.soheil.ember.data.model.ConnectionState
import com.soheil.ember.engine.AndroidEngine
import com.soheil.ember.engine.SOCKS_PORT
import com.soheil.ember.engine.VpnState
import com.soheil.ember.vpn.tun.HevSocks5Tunnel
import com.soheil.ember.vpn.tun.HevSocks5TunnelConfig
import ember.subs.ProxyNode
import ember.subs.XrayConfig
import ember.subs.pickFailover
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request

private const val TAG = "XrayVpnService"
private const val NOTIFICATION_ID = 7
private const val TCP_BUFFER_BYTES = 524_288
private const val CHECK_EVERY_SECONDS = 15
private const val FAILURES_BEFORE_SWITCH = 3
private const val MAX_FAILOVERS = 3

/** How the apps the user listed are treated (see [AndroidEngine.appMode]). */
object AppMode {
    const val OFF = "off"

    /** The listed apps go straight to the internet; everything else uses the VPN. */
    const val SKIP = "skip"

    /** Only the listed apps use the VPN. */
    const val ONLY = "only"
}

/**
 * The whole phone through one server: Xray carries the traffic as a local SOCKS5 proxy, and hev-socks5-tunnel feeds
 * the phone's traffic from the VPN interface into it. This app is never inside the VPN, so Xray's own connection to
 * the server (a child process of the app) goes out directly instead of looping back into the tunnel. Android itself
 * decides per app what the VPN carries, which is how the Apps setting works.
 */
class XrayVpnService : VpnService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var tun: ParcelFileDescriptor? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISCONNECT -> disconnect()
            else -> connect(intent?.getStringExtra(EXTRA_NODE))
        }
        return START_NOT_STICKY
    }

    private fun connect(nodeId: String?) {
        if (job?.isActive == true) return
        val words = (application as EmberVpnApp).engine.strings
        VpnState.uploadBytes.value = 0L
        VpnState.downloadBytes.value = 0L
        startForegroundNow(words.connecting)
        VpnState.error.value = null
        VpnState.phase.value = ConnectionState.CONNECTING
        VpnState.progress.value = words.connecting
        job = scope.launch {
            try {
                val engine = (application as EmberVpnApp).engine
                var node = (nodeId?.let { engine.subscriptions.nodeById(it) } ?: engine.selectedNode())
                    ?: error(words.errChooseServer)
                if (node.protocol == "openvpn") error(words.errOpenVpn)
                VpnState.node.value = node
                engine.stopTestForConnect()
                startXray(engine, node)
                VpnState.progress.value = words.checkingServer
                if (!passesTraffic()) error(words.errServerNoAnswer)
                VpnState.progress.value = words.startingVpn
                val fd = establishTun(engine) ?: error(words.errVpnDenied)
                tun = fd
                val config = HevSocks5TunnelConfig.write(filesDir, SOCKS_PORT, useMapDns = true, tcpBufferSize = TCP_BUFFER_BYTES)
                if (!HevSocks5Tunnel.start(config, fd.fd)) error(words.errTunnel)
                val startStats = HevSocks5Tunnel.stats()
                VpnState.connectedAt.value = System.currentTimeMillis()
                engine.recordRecent(node.id)
                VpnState.progress.value = null
                VpnState.phase.value = ConnectionState.CONNECTED
                startForegroundNow("${words.connected} · ${node.title}")
                AppLogger.i(TAG, "connected via a ${node.protocol} server")

                // Xray can be running while the server behind it has stopped passing traffic: every few seconds a tiny
                // request goes through it, and when several in a row fail the connection moves to the next-best server.
                val failed = mutableSetOf<String>()
                var switches = 0
                var failures = 0
                var waited = 0
                var sinceNotification = 0
                var sinceWidgetRefresh = 0
                while (isActive) {
                    delay(2_000)
                    if (!engine.xray.isRunning()) error(words.errXrayStopped)
                    val stats = HevSocks5Tunnel.stats()
                    VpnState.uploadBytes.value = (stats.getOrElse(1) { 0L } - startStats.getOrElse(1) { 0L }).coerceAtLeast(0)
                    VpnState.downloadBytes.value = (stats.getOrElse(3) { 0L } - startStats.getOrElse(3) { 0L }).coerceAtLeast(0)
                    sinceNotification += 2
                    if (sinceNotification >= 10) {
                        sinceNotification = 0
                        startForegroundNow("${words.connected} · ${node.title}")
                    }
                    sinceWidgetRefresh += 2
                    if (sinceWidgetRefresh >= 30) {
                        sinceWidgetRefresh = 0
                        ConnectWidget.refresh(this@XrayVpnService)
                    }
                    waited += 2
                    if (!engine.autoFailover.value || waited < CHECK_EVERY_SECONDS) continue
                    waited = 0
                    failures = if (passesTraffic()) 0 else failures + 1
                    if (failures < FAILURES_BEFORE_SWITCH) continue
                    failures = 0
                    failed += node.id
                    val all = engine.subscriptions.subscriptions.value.flatMap { it.nodes }
                    val next = if (switches < MAX_FAILOVERS) {
                        pickFailover(all, node, failed, engine.subscriptions.results.value, engine.favorites.value)
                    } else null
                    if (next == null) error(words.errStoppedPassing)
                    switches++
                    AppLogger.w(TAG, "${node.title} stopped passing traffic; switching to ${next.title}")
                    engine.announce(words.switching(node.title, next.title))
                    startXray(engine, next) // the tunnel stays up; only the server behind the local proxy changes
                    node = next
                    VpnState.node.value = next
                    startForegroundNow("${words.connected} · ${next.title}")
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Throwable) {
                AppLogger.e(TAG, "connecting failed", failure)
                VpnState.error.value = failure.message ?: failure.javaClass.simpleName
                teardown()
                stopSelf()
            }
        }
    }

    private suspend fun startXray(engine: AndroidEngine, node: ProxyNode) {
        val address = engine.resolveAddress(node)
        engine.xray.start(XrayConfig.single(node, address, SOCKS_PORT, null, engine.xray.logFile))
        engine.xray.awaitListening(SOCKS_PORT)
    }

    private fun disconnect() {
        job?.cancel()
        teardown()
        stopSelf()
    }

    /** One small request through Xray: does this server carry traffic at all? */
    private fun passesTraffic(): Boolean = runCatching {
        val client = OkHttpClient.Builder()
            .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", SOCKS_PORT)))
            .connectTimeout(8, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).retryOnConnectionFailure(false).build()
        client.newCall(Request.Builder().url("http://cp.cloudflare.com/generate_204").build()).execute().use { it.code in 200..399 }
    }.getOrDefault(false)

    private fun establishTun(engine: AndroidEngine): ParcelFileDescriptor? {
        val mode = engine.appMode.value
        val apps = engine.appPackages.value.filter { it != packageName }
        return Builder()
            .setSession("Ember")
            .addAddress(HevSocks5TunnelConfig.TUN_ADDRESS, 32)
            .addRoute("0.0.0.0", 0)
            // IPv6 is claimed too, so it cannot go around the tunnel; with no address on the interface it is simply dropped
            .apply { runCatching { addRoute("::", 0) } }
            .addDnsServer(HevSocks5TunnelConfig.MAPDNS_ADDRESS)
            .setMtu(HevSocks5TunnelConfig.TUN_MTU)
            .setBlocking(true)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) runCatching { setMetered(false) }
                if (mode == AppMode.ONLY && apps.isNotEmpty()) {
                    // only these apps use the VPN; this app is not among them, so Xray stays outside it
                    apps.forEach { runCatching { addAllowedApplication(it) }.onFailure { e -> AppLogger.w(TAG, "could not include $it: ${e.message}") } }
                } else {
                    runCatching { addDisallowedApplication(packageName) }
                    if (mode == AppMode.SKIP) {
                        apps.forEach { runCatching { addDisallowedApplication(it) }.onFailure { e -> AppLogger.w(TAG, "could not skip $it: ${e.message}") } }
                    }
                }
            }
            .establish()
    }

    private fun teardown() {
        runCatching { HevSocks5Tunnel.stop() }
        runCatching { tun?.close() }
        tun = null
        runCatching { (application as EmberVpnApp).engine.xray.stop() }
        VpnState.connectedAt.value = null
        VpnState.uploadBytes.value = 0L
        VpnState.downloadBytes.value = 0L
        VpnState.progress.value = null
        VpnState.node.value = null
        VpnState.phase.value = ConnectionState.DISCONNECTED
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onRevoke() = disconnect()

    override fun onDestroy() {
        job?.cancel()
        teardown()
        super.onDestroy()
    }

    private fun startForegroundNow(text: String) {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val disconnect = PendingIntent.getService(
            this, 1, Intent(this, XrayVpnService::class.java).setAction(ACTION_DISCONNECT), PendingIntent.FLAG_IMMUTABLE,
        )
        val notification: Notification = NotificationCompat.Builder(this, EmberVpnApp.VPN_NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Ember")
            .setContentText(text)
            .setSubText("↑ ${formatBytes(VpnState.uploadBytes.value)}  ·  ↓ ${formatBytes(VpnState.downloadBytes.value)}")
            .setSmallIcon(R.drawable.ic_tile_ember)
            .setContentIntent(open)
            .addAction(0, (application as EmberVpnApp).engine.strings.disconnect, disconnect)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun formatBytes(value: Long): String = when {
        value < 1_000 -> "$value B"
        value < 1_000_000 -> String.format(java.util.Locale.ROOT, "%.1f KB", value / 1_000.0)
        value < 1_000_000_000 -> String.format(java.util.Locale.ROOT, "%.1f MB", value / 1_000_000.0)
        else -> String.format(java.util.Locale.ROOT, "%.1f GB", value / 1_000_000_000.0)
    }

    companion object {
        const val ACTION_DISCONNECT = "com.soheil.ember.DISCONNECT"
        private const val EXTRA_NODE = "node"

        /** Starts the VPN for [nodeId] (or the server the user chose). Android's VPN permission must already be granted. */
        fun start(context: Context, nodeId: String? = null) {
            val intent = Intent(context, XrayVpnService::class.java).putExtra(EXTRA_NODE, nodeId)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, XrayVpnService::class.java).setAction(ACTION_DISCONNECT))
        }
    }
}
