package ember.engine

import com.soheil.ember.data.AppLogger
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private const val TAG = "AppRouting"
private const val TASK = "\\Ember\\Apps"

/** How the apps in the list are treated. */
object AppRuleMode {
    const val OFF = "off"

    /** The apps in the list go out directly; everything else uses the VPN. */
    const val SKIP = "skip"

    /** Only the apps in the list use the VPN; everything else goes out directly. */
    const val ONLY = "only"
}

/** The sing-box settings that decide, per program, whether traffic uses the VPN. */
object AppRoutingConfig {

    const val ADAPTER = "EmberApps"

    /**
     * sing-box takes over the computer's traffic through a tunnel adapter, knows which program each connection belongs
     * to, and sends what should use the VPN into Xray's local SOCKS5 port. Xray's own traffic (and sing-box's) must
     * leave directly, or it would loop back into the tunnel.
     */
    fun build(
        mode: String,
        apps: List<String>,
        socksPort: Int,
        skipCidrs: List<String>,
        skipDomains: List<String>,
        logFile: File,
    ): JSONObject {
        require(mode == AppRuleMode.SKIP || mode == AppRuleMode.ONLY) { "unknown mode $mode" }
        val names = apps.flatMap { listOf(it, it.lowercase()) }.distinct()
        val appsGoDirect = mode == AppRuleMode.SKIP

        val rules = JSONArray()
            .put(JSONObject().put("action", "sniff"))
            .put(JSONObject().put("protocol", "dns").put("action", "hijack-dns"))
            .put(route(JSONObject().put("process_name", JSONArray(listOf("xray.exe", "sing-box.exe"))), "direct"))
            .put(route(JSONObject().put("ip_is_private", true), "direct"))
        if (skipCidrs.isNotEmpty()) rules.put(route(JSONObject().put("ip_cidr", JSONArray(skipCidrs)), "direct"))
        if (skipDomains.isNotEmpty()) rules.put(route(JSONObject().put("domain", JSONArray(skipDomains)), "direct"))
        if (names.isNotEmpty()) rules.put(route(JSONObject().put("process_name", JSONArray(names)), if (appsGoDirect) "direct" else "proxy"))

        // Names are looked up the way the program that asks would reach the internet: a program that goes out directly
        // also resolves directly, one that uses the VPN resolves through it.
        val dnsRules = JSONArray()
        if (names.isNotEmpty()) {
            dnsRules.put(JSONObject().put("process_name", JSONArray(names)).put("server", if (appsGoDirect) "local" else "remote"))
        }

        return JSONObject()
            .put("log", JSONObject().put("level", "warn").put("output", logFile.absolutePath).put("timestamp", true))
            .put("dns", JSONObject()
                .put("servers", JSONArray()
                    .put(JSONObject().put("type", "tcp").put("tag", "remote").put("server", "1.1.1.1").put("detour", "proxy"))
                    .put(JSONObject().put("type", "local").put("tag", "local")))
                .put("rules", dnsRules)
                .put("final", if (appsGoDirect) "remote" else "local"))
            .put("inbounds", JSONArray().put(JSONObject()
                .put("type", "tun").put("tag", "tun-in").put("interface_name", ADAPTER)
                .put("address", JSONArray().put("172.19.0.1/30"))
                .put("auto_route", true).put("strict_route", true).put("stack", "mixed")))
            .put("outbounds", JSONArray()
                .put(JSONObject().put("type", "socks").put("tag", "proxy").put("server", "127.0.0.1").put("server_port", socksPort).put("version", "5"))
                .put(JSONObject().put("type", "direct").put("tag", "direct")))
            .put("route", JSONObject()
                .put("rules", rules)
                .put("final", if (appsGoDirect) "proxy" else "direct")
                .put("auto_detect_interface", true)
                .put("default_domain_resolver", JSONObject().put("server", "local")))
    }

    private fun route(match: JSONObject, outbound: String): JSONObject = match.put("action", "route").put("outbound", outbound)
}

/**
 * Starts sing-box (shipped with Ember) for per-program rules. It needs administrator rights to make its adapter, so a
 * scheduled task (one Windows prompt, once) starts it. When it stops, Windows removes its adapter, routes and firewall
 * rules by itself. Ember's own kill switch is not part of this mode.
 */
class AppRoutingRunner(dataDir: File, private val privileged: PrivilegedTask) {

    private val resourcesDir = System.getProperty("compose.application.resources.dir")?.let(::File)
    private val dir = File(dataDir, "apps")
    val configFile = File(dir, "config.json")
    val logFile = File(dir, "sing-box.log")

    val exe: File? get() = resourcesDir?.resolve("tunnel")?.walkTopDown()?.firstOrNull { it.name == "sing-box.exe" }
    val isAvailable: Boolean get() = exe != null

    private fun run(vararg command: String): Pair<Int, String> = runCatching {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        val text = process.inputStream.readBytes().toString(Charsets.UTF_8).replace("\u0000", "")
        process.waitFor(15, TimeUnit.SECONDS)
        process.exitValue() to text
    }.getOrDefault(-1 to "")

    /** True when the task exists and starts the sing-box this installation ships. */
    fun taskReady(exe: File): Boolean {
        val xml = run("schtasks.exe", "/query", "/tn", TASK, "/xml").second
        return xml.contains(exe.absolutePath, ignoreCase = true) && xml.contains(configFile.absolutePath, ignoreCase = true) && xml.contains("S4U", ignoreCase = true)
    }

    private fun isRunning(): Boolean = run("tasklist.exe", "/fi", "imagename eq sing-box.exe", "/fo", "csv", "/nh").second.contains("sing-box.exe", ignoreCase = true)

    private fun adapterExists(): Boolean = run("netsh.exe", "interface", "show", "interface", "name=${AppRoutingConfig.ADAPTER}").first == 0

    suspend fun installTask(exe: File) {
        dir.mkdirs()
        val user = (System.getenv("USERDOMAIN") ?: "") + "\\" + System.getProperty("user.name")
        val unsafe = listOf(exe.absolutePath, dir.absolutePath, user).any { it.contains('\'') || it.contains('"') }
        require(!unsafe) { "Unsupported character in a path or user name." }
        val arguments = "run -c \"${configFile.absolutePath}\" -D \"${dir.absolutePath}\""
        val d = '$'
        val register = """
            ${d}ErrorActionPreference = 'Stop'
            ${d}action = New-ScheduledTaskAction -Execute '${exe.absolutePath}' -Argument '$arguments' -WorkingDirectory '${dir.absolutePath}'
            ${d}principal = New-ScheduledTaskPrincipal -UserId '$user' -LogonType S4U -RunLevel Highest
            ${d}settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -ExecutionTimeLimit ([TimeSpan]::Zero)
            Register-ScheduledTask -TaskName 'Apps' -TaskPath '\Ember\' -Action ${d}action -Principal ${d}principal -Settings ${d}settings -Force | Out-Null
        """.trimIndent()
        privileged.elevatedPowerShell(register)
        val deadline = System.currentTimeMillis() + 90_000L
        while (System.currentTimeMillis() < deadline) {
            if (taskReady(exe)) return
            delay(500)
        }
        error("The one-time setup did not finish. Was the administrator prompt declined?")
    }

    /** Writes [config], starts sing-box and returns once its adapter is there. Throws when it does not come up. */
    suspend fun start(config: JSONObject) = withContext(Dispatchers.IO) {
        dir.mkdirs()
        configFile.writeText(config.toString(2))
        logFile.delete()
        val started = run("schtasks.exe", "/run", "/tn", TASK)
        AppLogger.i(TAG, "started the sing-box task: ${started.second.trim().take(120)}")
        val begun = System.currentTimeMillis()
        while (true) {
            if (adapterExists()) {
                delay(1_000) // routes are added right after the adapter appears
                if (isRunning()) return@withContext
            }
            val waited = System.currentTimeMillis() - begun
            if (waited > 4_000 && !isRunning()) error("The app-rules program did not start.${logHint()}")
            if (waited > 25_000) error("The app-rules program did not set up its adapter.${logHint()}")
            delay(400)
        }
    }

    suspend fun stop() = withContext(Dispatchers.IO) {
        run("schtasks.exe", "/end", "/tn", TASK)
        var waited = 0
        while (waited < 6_000 && isRunning()) {
            delay(300)
            waited += 300
        }
    }

    fun running(): Boolean = isRunning()

    private fun logHint(): String = runCatching {
        val lines = logFile.readLines().filter { Regex("(?i)fatal|error|fail").containsMatchIn(it) }.takeLast(2)
        if (lines.isEmpty()) "" else " " + lines.joinToString(" ") { it.substringAfter(' ').trim() }.take(220)
    }.getOrDefault("")
}
