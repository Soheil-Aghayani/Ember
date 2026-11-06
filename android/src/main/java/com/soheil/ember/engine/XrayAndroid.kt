package com.soheil.ember.engine

import android.content.Context
import com.soheil.ember.data.AppLogger
import ember.subs.XrayProcess
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.delay
import org.json.JSONObject

private const val TAG = "Xray"

/**
 * Xray on Android: the official arm64 build is packaged as `libxray.so` (a program, not a library), unpacked next to the
 * app's other native libraries and started as a child process, which is how it stays outside the VPN it carries
 * (the VPN excludes this app).
 */
class XrayAndroid(private val context: Context) : XrayProcess {

    private val workDir = File(context.filesDir, "xray").apply { mkdirs() }
    private var process: Process? = null
    private var configFile: File? = null

    private val binary: File get() = File(context.applicationInfo.nativeLibraryDir, "libxray.so")

    override val isAvailable: Boolean get() = binary.isFile
    override val logFile: File get() = File(workDir, "xray.log")

    @Synchronized
    override fun start(config: JSONObject) {
        stop()
        check(isAvailable) { "The Xray program is missing from this app (this phone's processor is not supported)." }
        logFile.delete()
        val file = File(workDir, "run.json").also { it.writeText(config.toString()) }
        process = ProcessBuilder(binary.absolutePath, "run", "-c", file.absolutePath)
            .directory(workDir)
            .redirectErrorStream(true)
            .redirectOutput(File(workDir, "xray.out"))
            .also { it.environment()["XRAY_LOCATION_ASSET"] = workDir.absolutePath }
            .start()
        configFile = file
        AppLogger.i(TAG, "started xray")
    }

    @Synchronized
    override fun isRunning(): Boolean = process?.isAlive == true

    override suspend fun awaitListening(port: Int, timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (!isRunning()) error("Xray stopped right after it started. " + outputTail())
            val open = runCatching { Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 300) } }.isSuccess
            if (open) {
                // the settings are read; the file holds the account's keys, so remove it
                synchronized(this) { configFile?.delete(); configFile = null }
                return
            }
            delay(100)
        }
        error("Xray did not start in time. " + outputTail())
    }

    private fun outputTail(): String =
        runCatching { File(workDir, "xray.out").readLines().takeLast(3).joinToString(" ") }.getOrDefault("").take(400)

    @Synchronized
    override fun stop() {
        process?.let {
            it.destroy()
            if (runCatching { it.waitFor(1, java.util.concurrent.TimeUnit.SECONDS) }.getOrDefault(false).not()) it.destroyForcibly()
            AppLogger.i(TAG, "stopped xray")
        }
        process = null
        configFile?.delete()
        configFile = null
    }
}
