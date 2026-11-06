package ember.engine

import com.soheil.ember.data.AppLogger
import java.io.File
import java.io.InputStream
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

private const val TAG = "OpenVpn"
private const val TASK = "\\Ember\\OpenVPN"
private const val MANAGEMENT_PORT = 17505
private const val CONNECT_TIMEOUT_MS = 90_000L
private const val ENTER_PASSWORD = "ENTER PASSWORD:"

/** What the user typed for a profile that asks for credentials. [username] is null when only a password (a key's) is asked. */
class OpenVpnCredentials(val username: String?, val password: String)

/** The server refused the credentials; the ones Ember remembered are wrong. */
class OpenVpnAuthFailed : IllegalStateException("The server refused the user name or password.")

/**
 * Runs the OpenVPN program the user installed (openvpn.net/community-downloads) for `.ovpn` servers. OpenVPN needs
 * administrator rights to add its adapter and routes, so a scheduled task (one Windows prompt, once) starts it; Ember
 * then follows it through OpenVPN's management port, which is also where user names and passwords are handed over (they
 * never touch the disk). OpenVPN sets up its own adapter and routes: Ember's kill switch does not cover these servers.
 */
class OpenVpnRunner(dataDir: File, private val privileged: PrivilegedTask) {

    private val dir = File(dataDir, "openvpn")
    private val profileFile = File(dir, "active.ovpn")
    private val passwordFile = File(dir, "management.pw")
    private val logFile = File(dir, "openvpn.log")

    /** The installed `openvpn.exe`, or null when OpenVPN is not installed. */
    fun executable(): File? =
        listOf("ProgramFiles", "ProgramW6432", "ProgramFiles(x86)").mapNotNull { System.getenv(it) }.distinct()
            .map { File(it, "OpenVPN\\bin\\openvpn.exe") }.firstOrNull { it.isFile }

    private fun run(vararg command: String): String = runCatching {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        val text = process.inputStream.readBytes().toString(Charsets.UTF_8).replace("\u0000", "")
        process.waitFor(15, TimeUnit.SECONDS)
        text
    }.getOrDefault("")

    /** True when the task exists and starts [exe] with the options this version of Ember expects. */
    fun taskReady(exe: File): Boolean {
        val xml = run("schtasks.exe", "/query", "/tn", TASK, "/xml")
        return xml.contains(exe.absolutePath, ignoreCase = true) && xml.contains("$MANAGEMENT_PORT") && xml.contains("management-query-passwords") && xml.contains("S4U", ignoreCase = true)
    }

    private fun isRunning(): Boolean = run("tasklist.exe", "/fi", "imagename eq openvpn.exe", "/fo", "csv", "/nh").contains("openvpn.exe", ignoreCase = true)

    /** Registers the task. Windows shows one administrator prompt. */
    suspend fun installTask(exe: File) {
        dir.mkdirs()
        val user = (System.getenv("USERDOMAIN") ?: "") + "\\" + System.getProperty("user.name")
        val unsafe = listOf(exe.absolutePath, dir.absolutePath, user).any { it.contains('\'') || it.contains('"') }
        require(!unsafe) { "Unsupported character in a path or user name." }
        // Everything that matters is on the command line; the profile cannot change it (script-security is set after it).
        val arguments = "--config \"${profileFile.absolutePath}\" --script-security 1 --management 127.0.0.1 $MANAGEMENT_PORT " +
            "\"${passwordFile.absolutePath}\" --management-hold --management-query-passwords --log \"${logFile.absolutePath}\" --verb 3"
        val d = '$'
        val register = """
            ${d}ErrorActionPreference = 'Stop'
            ${d}action = New-ScheduledTaskAction -Execute '${exe.absolutePath}' -Argument '$arguments' -WorkingDirectory '${dir.absolutePath}'
            ${d}principal = New-ScheduledTaskPrincipal -UserId '$user' -LogonType S4U -RunLevel Highest
            ${d}settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -ExecutionTimeLimit ([TimeSpan]::Zero)
            Register-ScheduledTask -TaskName 'OpenVPN' -TaskPath '\Ember\' -Action ${d}action -Principal ${d}principal -Settings ${d}settings -Force | Out-Null
        """.trimIndent()
        privileged.elevatedPowerShell(register)
        val deadline = System.currentTimeMillis() + 90_000L
        while (System.currentTimeMillis() < deadline) {
            if (taskReady(exe)) return
            delay(500)
        }
        error("The one-time setup did not finish. Was the administrator prompt declined?")
    }

    /**
     * Starts OpenVPN with [profile] and follows it until it ends. Returns when OpenVPN stops; throws when it never
     * connects. [onConnected] runs once the tunnel is up. Cancelling the caller shuts OpenVPN down.
     */
    suspend fun session(
        profile: String,
        askCredentials: suspend (subject: String, askUser: Boolean) -> OpenVpnCredentials?,
        onProgress: (String) -> Unit,
        onConnected: () -> Unit,
    ) = withContext(Dispatchers.IO) {
        dir.mkdirs()
        if (isRunning()) {
            // an OpenVPN left running by an earlier Ember (it holds the old management password): end it first
            AppLogger.w(TAG, "ending an OpenVPN that was already running")
            run("schtasks.exe", "/end", "/tn", TASK)
            var waited = 0
            while (waited < 4_000 && isRunning()) {
                delay(300)
                waited += 300
            }
        }
        val secret = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        profileFile.writeText(profile)
        passwordFile.writeText(secret)
        logFile.delete()
        val started = run("schtasks.exe", "/run", "/tn", TASK)
        AppLogger.i(TAG, "started the OpenVPN task: ${started.trim().take(120)}")
        var socket: Socket? = null
        try {
            socket = openManagement()
            socket.soTimeout = 500
            val output = socket.getOutputStream()
            fun send(line: String) {
                output.write((line + "\n").toByteArray())
                output.flush()
            }
            val reader = LineReader(socket.getInputStream())
            var connected = false
            var deadline = System.currentTimeMillis() + CONNECT_TIMEOUT_MS
            while (true) {
                ensureActive()
                val line = try {
                    reader.next()
                } catch (_: SocketTimeoutException) {
                    if (!connected && System.currentTimeMillis() > deadline) error("OpenVPN did not connect in time.${logHint()}")
                    continue
                } ?: break
                when {
                    line == ENTER_PASSWORD -> send(secret)
                    line.startsWith("SUCCESS: password is correct") -> {
                        send("state on")
                        send("hold release")
                    }
                    line.startsWith(">PASSWORD:Verification Failed") -> throw OpenVpnAuthFailed()
                    line.startsWith(">PASSWORD:Need '") -> {
                        val match = Regex("^>PASSWORD:Need '(.+)' (username/password|password)").find(line) ?: continue
                        val subject = match.groupValues[1]
                        val askUser = match.groupValues[2] == "username/password"
                        onProgress("Waiting for your sign-in…")
                        val credentials = askCredentials(subject, askUser) ?: error("Sign-in was cancelled.")
                        if (askUser) send("username \"$subject\" \"${escape(credentials.username.orEmpty())}\"")
                        send("password \"$subject\" \"${escape(credentials.password)}\"")
                        deadline = System.currentTimeMillis() + CONNECT_TIMEOUT_MS
                        onProgress("Signing in…")
                    }
                    line.startsWith(">STATE:") -> {
                        val parts = line.removePrefix(">STATE:").split(',')
                        when (val state = parts.getOrNull(1).orEmpty()) {
                            "CONNECTED" -> if (!connected) {
                                connected = true
                                AppLogger.i(TAG, "OpenVPN is connected")
                                onConnected()
                            }
                            "EXITING" -> {
                                if (!connected) error("OpenVPN stopped: ${parts.getOrNull(2).orEmpty()}.${logHint()}")
                                break
                            }
                            "AUTH", "GET_CONFIG" -> if (!connected) onProgress("Signing in…")
                            "ASSIGN_IP", "ADD_ROUTES" -> if (!connected) onProgress("Setting up the connection…")
                            "RECONNECTING" -> AppLogger.w(TAG, "OpenVPN is reconnecting (${parts.getOrNull(2).orEmpty()})")
                            else -> if (!connected) onProgress("Reaching the server…")
                                .also { AppLogger.i(TAG, "state $state") }
                        }
                    }
                    line.startsWith(">FATAL:") -> error(line.removePrefix(">FATAL:").trim() + logHint())
                }
            }
            if (!connected) error("OpenVPN stopped before it connected.${logHint()}")
        } finally {
            withContext(NonCancellable) { stop(socket) }
        }
    }

    private suspend fun openManagement(): Socket {
        val started = System.currentTimeMillis()
        while (true) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            try {
                return Socket().apply { connect(InetSocketAddress("127.0.0.1", MANAGEMENT_PORT), 500) }
            } catch (_: ConnectException) {
            } catch (_: SocketTimeoutException) {
            }
            val waited = System.currentTimeMillis() - started
            // OpenVPN that has already exited (no network driver, a bad option) will never open the port.
            if (waited > 4_000 && !isRunning()) error("OpenVPN did not start.${logHint()}")
            if (waited > 30_000) error("OpenVPN did not answer.${logHint()}")
            delay(300)
        }
    }

    /** Asks OpenVPN to close down cleanly, then ends the task if it is still there. */
    private suspend fun stop(socket: Socket?) {
        runCatching { socket?.getOutputStream()?.apply { write("signal SIGTERM\n".toByteArray()); flush() } }
        runCatching { socket?.close() }
        var waited = 0
        while (waited < 8_000 && isRunning()) {
            delay(500)
            waited += 500
        }
        if (isRunning()) {
            AppLogger.w(TAG, "OpenVPN did not stop by itself; ending its task")
            run("schtasks.exe", "/end", "/tn", TASK)
        }
    }

    /** The last lines of OpenVPN's own log that name a problem, for the error shown to the user. */
    private fun logHint(): String = runCatching {
        val lines = logFile.readLines().filter { Regex("(?i)error|fail|cannot|AUTH|TLS").containsMatchIn(it) }.takeLast(2)
        if (lines.isEmpty()) "" else " " + lines.joinToString(" ") { it.substringAfter(' ').trim() }.take(200)
    }.getOrDefault("")

    private fun escape(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")

    /** Reads management lines; the password prompt arrives without an end of line, so it is recognised on its own. */
    private class LineReader(private val input: InputStream) {
        private val buffer = StringBuilder()

        /** The next non-empty line, or null at the end of the stream. A read timeout keeps what has arrived so far. */
        fun next(): String? {
            while (true) {
                val newline = buffer.indexOf("\n")
                if (newline >= 0) {
                    val line = buffer.substring(0, newline).trimEnd('\r')
                    buffer.delete(0, newline + 1)
                    if (line.isNotEmpty()) return line
                    continue
                }
                if (buffer.toString() == ENTER_PASSWORD) {
                    buffer.clear()
                    return ENTER_PASSWORD
                }
                val byte = input.read()
                if (byte < 0) return null
                buffer.append(byte.toChar())
            }
        }
    }
}
