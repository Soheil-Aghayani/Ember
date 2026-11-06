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
import com.soheil.ember.data.AppLogger
import com.soheil.ember.data.model.ConnectionState
import com.soheil.ember.engine.SOCKS_PORT
import com.soheil.ember.engine.VpnState
import com.soheil.ember.vpn.tun.HevSocks5Tunnel
import com.soheil.ember.vpn.tun.HevSocks5TunnelConfig
import ember.subs.XrayConfig
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

/**
 * The whole phone through one server: Xray carries the traffic as a local SOCKS5 proxy, and hev-socks5-tunnel feeds
 * the phone's traffic from the VPN interface into it. This app is excluded from the VPN, so Xray's own connection to
 * the server (a child process of the app) goes out directly instead of looping back into the tunnel.
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
        startForegroundNow("Connecting…")
        VpnState.error.value = null
        VpnState.phase.value = ConnectionState.CONNECTING
        VpnState.progress.value = "Connecting…"
        job = scope.launch {
            try {
                val engine = (application as EmberVpnApp).engine
                val node = (nodeId?.let { engine.subscriptions.nodeById(it) } ?: engine.selectedNode())
                    ?: error("Choose a server first.")
                if (node.protocol == "openvpn") error("OpenVPN servers are not supported on Android yet.")
                VpnState.node.value = node
                engine.stopTestForConnect()
                val address = engine.resolveAddress(node)
                engine.xray.start(XrayConfig.single(node, address, SOCKS_PORT, null, engine.xray.logFile))
                engine.xray.awaitListening(SOCKS_PORT)
                VpnState.progress.value = "Checking the server…"
                if (!passesTraffic()) error("This server did not answer. Try another server.")
                VpnState.progress.value = "Starting the VPN…"
                val fd = establishTun() ?: error("Android did not allow the VPN. Open Ember and try again.")
                tun = fd
                val config = HevSocks5TunnelConfig.write(filesDir, SOCKS_PORT, useMapDns = true, tcpBufferSize = TCP_BUFFER_BYTES)
                if (!HevSocks5Tunnel.start(config, fd.fd)) error("The tunnel program did not start.")
                VpnState.connectedAt.value = System.currentTimeMillis()
                VpnState.progress.value = null
                VpnState.phase.value = ConnectionState.CONNECTED
                startForegroundNow("Connected · ${node.title}")
                AppLogger.i(TAG, "connected via a ${node.protocol} server")
                while (isActive) {
                    delay(2_000)
                    if (!engine.xray.isRunning()) error("The connection program stopped.")
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

    private fun establishTun(): ParcelFileDescriptor? = Builder()
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
            runCatching { addDisallowedApplication(packageName) }
        }
        .establish()

    private fun teardown() {
        runCatching { HevSocks5Tunnel.stop() }
        runCatching { tun?.close() }
        tun = null
        runCatching { (application as EmberVpnApp).engine.xray.stop() }
        VpnState.connectedAt.value = null
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
        val notification: Notification = NotificationCompat.Builder(this, EmberVpnApp.VPN_NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Ember")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(open)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val ACTION_DISCONNECT = "com.soheil.ember.DISCONNECT"
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
