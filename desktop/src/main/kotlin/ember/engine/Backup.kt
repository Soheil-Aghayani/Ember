package ember.engine

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.json.JSONObject

/**
 * A backup is a zip of the two files that hold what the user set up: the settings and the subscriptions (with the servers
 * last read from them). The subscriptions carry each account's key, so the file is as private as a password.
 */
object Backup {
    private const val SETTINGS = "settings.properties"
    private const val SUBSCRIPTIONS = "subscriptions.json"
    private const val MAX_FILE_BYTES = 5_000_000

    /** Settings that describe this computer or this moment, not a choice: a backup neither carries nor overwrites them. */
    private fun isLocal(key: String) =
        key in setOf("wasConnected", "adminNoticeSeen", "trustedNetworks", "lastCity", "lastCountry") || key.startsWith("quota")

    fun write(dataDir: File, target: File) {
        val settings = Properties().apply { File(dataDir, SETTINGS).takeIf { it.isFile }?.inputStream()?.use { load(it) } }
        settings.keys.filter { isLocal(it as String) }.forEach { settings.remove(it) }
        ZipOutputStream(target.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry(SETTINGS))
            settings.store(zip, null)
            zip.closeEntry()
            File(dataDir, SUBSCRIPTIONS).takeIf { it.isFile }?.let {
                zip.putNextEntry(ZipEntry(SUBSCRIPTIONS))
                zip.write(it.readBytes())
                zip.closeEntry()
            }
        }
    }

    /**
     * Replaces the settings and subscriptions in [dataDir] with the ones in [source]. Nothing is touched unless the whole
     * file checks out. The caller must have stopped the app first, or it would write its own copies over these.
     */
    fun restore(dataDir: File, source: File) {
        val found = HashMap<String, ByteArray>()
        ZipInputStream(source.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                require(entry.name == SETTINGS || entry.name == SUBSCRIPTIONS) { "unexpected file in the backup" }
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val n = zip.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    require(out.size() <= MAX_FILE_BYTES) { "the backup is too large" }
                }
                found[entry.name] = out.toByteArray()
            }
        }
        require(found.isNotEmpty()) { "the backup is empty" }
        found[SUBSCRIPTIONS]?.let { JSONObject(String(it, Charsets.UTF_8)) } // throws when it is not a subscriptions file

        found[SETTINGS]?.let { bytes ->
            val restored = Properties().apply { load(bytes.inputStream()) }
            restored.keys.filter { isLocal(it as String) }.forEach { restored.remove(it) }
            val current = File(dataDir, SETTINGS)
            Properties().apply { if (current.isFile) current.inputStream().use { load(it) } }
                .filter { isLocal(it.key as String) }.forEach { restored.setProperty(it.key as String, it.value as String) }
            current.outputStream().use { restored.store(it, null) }
        }
        found[SUBSCRIPTIONS]?.let { File(dataDir, SUBSCRIPTIONS).writeBytes(it) }
    }
}
