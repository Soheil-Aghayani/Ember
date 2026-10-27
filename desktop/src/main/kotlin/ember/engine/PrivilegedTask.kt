package ember.engine

import kotlinx.coroutines.delay
import java.io.File
import java.util.Base64
import java.util.concurrent.TimeUnit

private const val UP_TASK = "\\Ember\\Tunnel Up"
private const val RESTORE_TASK = "\\Ember\\Tunnel Restore"
private const val INSTALL_TIMEOUT_MS = 90_000L

/**
 * Optional one-time setup that lets Ember start its network helper without a Windows administrator
 * prompt on every connect. Setup (one prompt) registers two scheduled tasks that run the helper script
 * from the Ember installation folder with the highest privileges; connecting then just starts a task.
 *
 * The tasks only run ember-tunnel.ps1 from the installation folder, and the script validates everything
 * it reads from its config file. Programs already running as you can start the tunnel helper this way,
 * which is why this is opt-in.
 */
class PrivilegedTask(dataDir: File) {

    private val resourcesDir: File? =
        System.getProperty("compose.application.resources.dir")?.let(::File)
    private val script: File? get() = resourcesDir?.resolve("ember-tunnel.ps1")?.takeIf { it.isFile }
    private val nativeHelper: File? get() = resourcesDir?.resolve("ember-helper.exe")?.takeIf { it.isFile }
    private val configFile = File(dataDir, "tunnel-state/config.json")

    private fun schtasks(vararg args: String): Process =
        ProcessBuilder("schtasks.exe", *args).redirectErrorStream(true).start()

    private fun taskExists(name: String): Boolean = runCatching {
        val p = schtasks("/query", "/tn", name)
        p.inputStream.readBytes()
        p.waitFor(15, TimeUnit.SECONDS) && p.exitValue() == 0
    }.getOrDefault(false)

    fun isInstalled(): Boolean = taskExists(UP_TASK) && taskExists(RESTORE_TASK)

    /**
     * True when the registered tasks start the helper this installation ships. Tasks made by an older Ember point at the
     * PowerShell script; after an update to the native helper they must be registered again (one administrator prompt).
     */
    fun isCurrent(): Boolean {
        if (nativeHelper == null) return true
        return runCatching {
            val p = schtasks("/query", "/tn", UP_TASK, "/xml")
            val xml = p.inputStream.readBytes().toString(Charsets.UTF_8).replace("\u0000", "")
            p.waitFor(15, TimeUnit.SECONDS) && xml.contains("ember-helper.exe", ignoreCase = true)
        }.getOrDefault(false)
    }

    /** Starts the helper through its scheduled task (no prompt). */
    fun run(mode: String): Process = schtasks("/run", "/tn", if (mode == "Up") UP_TASK else RESTORE_TASK)

    /** Registers the tasks. Windows shows one administrator prompt. */
    suspend fun install() {
        val native = nativeHelper
        val helper = native ?: script ?: error("The network helper is missing from this installation.")
        val user = (System.getenv("USERDOMAIN") ?: "") + "\\" + System.getProperty("user.name")
        val unsafe = listOf(helper.absolutePath, configFile.absolutePath, user).any { it.contains('\'') || it.contains('"') }
        require(!unsafe) { "Unsupported character in a path or user name." }
        // The native helper runs directly; the PowerShell script runs through powershell.exe.
        val taskAction = if (native != null) {
            "${'$'}arg = ${'$'}mode.ToLower() + ' --config \"${configFile.absolutePath}\"'\n" +
                "                ${'$'}action = New-ScheduledTaskAction -Execute '${native.absolutePath}' -Argument ${'$'}arg"
        } else {
            "${'$'}arg = '-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File \"${helper.absolutePath}\" -Mode ' + ${'$'}mode + ' -ConfigPath \"${configFile.absolutePath}\"'\n" +
                "                ${'$'}action = New-ScheduledTaskAction -Execute ${'$'}ps -Argument ${'$'}arg"
        }
        val register = """
            ${'$'}ErrorActionPreference = 'Stop'
            ${'$'}ps = Join-Path ${'$'}env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
            function Reg(${'$'}name, ${'$'}mode) {
                $taskAction
                ${'$'}principal = New-ScheduledTaskPrincipal -UserId '$user' -LogonType Interactive -RunLevel Highest
                ${'$'}settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -ExecutionTimeLimit ([TimeSpan]::Zero)
                Register-ScheduledTask -TaskName ${'$'}name -TaskPath '\Ember\' -Action ${'$'}action -Principal ${'$'}principal -Settings ${'$'}settings -Force | Out-Null
            }
            Reg 'Tunnel Up' 'Up'
            Reg 'Tunnel Restore' 'Restore'
        """.trimIndent()
        elevatedPowerShell(register)
        val deadline = System.currentTimeMillis() + INSTALL_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (isInstalled() && isCurrent()) return
            delay(500)
        }
        error("The one-time setup did not finish. Was the administrator prompt declined?")
    }

    /** Removes the tasks. Windows shows one administrator prompt. */
    suspend fun uninstall() {
        elevatedPowerShell(
            "Unregister-ScheduledTask -TaskName 'Tunnel Up' -TaskPath '\\Ember\\' -Confirm:${'$'}false -ErrorAction SilentlyContinue; " +
                "Unregister-ScheduledTask -TaskName 'Tunnel Restore' -TaskPath '\\Ember\\' -Confirm:${'$'}false -ErrorAction SilentlyContinue",
        )
        val deadline = System.currentTimeMillis() + INSTALL_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (!isInstalled()) return
            delay(500)
        }
        error("Removing the one-time setup did not finish.")
    }

    private fun elevatedPowerShell(commandText: String) {
        val inner = Base64.getEncoder().encodeToString(commandText.toByteArray(Charsets.UTF_16LE))
        val outer = "Start-Process -FilePath powershell.exe -Verb RunAs -WindowStyle Hidden " +
            "-ArgumentList '-NoProfile','-NonInteractive','-EncodedCommand','$inner'"
        val encoded = Base64.getEncoder().encodeToString(outer.toByteArray(Charsets.UTF_16LE))
        ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded)
            .redirectErrorStream(true)
            .start()
    }
}
