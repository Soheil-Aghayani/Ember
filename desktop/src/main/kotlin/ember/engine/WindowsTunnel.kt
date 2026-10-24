package ember.engine

import com.soheil.ember.data.AppLogger
import com.soheil.ember.vpn.tun.HevSocks5TunnelConfig
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

private const val TAG = "WindowsTunnel"
private const val ADAPTER_NAME = "EmberTun"
private const val START_TIMEOUT_MS = 120_000L
private const val STOP_TIMEOUT_MS = 20_000L

/**
 * Whole-device mode on Windows. An elevated PowerShell helper (ember-tunnel.ps1, shipped in the
 * application folder) runs hev-socks5-tunnel on a Wintun adapter, routes traffic into it and applies the
 * kill switch. This class only starts and stops that helper and reads its status file; it never runs
 * as administrator itself.
 */
class WindowsTunnel(dataDir: File) {

    private val stateDir = File(dataDir, "tunnel-state").apply { mkdirs() }
    private val statusFile = File(stateDir, "status")
    private val stopFile = File(stateDir, "stop")
    private val configFile = File(stateDir, "config.json")

    private val resourcesDir: File? =
        System.getProperty("compose.application.resources.dir")?.let(::File)

    private val script: File? get() = resourcesDir?.resolve("ember-tunnel.ps1")?.takeIf { it.isFile }

    private val hevPresent: Boolean
        get() = resourcesDir?.resolve("tunnel")?.walkTopDown()?.any { it.name == "hev-socks5-tunnel.exe" } == true

    val isAvailable: Boolean
        get() = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true) &&
            script != null && hevPresent

    fun status(): String? = runCatching { statusFile.readText().trim() }.getOrNull()

    /** True when a previous run left the kill switch holding traffic (Ember crashed, or the tunnel died). */
    fun isBlocked(): Boolean = status()?.startsWith("BLOCKED") == true

    /**
     * Starts the helper and waits for it to report UP. Shows a Windows administrator prompt.
     * [edgeIps] are the Mozilla edge addresses that must stay on the physical network.
     */
    suspend fun start(socksPort: Int, edgeIps: List<String>, killSwitch: Boolean, mtu: Int = HevSocks5TunnelConfig.TUN_MTU) {
        val helper = script ?: error("The tunnel helper is missing from this installation.")
        statusFile.delete()
        stopFile.delete()

        val hevConfig = HevSocks5TunnelConfig.write(stateDir.parentFile, socksPort, useMapDns = true, mtu = mtu, tunnelName = ADAPTER_NAME)
        val config = JSONObject()
            .put("stateDir", stateDir.absolutePath)
            .put("hevConfigPath", hevConfig)
            .put("adapterName", ADAPTER_NAME)
            .put("tunAddress", HevSocks5TunnelConfig.TUN_ADDRESS)
            .put("mapdnsAddress", HevSocks5TunnelConfig.MAPDNS_ADDRESS)
            .put("edgeIps", JSONArray(edgeIps))
            .put("killSwitch", killSwitch)
            .put("parentPid", ProcessHandle.current().pid())
        configFile.writeText(config.toString())

        val launcher = elevate(helper, "Up", configFile)

        val deadline = System.currentTimeMillis() + START_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val current = status()
            when {
                current == "UP" -> return
                current != null && current.startsWith("ERROR") -> error(current.removePrefix("ERROR:").trim())
                current == null && !launcher.isAlive && launcher.exitValue() != 0 ->
                    error("Administrator permission was not granted, so the tunnel could not start.")
            }
            delay(500)
        }
        error("The tunnel helper did not start. Was the administrator prompt declined?")
    }

    /** Asks the helper to restore routes and firewall rules, and waits for it. */
    suspend fun stop() {
        val current = status() ?: return
        if (current == "DOWN" || current.startsWith("ERROR")) return
        stopFile.writeText("stop")
        val deadline = System.currentTimeMillis() + STOP_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val current = status()
            if (current == "DOWN") return
            delay(300)
        }
        AppLogger.w(TAG, "the tunnel helper did not confirm shutdown; running a restore")
        restore()
    }

    /** Undoes the kill switch and any leftovers. Shows a Windows administrator prompt. */
    suspend fun restore() {
        val helper = script ?: return
        configFile.writeText(JSONObject().put("stateDir", stateDir.absolutePath).toString())
        stopFile.writeText("stop")
        statusFile.delete()
        elevate(helper, "Restore", configFile)
        val deadline = System.currentTimeMillis() + STOP_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (status() == "DOWN") return
            delay(300)
        }
        error("Restoring the network timed out. Run ember-tunnel.ps1 -Mode Restore as administrator.")
    }

    private fun elevate(helper: File, mode: String, config: File): Process {
        val helperPath = helper.absolutePath
        val configPath = config.absolutePath
        require(!helperPath.contains('\'') && !configPath.contains('\'')) { "Unsupported character in an installation path." }
        val args = "'-NoProfile','-ExecutionPolicy','Bypass','-File','\"$helperPath\"','-Mode','$mode','-ConfigPath','\"$configPath\"'"
        val command = "Start-Process -FilePath powershell.exe -Verb RunAs -WindowStyle Hidden -ArgumentList $args"
        return ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", command)
            .redirectErrorStream(true)
            .start()
    }
}
