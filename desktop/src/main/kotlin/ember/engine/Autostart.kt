package ember.engine

import java.util.concurrent.TimeUnit

object Autostart {
    private const val KEY = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run"
    val supported: Boolean get() = System.getProperty("os.name").startsWith("Windows")

    fun isEnabled(): Boolean = supported && runCatching {
        runCommand(listOf("reg.exe", "query", KEY, "/v", "Ember")) == 0
    }.getOrDefault(false)

    fun setEnabled(enabled: Boolean): Boolean = supported && runCatching {
        if (!enabled && !isEnabled()) return@runCatching true
        val args = if (enabled) {
            val executable = ProcessHandle.current().info().command().orElse(null)
                ?: return@runCatching false
            listOf("reg.exe", "add", KEY, "/v", "Ember", "/t", "REG_SZ", "/d", "\"$executable\" --minimized", "/f")
        } else listOf("reg.exe", "delete", KEY, "/v", "Ember", "/f")
        runCommand(args) == 0
    }.getOrDefault(false)

    private fun runCommand(args: List<String>): Int {
        val process = ProcessBuilder(args).redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD).start()
        return try {
            if (process.waitFor(5, TimeUnit.SECONDS)) process.exitValue() else -1
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }
}
