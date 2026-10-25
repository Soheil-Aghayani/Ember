package ember.engine

import com.soheil.ember.data.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

data class SpeedResult(val downloadBitsPerSecond: Long, val pingMs: Long)

private const val TAG = "SpeedTest"
private const val DOWNLOAD_BYTES = 12_000_000L
private const val BROWSER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"

/** A test file. [ranged] sources are big files of which only the first [DOWNLOAD_BYTES] are requested. */
private data class Source(val name: String, val url: String, val pingUrl: String, val ranged: Boolean)

private val SOURCES = listOf(
    Source("Cloudflare", "https://speed.cloudflare.com/__down?bytes=$DOWNLOAD_BYTES", "https://speed.cloudflare.com/__down?bytes=1", false),
    Source("OVH", "https://proof.ovh.net/files/100Mb.dat", "https://proof.ovh.net/files/100Mb.dat", true),
    Source("Hetzner", "https://speed.hetzner.de/100MB.bin", "https://speed.hetzner.de/100MB.bin", true),
)

/** Measures the tunnel by downloading a test file through Ember's local SOCKS5 proxy (which uses the same upstream connection). */
object SpeedTest {

    suspend fun run(socksPort: Int): Result<SpeedResult> = withContext(Dispatchers.IO) {
        val client = OkHttpClient.Builder()
            .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress(LOCAL_BIND, socksPort)))
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .build()
        try {
            var lastFailure: Throwable? = null
            for (source in SOURCES) {
                val attempt = runCatching { measure(client, source) }
                if (attempt.isSuccess) return@withContext attempt
                lastFailure = attempt.exceptionOrNull()
                AppLogger.w(TAG, "speed test source ${source.name} failed", lastFailure)
            }
            Result.failure(lastFailure ?: IllegalStateException("The speed test failed"))
        } finally {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    private fun request(url: String, ranged: Boolean, range: String): Request =
        Request.Builder()
            .url(url)
            .header("User-Agent", BROWSER_AGENT)
            .header("Accept", "*/*")
            .apply { if (ranged) header("Range", range) }
            .build()

    private fun measure(client: OkHttpClient, source: Source): SpeedResult {
        // The first tiny request opens the connection; the second measures the round trip on it.
        client.newCall(request(source.pingUrl, source.ranged, "bytes=0-0")).execute().use {
            if (!it.isSuccessful) error("${source.name} answered HTTP ${it.code}")
            it.body?.bytes()
        }
        val pingStart = System.nanoTime()
        client.newCall(request(source.pingUrl, source.ranged, "bytes=0-0")).execute().use { it.body?.bytes() }
        val pingMs = (System.nanoTime() - pingStart) / 1_000_000L

        val start = System.nanoTime()
        var received = 0L
        client.newCall(request(source.url, source.ranged, "bytes=0-${DOWNLOAD_BYTES - 1}")).execute().use { response ->
            if (!response.isSuccessful) error("${source.name} answered HTTP ${response.code}")
            val stream = response.body?.source()?.inputStream() ?: error("${source.name} returned no data")
            val buffer = ByteArray(64 * 1024)
            while (received < DOWNLOAD_BYTES) {
                val read = stream.read(buffer)
                if (read < 0) break
                received += read
            }
        }
        val seconds = (System.nanoTime() - start) / 1_000_000_000.0
        if (received < DOWNLOAD_BYTES / 2 || seconds <= 0) error("${source.name} did not finish")
        return SpeedResult((received * 8 / seconds).toLong(), pingMs)
    }
}
