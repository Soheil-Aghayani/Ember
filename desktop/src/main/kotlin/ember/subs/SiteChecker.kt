package ember.subs

import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
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

/**
 * What one server got when asked for a website. [code] null means no answer. A site that does not want the server's
 * address (a region block or a banned address range) answers 403 or 451, so those count as "does not work";
 * anything else that is not a server error (even 401 or 404) means the site was reached.
 */
data class SiteResult(val code: Int?, val ms: Int?) {
    val ok: Boolean get() = code != null && code !in BLOCKED_CODES && code < 500

    private companion object {
        val BLOCKED_CODES = setOf(403, 451)
    }
}

/** Websites people most often ask about, each with an address that answers 403 when the server's address is refused. */
object SitePresets {
    val list: List<Pair<String, String>> = listOf(
        "Spotify" to "https://apresolve.spotify.com/?type=spclient",
        "YouTube" to "https://www.youtube.com/",
        "ChatGPT" to "https://api.openai.com/v1/models",
    )
}

/** A web address from what the user typed ("open.spotify.com" or a full link), or null when it is not one. */
fun normalizeSiteUrl(input: String): String? {
    val text = input.trim()
    if (text.isEmpty()) return null
    val full = if ("://" in text) text else "https://$text"
    val uri = runCatching { URI(full) }.getOrNull() ?: return null
    if (uri.scheme?.lowercase() !in setOf("http", "https")) return null
    val host = uri.host ?: return null
    if ('.' !in host) return null
    return full
}

/**
 * Asks every server for one website, all at the same time through one Xray process (the way [NodeTester] measures
 * speed), so the user can see which servers the site accepts. Must run while Ember is not connected.
 */
class SiteChecker(private val xray: XrayRunner) {

    suspend fun run(
        nodes: List<ProxyNode>,
        url: String,
        onProgress: (String) -> Unit,
        onResult: (ProxyNode, SiteResult) -> Unit,
    ) = withContext(Dispatchers.IO) {
        // OpenVPN servers do not run through Xray, so they cannot be asked this way.
        val candidates = nodes.filterNot { it.isInfo || it.protocol == "openvpn" }
        onProgress("Looking up the servers…")
        val resolved = coroutineScope {
            candidates.map { node -> async { withTimeoutOrNull(6_000) { node to resolveIpv4(node.host) } ?: (node to null) } }.awaitAll()
        }
        resolved.filter { it.second == null }.forEach { (node, _) -> onResult(node, SiteResult(null, null)) }
        val reachable = resolved.mapNotNull { (node, ip) -> ip?.let { node to it } }
        if (reachable.isEmpty()) return@withContext

        val ports = XrayRunner.freePorts(reachable.size)
        val entries = reachable.mapIndexed { i, (node, ip) -> Triple(node, ip, ports[i]) }
        try {
            xray.start(XrayConfig.many(entries, xray.logFile))
            xray.awaitListening(ports.first())
            val semaphore = Semaphore(12)
            var done = 0
            coroutineScope {
                entries.map { (node, _, port) ->
                    async {
                        val result = semaphore.withPermit { ask(port, url) }
                        synchronized(this@SiteChecker) { done++; onProgress("Asking the servers… $done of ${entries.size}") }
                        onResult(node, result)
                    }
                }.awaitAll()
            }
        } finally {
            xray.stop()
        }
    }

    private fun ask(port: Int, url: String): SiteResult {
        val client = OkHttpClient.Builder()
            .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port)))
            .connectTimeout(10, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).callTimeout(14, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false).followRedirects(false)
            .build()
        return try {
            val start = System.nanoTime()
            val request = Request.Builder().url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130 Safari/537.36")
                .build()
            client.newCall(request).execute().use { response ->
                SiteResult(response.code, ((System.nanoTime() - start) / 1_000_000L).toInt())
            }
        } catch (_: Exception) {
            SiteResult(null, null)
        } finally {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }
}
