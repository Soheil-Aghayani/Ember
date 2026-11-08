package ember.subs

import com.soheil.ember.data.AppLogger
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.WinBase
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinError
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

private const val TAG = "Xray"
private const val CREATE_NO_WINDOW = 0x08000000

/** Runs Ember's own copy of xray.exe without a console window and stops it again. */
class XrayRunner(dataDir: File) : XrayProcess {

    private val resourcesDir = System.getProperty("compose.application.resources.dir")?.let(::File)
    private val workDir = File(dataDir, "xray").apply { mkdirs() }
    private var process: WinBase.PROCESS_INFORMATION? = null
    private var configFile: File? = null

    val exe: File? get() = resourcesDir?.resolve("tunnel")?.walkTopDown()?.firstOrNull { it.name == "xray.exe" }
    override val isAvailable: Boolean get() = exe != null
    override val logFile: File get() = File(workDir, "xray.log")

    @Synchronized
    override fun start(config: JSONObject) {
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
    override fun isRunning(): Boolean {
        val p = process ?: return false
        return Kernel32.INSTANCE.WaitForSingleObject(p.hProcess, 0) == WinError.WAIT_TIMEOUT
    }

    /** Waits until Xray accepts connections on [port], or fails with what it logged. */
    override suspend fun awaitListening(port: Int, timeoutMs: Long) {
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
        error("Xray did not start in time. " + logTail().ifBlank {
            "It wrote nothing, so something may be holding xray.exe up (an antivirus scan, or a program using port $port)."
        })
    }

    private fun logTail(): String = runCatching { logFile.readLines().takeLast(3).joinToString(" ") }.getOrDefault("").take(400)

    @Synchronized
    override fun stop() {
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
}
