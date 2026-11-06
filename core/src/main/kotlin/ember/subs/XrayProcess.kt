package ember.subs

import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import org.json.JSONObject

/** A running copy of Xray on this computer: Ember's own program on Windows, the one packaged in the app on Android. */
interface XrayProcess {
    val isAvailable: Boolean
    val logFile: File
    fun start(config: JSONObject)
    fun stop()
    fun isRunning(): Boolean

    /** Waits until Xray accepts connections on [port], or fails with what it logged. */
    suspend fun awaitListening(port: Int, timeoutMs: Long = 10_000)
}

/** [count] free local ports. */
fun freePorts(count: Int): List<Int> {
    val sockets = (1..count).map { ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")) }
    return sockets.map { it.localPort }.also { sockets.forEach { s -> s.close() } }
}
