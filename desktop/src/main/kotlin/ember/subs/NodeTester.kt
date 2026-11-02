package ember.subs

import com.soheil.ember.data.AppLogger
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

private const val TAG = "NodeTester"
private const val PING_URL = "http://cp.cloudflare.com/generate_204"
private const val SPEED_URL = "https://speed.cloudflare.com/__down?bytes=30000000"
private const val SPEED_SECONDS = 4
private const val PING_PARALLEL = 16
private const val SPEED_TOP = 12

/** An IPv4 address for [host], or null when it cannot be looked up. */
internal fun resolveIpv4(host: String): String? = runCatching {
    InetAddress.getAllByName(host).filterIsInstance<Inet4Address>().firstOrNull()?.hostAddress
}.getOrNull()

/**
 * Tests every server of a subscription through one Xray process (one local port per server): first how fast each
 * answers, then the download speed of the quickest ones one after another so they do not share the bandwidth.
 * Must run while Ember is not connected, otherwise the traffic would go through the tunnel.
 */
class NodeTester(private val xray: XrayRunner) {

    suspend fun run(
        nodes: List<ProxyNode>,
        onProgress: (String) -> Unit,
        onResult: (ProxyNode, NodeResult) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val candidates = nodes.filterNot { it.isInfo }
        onProgress("Looking up the servers…")
        val resolved = coroutineScope {
            candidates.map { node ->
                async { withTimeoutOrNull(6_000) { node to resolveIpv4(node.host) } ?: (node to null) }
            }.awaitAll()
        }
        val reachable = resolved.mapNotNull { (node, ip) -> ip?.let { Triple(node, it, 0) } }
        resolved.filter { it.second == null }.forEach { (node, _) -> onResult(node, NodeResult(null, null, System.currentTimeMillis())) }
        if (reachable.isEmpty()) return@withContext

        val ports = XrayRunner.freePorts(reachable.size)
        val entries = reachable.mapIndexed { i, (node, ip, _) -> Triple(node, ip, ports[i]) }
        try {
            xray.start(XrayConfig.many(entries, xray.logFile))
            xray.awaitListening(ports.first())

            onProgress("Measuring delay…")
            val semaphore = Semaphore(PING_PARALLEL)
            var done = 0
            val pings = coroutineScope {
                entries.map { (node, _, port) ->
                    async {
                        val ping = semaphore.withPermit { ping(port) }
                        synchronized(this@NodeTester) { done++; onProgress("Measuring delay… $done of ${entries.size}") }
                        onResult(node, NodeResult(ping, null, System.currentTimeMillis()))
                        Triple(node, port, ping)
                    }
                }.awaitAll()
            }

            val fastest = pings.filter { it.third != null }.sortedBy { it.third }.take(SPEED_TOP)
            fastest.forEachIndexed { i, (node, port, ping) ->
                onProgress("Measuring speed… ${i + 1} of ${fastest.size}")
                val mbps = speed(port)
                onResult(node, NodeResult(ping, mbps, System.currentTimeMillis()))
            }
        } finally {
            xray.stop()
        }
    }

    private fun client(port: Int, seconds: Long) = OkHttpClient.Builder()
        .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port)))
        .connectTimeout(seconds, TimeUnit.SECONDS)
        .readTimeout(seconds, TimeUnit.SECONDS)
        .callTimeout(seconds + 2, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    /** Round trip through the server in milliseconds: the best of two requests (the first one opens the connection). */
    private fun ping(port: Int): Int? {
        val client = client(port, 7)
        return try {
            val times = (1..2).mapNotNull {
                val start = System.nanoTime()
                runCatching {
                    client.newCall(Request.Builder().url(PING_URL).build()).execute().use { r -> r.body?.bytes(); r.code in 200..399 }
                }.getOrNull()?.takeIf { ok -> ok }?.let { ((System.nanoTime() - start) / 1_000_000L).toInt() }
            }
            times.minOrNull()
        } finally {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    /** Download speed in megabits per second over about [SPEED_SECONDS] seconds, or null when nothing came through. */
    private fun speed(port: Int): Double? {
        val client = client(port, 12)
        return try {
            val request = Request.Builder().url(SPEED_URL).header("User-Agent", "Mozilla/5.0").build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val stream = response.body?.source()?.inputStream() ?: return null
                val buffer = ByteArray(64 * 1024)
                val start = System.nanoTime()
                var received = 0L
                while ((System.nanoTime() - start) < SPEED_SECONDS * 1_000_000_000L) {
                    val n = stream.read(buffer)
                    if (n < 0) break
                    received += n
                }
                val seconds = (System.nanoTime() - start) / 1_000_000_000.0
                if (received < 50_000 || seconds <= 0) null else received * 8 / seconds / 1_000_000.0
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "speed test failed on a server: ${e.javaClass.simpleName}")
            null
        } finally {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }
}
