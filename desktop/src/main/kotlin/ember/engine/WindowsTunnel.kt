package ember.engine

import com.soheil.ember.data.AppLogger
import com.soheil.ember.vpn.tun.HevSocks5TunnelConfig
import com.sun.jna.platform.win32.Shell32
import com.sun.jna.platform.win32.WinUser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Base64

private const val TAG = "WindowsTunnel"
private const val ADAPTER_NAME = "EmberTun"
private const val START_TIMEOUT_MS = 120_000L
private const val HELPER_SILENT_TIMEOUT_MS = 20_000L
private const val STOP_TIMEOUT_MS = 20_000L

/** A helper start-up failure. [retryWithFallback] is true when the helper itself ran and reported a problem. */
private class HelperFailure(message: String, val retryWithFallback: Boolean) : Exception(message)

/** How the helper was launched: a process (PowerShell or scheduled task) or, for the native helper, just an outcome. */
private class Launch(val process: Process?, val failure: String?, val launchedAt: Long = System.currentTimeMillis())

/**
 * Whole-device mode on Windows. An elevated helper runs hev-socks5-tunnel on a Wintun adapter, routes
 * traffic into it and applies the kill switch. Two helpers ship in the application folder:
 * `ember-helper.exe` (native, about a second to set up) and `ember-tunnel.ps1` (PowerShell, the fallback).
 * This class only starts and stops the helper and reads its status file; it never runs as administrator itself.
 */
class WindowsTunnel(dataDir: File) {

    private val stateDir = File(dataDir, "tunnel-state").apply { mkdirs() }
    private val statusFile = File(stateDir, "status")
    private val stopFile = File(stateDir, "stop")
    private val configFile = File(stateDir, "config.json")
    val logFile = File(stateDir, "helper.log")
    val privileged = PrivilegedTask(dataDir)

    private val resourcesDir: File? =
        System.getProperty("compose.application.resources.dir")?.let(::File)

    private val script: File? get() = resourcesDir?.resolve("ember-tunnel.ps1")?.takeIf { it.isFile }
    private val nativeHelper: File? get() = resourcesDir?.resolve("ember-helper.exe")?.takeIf { it.isFile }

    private val hevPresent: Boolean
        get() = resourcesDir?.resolve("tunnel")?.walkTopDown()?.any { it.name == "hev-socks5-tunnel.exe" } == true

    val isAvailable: Boolean
        get() = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true) &&
            (nativeHelper != null || script != null) && hevPresent

    /** Asks the running helper to re-assert the route to Mozilla's server (used before redialing). */
    fun requestRefresh() {
        runCatching { File(stateDir, "refresh").writeText("1") }
    }

    fun status(): String? = runCatching { statusFile.readText().trim() }.getOrNull()

    /** True when a previous run left the kill switch holding traffic (Ember crashed, or the tunnel died). */
    fun isBlocked(): Boolean = status()?.startsWith("BLOCKED") == true

    /**
     * True when the status file says a tunnel was up but no native helper is running any more, for example after
     * Windows restarted or Ember was killed. The firewall rules of that run may still be in place.
     */
    fun isStale(): Boolean {
        val current = status() ?: return false
        if (current == "DOWN" || current.startsWith("ERROR") || current.startsWith("BLOCKED")) return false
        return nativeHelper != null && !nativeHelperRunning()
    }

    private fun nativeHelperRunning(): Boolean = runCatching {
        ProcessHandle.allProcesses().anyMatch { handle ->
            handle.info().command().map { it.endsWith("ember-helper.exe", ignoreCase = true) }.orElse(false)
        }
    }.getOrDefault(false)

    /**
     * Starts the helper and waits for it to report UP. Windows shows an administrator prompt unless the optional
     * one-time setup was done. [edgeIps] are the Mozilla edge addresses that must stay on the physical network.
     * [onProgress] receives plain-language steps for the UI.
     */
    suspend fun start(
        socksPort: Int,
        edgeIps: List<String>,
        killSwitch: Boolean,
        mtu: Int = HevSocks5TunnelConfig.TUN_MTU,
        tcpBufferSize: Int = HevSocks5TunnelConfig.DEFAULT_TCP_BUFFER_SIZE_BYTES,
        excludeCidrs: List<String> = emptyList(),
        onProgress: (String) -> Unit = {},
    ) {
        stopStaleHelper()
        val hevConfig = HevSocks5TunnelConfig.write(stateDir.parentFile, socksPort, useMapDns = true, mtu = mtu, tunnelName = ADAPTER_NAME, tcpBufferSize = tcpBufferSize)
        val config = JSONObject()
            .put("stateDir", stateDir.absolutePath)
            .put("hevConfigPath", hevConfig)
            .put("adapterName", ADAPTER_NAME)
            .put("tunAddress", HevSocks5TunnelConfig.TUN_ADDRESS)
            .put("mapdnsAddress", HevSocks5TunnelConfig.MAPDNS_ADDRESS)
            .put("edgeIps", JSONArray(edgeIps))
            .put("excludeCidrs", JSONArray(excludeCidrs))
            .put("killSwitch", killSwitch)
            .put("parentPid", ProcessHandle.current().pid())
        val native = nativeHelper
        val ps = script
        if (native == null && ps == null) error("The network helper is missing from this installation.")

        if (native != null) {
            try {
                startWith(native, native = true, config, onProgress)
                return
            } catch (failure: HelperFailure) {
                if (!failure.retryWithFallback || ps == null) throw IllegalStateException(failure.message)
                AppLogger.w(TAG, "the native helper failed (${failure.message}); trying the PowerShell helper")
                onProgress("Trying the fallback helper…")
            }
        }
        try {
            startWith(ps!!, native = false, config, onProgress)
        } catch (failure: HelperFailure) {
            throw IllegalStateException(failure.message)
        }
    }

    private suspend fun startWith(helper: File, native: Boolean, config: JSONObject, onProgress: (String) -> Unit) {
        statusFile.delete()
        stopFile.delete()
        logFile.delete()
        configFile.writeText(config.toString())

        if (native && privileged.isInstalled() && !privileged.isCurrent()) {
            // The one-time setup was made by an older Ember and still starts the slow PowerShell helper: register it again.
            onProgress("Updating the one-time setup (one Windows prompt)…")
            runCatching { privileged.install() }.onFailure { AppLogger.w(TAG, "could not update the one-time setup", it) }
        }
        val noPrompt = privileged.isInstalled() && privileged.isCurrent() && (native || nativeHelper == null)
        onProgress(
            if (noPrompt) "Starting the secure network adapter…"
            else "Waiting for you to allow administrator access in the Windows prompt…",
        )
        val launch = if (noPrompt) Launch(privileged.run("Up"), null) else elevate(helper, native, "Up", configFile)
        if (launch.failure != null) throw HelperFailure(launch.failure, retryWithFallback = false)

        val started = System.currentTimeMillis()
        var reportedAdapter = false
        while (System.currentTimeMillis() - started < START_TIMEOUT_MS) {
            val current = status()
            when {
                current == "UP" -> return
                current != null && current.startsWith("ERROR") ->
                    throw HelperFailure(current.removePrefix("ERROR:").trim(), retryWithFallback = true)
                current == "STARTING" && !reportedAdapter -> {
                    reportedAdapter = true
                    onProgress("Setting up the secure network adapter…")
                }
            }
            if (current == null) {
                val process = launch.process
                if (process != null && !process.isAlive) {
                    if (process.exitValue() != 0) {
                        val detail = process.inputStream.bufferedReader().readText().trim()
                        throw HelperFailure(
                            if (detail.contains("canceled", ignoreCase = true) || detail.contains("cancelled", ignoreCase = true)) {
                                "Administrator access was not allowed, so the tunnel could not start."
                            } else {
                                "Windows could not start the network helper: ${detail.ifBlank { "exit code ${process.exitValue()}" }}"
                            },
                            retryWithFallback = false,
                        )
                    }
                }
                val silentFor = System.currentTimeMillis() - (if (process != null && !process.isAlive) started else launch.launchedAt)
                if ((process == null || !process.isAlive) && silentFor > HELPER_SILENT_TIMEOUT_MS) {
                    throw HelperFailure(
                        "The network helper was allowed to run but did not report back. Details may be in ${logFile.absolutePath}",
                        retryWithFallback = native,
                    )
                }
            }
            delay(150)
        }
        throw HelperFailure("The network helper did not finish starting in time. Details may be in ${logFile.absolutePath}", retryWithFallback = false)
    }

    /** A helper left over from an earlier connection (for example one holding the kill switch) must end first. */
    private suspend fun stopStaleHelper() {
        val current = status() ?: return
        if (current == "DOWN" || current.startsWith("ERROR")) return
        stopFile.writeText("stop")
        val deadline = System.currentTimeMillis() + 10_000L
        while (System.currentTimeMillis() < deadline) {
            if (status() == "DOWN") return
            delay(300)
        }
    }

    /** Asks the helper to restore routes and firewall rules, and waits for it. */
    suspend fun stop() {
        val current = status() ?: return
        if (current == "DOWN" || current.startsWith("ERROR")) return
        stopFile.writeText("stop")
        val deadline = System.currentTimeMillis() + STOP_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (status() == "DOWN") return
            delay(150)
        }
        AppLogger.w(TAG, "the tunnel helper did not confirm shutdown; running a restore")
        restore()
    }

    /** Undoes the kill switch and any leftovers. Windows shows an administrator prompt unless the one-time setup was done. */
    suspend fun restore() {
        val native = nativeHelper
        val helper = native ?: script ?: return
        configFile.writeText(JSONObject().put("stateDir", stateDir.absolutePath).toString())
        stopFile.writeText("stop")
        statusFile.delete()
        if (privileged.isInstalled() && privileged.isCurrent()) {
            privileged.run("Restore")
        } else {
            val launch = elevate(helper, native != null, "Restore", configFile)
            if (launch.failure != null) error(launch.failure)
        }
        val deadline = System.currentTimeMillis() + STOP_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (status() == "DOWN") return
            delay(150)
        }
        error("Restoring the network timed out. Run ember-helper.exe restore as administrator, or use Windows' firewall settings to remove the \"Ember\" rules.")
    }

    private suspend fun elevate(helper: File, native: Boolean, mode: String, config: File): Launch =
        withContext(Dispatchers.IO) {
            if (native) elevateNative(helper, mode, config) else elevatePowerShell(helper, mode, config)
        }

    /** Starts the native helper with the Windows administrator prompt (ShellExecute "runas"); returns when the prompt is answered. */
    private fun elevateNative(helper: File, mode: String, config: File): Launch {
        val parameters = "${mode.lowercase()} --config \"${config.absolutePath}\""
        // ShellExecute returns a value above 32 on success and an error code (5 = access denied / prompt declined) otherwise.
        val code = Shell32.INSTANCE.ShellExecute(null, "runas", helper.absolutePath, parameters, helper.parent, WinUser.SW_HIDE).toLong()
        return when {
            code > 32 -> Launch(null, null)
            code == 5L || code == 1223L -> Launch(null, "Administrator access was not allowed, so the tunnel could not start.")
            else -> Launch(null, "Windows could not start the network helper (error $code).")
        }
    }

    private fun elevatePowerShell(helper: File, mode: String, config: File): Launch {
        val helperPath = helper.absolutePath
        val configPath = config.absolutePath
        require(!helperPath.contains('\'') && !configPath.contains('\'')) { "Unsupported character in an installation path." }
        val args = "'-NoProfile','-ExecutionPolicy','Bypass','-File','\"$helperPath\"','-Mode','$mode','-ConfigPath','\"$configPath\"'"
        val command = "Start-Process -FilePath powershell.exe -Verb RunAs -WindowStyle Hidden -ArgumentList $args"
        // -EncodedCommand avoids every quoting problem between the JVM and PowerShell.
        val encoded = Base64.getEncoder().encodeToString(command.toByteArray(Charsets.UTF_16LE))
        val process = ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded)
            .redirectErrorStream(true)
            .start()
        return Launch(process, null)
    }
}
