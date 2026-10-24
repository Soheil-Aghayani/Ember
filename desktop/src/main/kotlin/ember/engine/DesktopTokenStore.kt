package ember.engine

import com.soheil.ember.data.AuthTokenStore
import com.soheil.ember.data.model.RuntimeAuth
import com.sun.jna.platform.win32.Crypt32Util
import java.io.File
import java.nio.file.attribute.PosixFilePermissions
import java.util.Properties

/**
 * Persists the Firefox account session. On Windows the file is encrypted with DPAPI, so only the
 * same Windows user can read it. Elsewhere it is a plain file readable only by the owner.
 */
class DesktopTokenStore(private val file: File) : AuthTokenStore {

    private val lock = Any()
    private val isWindows = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

    private fun read(): Properties = synchronized(lock) {
        val props = Properties()
        if (!file.isFile) return props
        runCatching {
            val raw = file.readBytes()
            val plain = if (isWindows) Crypt32Util.cryptUnprotectData(raw) else raw
            props.load(plain.inputStream())
        }
        props
    }

    private fun write(props: Properties) = synchronized(lock) {
        file.parentFile?.mkdirs()
        val out = java.io.ByteArrayOutputStream()
        props.store(out, null)
        val bytes = if (isWindows) Crypt32Util.cryptProtectData(out.toByteArray()) else out.toByteArray()
        file.writeBytes(bytes)
        if (!isWindows) {
            runCatching {
                java.nio.file.Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString("rw-------"))
            }
        }
    }

    override var pendingSessionToken: String?
        get() = read().getProperty(KEY_PENDING)?.takeIf { it.isNotBlank() }
        set(value) {
            val props = read()
            if (value != null) props.setProperty(KEY_PENDING, value) else props.remove(KEY_PENDING)
            write(props)
        }

    override fun saveAuth(auth: RuntimeAuth) {
        val props = read()
        props.setProperty(KEY_ACCESS, auth.accessToken)
        if (auth.refreshToken != null) props.setProperty(KEY_REFRESH, auth.refreshToken!!) else props.remove(KEY_REFRESH)
        props.setProperty(KEY_EXPIRES, auth.expiresAtEpochSeconds.toString())
        write(props)
    }

    override fun loadAuth(): RuntimeAuth? {
        val props = read()
        val access = props.getProperty(KEY_ACCESS)?.takeIf { it.isNotBlank() } ?: return null
        val refresh = props.getProperty(KEY_REFRESH)?.takeIf { it.isNotBlank() }
        val expires = props.getProperty(KEY_EXPIRES)?.toLongOrNull() ?: 0L
        return RuntimeAuth(access, refresh, expires)
    }

    override fun hasValidAccessToken(): Boolean {
        val auth = loadAuth() ?: return false
        if (auth.expiresAtEpochSeconds <= 0L) return false
        return auth.expiresAtEpochSeconds - System.currentTimeMillis() / 1000 > CLOCK_SKEW_SECONDS
    }

    override fun clear() {
        synchronized(lock) { file.delete() }
    }

    private companion object {
        const val KEY_ACCESS = "access_token"
        const val KEY_REFRESH = "refresh_token"
        const val KEY_EXPIRES = "expires_at"
        const val KEY_PENDING = "pending_session_token"
        const val CLOCK_SKEW_SECONDS = 60L
    }
}
