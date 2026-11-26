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
private const val SPEED_TOP = 3

internal data class PingMeasure(val medianMs: Int?, val jitterMs: Int?, val successRate: Double)

internal fun summarizePings(samples: List<Int>): PingMeasure {
    val sorted = samples.sorted()
    val reliable = sorted.size >= 2
    val median = when {
        sorted.size < 2 -> null
        sorted.size % 2 == 1 -> sorted[sorted.size / 2]
        else -> (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2
    }
    return PingMeasure(
        medianMs = median.takeIf { reliable },
        jitterMs = if (reliable) sorted.last() - sorted.first() else null,
        successRate = (sorted.size.coerceAtMost(3)) / 3.0,
    )
}

/** An IPv4 address for [host], or null when it cannot be looked up. */
fun resolveIpv4(host: String): String? = runCatching {
    InetAddress.getAllByName(host).filterIsInstance<Inet4Address>().firstOrNull()?.hostAddress
}.onFailure { AppLogger.w(TAG, "could not look up $host (${it.javaClass.simpleName}: ${it.message})") }.getOrNull()

/**
 * Tests every server of a subscription through one Xray process (one local port per server): first how fast each
 * answers, then the download speed of the quickest ones one after another so they do not share the bandwidth.
 * Must run while Ember is not connected, otherwise the traffic would go through the tunnel.
 */
class NodeTester(private val xray: XrayProcess) {

    @Volatile private var loggedPingFailure = false

    suspend fun run(
        nodes: List<ProxyNode>,
        onProgress: (String) -> Unit,
        onResult: (ProxyNode, NodeResult) -> Unit,
        onAddress: (ProxyNode, String) -> Unit = { _, _ -> },
        measureSpeed: Boolean = true,
    ) = withContext(Dispatchers.IO) {
        loggedPingFailure = false
        val candidates = nodes.filterNot { it.isInfo }
        onProgress("Looking up the servers…")
        val resolved = coroutineScope {
            candidates.map { node ->
                async { withTimeoutOrNull(6_000) { node to resolveIpv4(node.host) } ?: (node to null) }
            }.awaitAll()
        }
        resolved.forEach { (node, ip) -> if (ip != null) onAddress(node, ip) }
        AppLogger.i(TAG, "looked up ${resolved.count { it.second != null }} of ${resolved.size} servers")
        val reachable = resolved.mapNotNull { (node, ip) -> ip?.let { Triple(node, it, 0) } }
        resolved.filter { it.second == null }.forEach { (node, _) -> onResult(node, NodeResult(null, null, System.currentTimeMillis(), successRate = 0.0)) }
        if (reachable.isEmpty()) return@withContext

        val ports = freePorts(reachable.size)
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
                        onResult(node, NodeResult(ping.medianMs, null, System.currentTimeMillis(), ping.jitterMs, ping.successRate))
                        Triple(node, port, ping)
                    }
                }.awaitAll()
            }

            if (!measureSpeed) return@withContext
            val fastest = pings.filter { it.third.medianMs != null }.sortedBy { it.third.medianMs }.take(SPEED_TOP)
            fastest.forEachIndexed { i, (node, port, ping) ->
                onProgress("Measuring speed… ${i + 1} of ${fastest.size}")
                val mbps = speed(port)
                onResult(node, NodeResult(ping.medianMs, mbps, System.currentTimeMillis(), ping.jitterMs, ping.successRate))
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

    /** Median round trip through the server; three samples reduce one-off spikes before Ember picks a server. */
    private fun ping(port: Int): PingMeasure {
        val client = client(port, 7)
        return try {
            val times = (1..3).mapNotNull {
                val start = System.nanoTime()
                runCatching {
                    client.newCall(Request.Builder().url(PING_URL).build()).execute().use { r -> r.body?.bytes(); r.code in 200..399 }
                }.onFailure {
                    if (!loggedPingFailure) {
                        loggedPingFailure = true
                        AppLogger.w(TAG, "a request through a server failed: ${it.javaClass.simpleName}: ${it.message}")
                    }
                }.getOrNull()?.takeIf { ok -> ok }?.let { ((System.nanoTime() - start) / 1_000_000L).toInt() }
            }
            summarizePings(times)
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
