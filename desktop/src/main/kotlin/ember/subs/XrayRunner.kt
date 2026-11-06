package ember.subs

import com.soheil.ember.data.AppLogger
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.WinBase
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinError
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

private const val TAG = "Xray"
private const val CREATE_NO_WINDOW = 0x08000000

/** Builds Xray configurations for one connection or for testing many servers at once. */
object XrayConfig {

    /** [verbose] logs why connections fail but also names the sites visited, so it is only for hunting a problem. */
    private fun base(logFile: File, verbose: Boolean = false) =
        JSONObject().put("log", JSONObject().put("loglevel", if (verbose) "info" else "warning").put("error", logFile.absolutePath))

    private fun socksInbound(tag: String, port: Int) = JSONObject()
        .put("tag", tag).put("listen", "127.0.0.1").put("port", port).put("protocol", "socks")
        .put("settings", JSONObject().put("auth", "noauth").put("udp", true).put("ip", "127.0.0.1"))

    /** One server behind a local SOCKS5 port (and a local HTTP proxy when [httpPort] is given). */
    /**
     * One server behind a local SOCKS5 port (and a local HTTP proxy when [httpPort] is given). [bypass] maps hostnames
     * the user wants to reach without the VPN to the IPv4 addresses Ember looked up for them: Xray sends exactly those
     * names straight out. (Whole-device mode hands applications made-up addresses for every name, so a route to a
     * website's real address is never used by them; the decision has to be made here, where the name is still known.)
     */
    fun single(
        node: ProxyNode, address: String, socksPort: Int, httpPort: Int?, logFile: File,
        verbose: Boolean = false, bypass: Map<String, List<String>> = emptyMap(),
    ): JSONObject {
        val inbounds = JSONArray().put(socksInbound("in", socksPort))
        if (httpPort != null) {
            inbounds.put(JSONObject().put("tag", "http").put("listen", "127.0.0.1").put("port", httpPort).put("protocol", "http"))
        }
        // QUIC (UDP 443) is dropped here: many servers carry no UDP, and a browser then waits for a QUIC attempt that
        // never answers before it falls back to TCP. Dropping it makes sites use TCP straight away.
        val outbounds = JSONArray()
        ShareLinks.outbounds(node.raw, address, "proxy").forEach { outbounds.put(it) }
        outbounds.put(JSONObject().put("tag", "block").put("protocol", "blackhole"))
        val rules = JSONArray()
        val config = base(logFile, verbose)
        if (bypass.isNotEmpty()) {
            outbounds.put(JSONObject().put("tag", "direct").put("protocol", "freedom")
                .put("settings", JSONObject().put("domainStrategy", "UseIPv4")))
            val hosts = JSONObject()
            for ((name, ips) in bypass) hosts.put("full:$name", JSONArray(ips))
            // Only exact names are routed directly, and each one has its addresses here, so Xray never has to ask a
            // resolver (which would hand back a made-up address and loop through the tunnel).
            config.put("dns", JSONObject().put("hosts", hosts).put("servers", JSONArray().put("localhost")))
            rules.put(JSONObject().put("type", "field").put("domain", JSONArray(bypass.keys.map { "full:$it" })).put("outboundTag", "direct"))
        }
        rules.put(JSONObject().put("type", "field").put("network", "udp").put("port", "443").put("outboundTag", "block"))
        return config.put("inbounds", inbounds).put("outbounds", outbounds)
            .put("routing", JSONObject().put("domainStrategy", "AsIs").put("rules", rules))
    }

    /** Each server gets its own local SOCKS5 port, so all of them can be tested at the same time. */
    fun many(entries: List<Triple<ProxyNode, String, Int>>, logFile: File): JSONObject {
        val inbounds = JSONArray()
        val outbounds = JSONArray()
        val rules = JSONArray()
        entries.forEachIndexed { i, (node, address, port) ->
            inbounds.put(socksInbound("in$i", port))
            ShareLinks.outbounds(node.raw, address, "out$i").forEach { outbounds.put(it) }
            rules.put(JSONObject().put("type", "field").put("inboundTag", JSONArray().put("in$i")).put("outboundTag", "out$i"))
        }
        return base(logFile).put("inbounds", inbounds).put("outbounds", outbounds)
            .put("routing", JSONObject().put("domainStrategy", "AsIs").put("rules", rules))
    }
}

/** Runs Ember's own copy of xray.exe without a console window and stops it again. */
class XrayRunner(dataDir: File) {

    private val resourcesDir = System.getProperty("compose.application.resources.dir")?.let(::File)
    private val workDir = File(dataDir, "xray").apply { mkdirs() }
    private var process: WinBase.PROCESS_INFORMATION? = null
    private var configFile: File? = null

    val exe: File? get() = resourcesDir?.resolve("tunnel")?.walkTopDown()?.firstOrNull { it.name == "xray.exe" }
    val isAvailable: Boolean get() = exe != null
    val logFile: File get() = File(workDir, "xray.log")

    @Synchronized
    fun start(config: JSONObject) {
        stop()
        val xray = exe ?: error("The Xray program is missing from this installation.")
        logFile.delete()
        val file = File(workDir, "run.json").also { it.writeText(config.toString()) }
        val startup = WinBase.STARTUPINFO().apply {
            dwFlags = WinBase.STARTF_USESHOWWINDOW
            wShowWindow = WinDef.WORD(0) // SW_HIDE
        }
        val info = WinBase.PROCESS_INFORMATION()
        val command = "\"${xray.absolutePath}\" run -c \"${file.absolutePath}\""
        val created = Kernel32.INSTANCE.CreateProcess(
            null, command, null, null, false, WinDef.DWORD(CREATE_NO_WINDOW.toLong()), null, workDir.absolutePath, startup, info,
        )
        if (!created) {
            file.delete()
            error("Windows could not start Xray (error ${Kernel32.INSTANCE.GetLastError()}).")
        }
        process = info
        configFile = file
        AppLogger.i(TAG, "started xray.exe (pid ${info.dwProcessId.toInt()})")
    }

    @Synchronized
    fun isRunning(): Boolean {
        val p = process ?: return false
        return Kernel32.INSTANCE.WaitForSingleObject(p.hProcess, 0) == WinError.WAIT_TIMEOUT
    }

    /** Waits until Xray accepts connections on [port], or fails with what it logged. */
    suspend fun awaitListening(port: Int, timeoutMs: Long = 10_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (!isRunning()) error("Xray stopped right after it started. " + logTail())
            val open = runCatching { Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 300) } }.isSuccess
            if (open) {
                // The settings are read; the file holds the account's keys, so remove it.
                synchronized(this) { configFile?.delete(); configFile = null }
                return
            }
            delay(100)
        }
        error("Xray did not start in time. " + logTail())
    }

    private fun logTail(): String = runCatching { logFile.readLines().takeLast(3).joinToString(" ") }.getOrDefault("").take(400)

    @Synchronized
    fun stop() {
        process?.let {
            Kernel32.INSTANCE.TerminateProcess(it.hProcess, 0)
            Kernel32.INSTANCE.CloseHandle(it.hProcess)
            Kernel32.INSTANCE.CloseHandle(it.hThread)
            AppLogger.i(TAG, "stopped xray.exe")
        }
        process = null
        configFile?.delete()
        configFile = null
    }

    /** Ends xray.exe copies left from Ember's own folder by an earlier crash (never another program's Xray). */
    fun killStale() {
        val own = exe?.canonicalPath ?: return
        ProcessHandle.allProcesses().filter { h ->
            h.info().command().map { runCatching { File(it).canonicalPath }.getOrNull() == own }.orElse(false)
        }.forEach { it.destroyForcibly() }
    }

    companion object {
        fun freePorts(count: Int): List<Int> {
            val sockets = (1..count).map { ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")) }
            return sockets.map { it.localPort }.also { sockets.forEach { s -> s.close() } }
        }
    }
}
