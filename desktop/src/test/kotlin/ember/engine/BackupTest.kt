package ember.engine

import java.io.File
import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class BackupTest {
    private fun dir() = createTempDirectory("ember-backup").toFile()

    private fun props(file: File) = Properties().apply { file.inputStream().use { load(it) } }

    @Test
    fun roundTripKeepsChoicesAndLeavesThisComputersStateAlone() {
        val a = dir()
        File(a, "settings.properties").writeText("theme=Dark\nlanguage=fa\nwasConnected=true\nquotaMax=9\ntrustedNetworks=home\n")
        File(a, "subscriptions.json").writeText("""{"subscriptions":[{"nodes":["vless://x"]}]}""")
        val zip = File(a, "b.zip")
        Backup.write(a, zip)

        val b = dir()
        File(b, "settings.properties").writeText("theme=Light\ntrustedNetworks=office\nadminNoticeSeen=true\n")
        Backup.restore(b, zip)

        val restored = props(File(b, "settings.properties"))
        assertEquals("Dark", restored.getProperty("theme"))
        assertEquals("fa", restored.getProperty("language"))
        assertEquals("office", restored.getProperty("trustedNetworks")) // this PC's own value survives
        assertEquals("true", restored.getProperty("adminNoticeSeen"))
        assertFalse(restored.containsKey("wasConnected"))
        assertFalse(restored.containsKey("quotaMax"))
        assertEquals(File(a, "subscriptions.json").readText(), File(b, "subscriptions.json").readText())
    }

    @Test
    fun refusesAFileThatIsNotABackupAndChangesNothing() {
        val d = dir()
        File(d, "settings.properties").writeText("theme=Light\n")
        val evil = File(d, "evil.zip")
        ZipOutputStream(evil.outputStream()).use {
            it.putNextEntry(ZipEntry("settings.properties")); it.write("theme=Dark".toByteArray()); it.closeEntry()
            it.putNextEntry(ZipEntry("../escape.txt")); it.write("x".toByteArray()); it.closeEntry()
        }
        assertFailsWith<IllegalArgumentException> { Backup.restore(d, evil) }
        assertEquals("Light", props(File(d, "settings.properties")).getProperty("theme"))

        val notSubs = File(d, "bad.zip")
        ZipOutputStream(notSubs.outputStream()).use { it.putNextEntry(ZipEntry("subscriptions.json")); it.write("not json".toByteArray()); it.closeEntry() }
        assertFailsWith<Exception> { Backup.restore(d, notSubs) }
        assertFalse(File(d, "subscriptions.json").exists())
    }
}
