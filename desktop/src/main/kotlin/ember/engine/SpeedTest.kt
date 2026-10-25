package ember.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

data class SpeedResult(val downloadBitsPerSecond: Long, val pingMs: Long)

/** Measures the tunnel by downloading a test file through Ember's local SOCKS5 proxy (which uses the same upstream connection). */
object SpeedTest {
    private const val TEST_URL = "https://speed.cloudflare.com/__down?bytes="
    private const val DOWNLOAD_BYTES = 12_000_000L

    suspend fun run(socksPort: Int): Result<SpeedResult> = withContext(Dispatchers.IO) {
        val client = OkHttpClient.Builder()
            .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress(LOCAL_BIND, socksPort)))
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .build()
        try {
            runCatching {
                // The first tiny request opens the connection; the second measures the round trip on it.
                client.newCall(Request.Builder().url(TEST_URL + "1").build()).execute().use { it.body?.bytes() }
                val pingStart = System.nanoTime()
                client.newCall(Request.Builder().url(TEST_URL + "1").build()).execute().use { it.body?.bytes() }
                val pingMs = (System.nanoTime() - pingStart) / 1_000_000L

                val start = System.nanoTime()
                var received = 0L
                client.newCall(Request.Builder().url(TEST_URL + DOWNLOAD_BYTES).build()).execute().use { response ->
                    if (!response.isSuccessful) error("The speed test server answered HTTP ${response.code}")
                    val source = response.body?.source() ?: error("The speed test returned no data")
                    val buffer = ByteArray(64 * 1024)
                    val stream = source.inputStream()
                    while (true) {
                        val read = stream.read(buffer)
                        if (read < 0) break
                        received += read
                    }
                }
                val seconds = (System.nanoTime() - start) / 1_000_000_000.0
                if (received < DOWNLOAD_BYTES / 2 || seconds <= 0) error("The speed test did not finish")
                SpeedResult((received * 8 / seconds).toLong(), pingMs)
            }
        } finally {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }
}
