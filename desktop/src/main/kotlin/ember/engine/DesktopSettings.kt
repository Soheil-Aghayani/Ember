package ember.engine

import java.io.File
import java.util.Properties

data class QuotaInfo(val remaining: Long?, val max: Long?, val resetEpochSeconds: Long?)

/** Small per-user settings file. */
class DesktopSettings(private val file: File) {

    private val props = Properties().apply {
        runCatching { if (file.isFile) file.inputStream().use { load(it) } }
    }

    var keepInTray: Boolean
        get() = props.getProperty("keepInTray", "true").toBoolean()
        set(value) = put("keepInTray", value.toString())

    var autoConnect: Boolean
        get() = props.getProperty("autoConnect", "false").toBoolean()
        set(value) = put("autoConnect", value.toString())

    var theme: String
        get() = props.getProperty("theme", "System")
        set(value) = put("theme", value)

    var lastCountry: String
        get() = props.getProperty("lastCountry", "")
        set(value) = put("lastCountry", value)

    var lastCity: String
        get() = props.getProperty("lastCity", "")
        set(value) = put("lastCity", value)

    var wholeDevice: Boolean
        get() = props.getProperty("wholeDevice", "false").toBoolean()
        set(value) = put("wholeDevice", value.toString())

    var adminNoticeSeen: Boolean
        get() = props.getProperty("adminNoticeSeen", "false").toBoolean()
        set(value) = put("adminNoticeSeen", value.toString())

    var killSwitch: Boolean
        get() = props.getProperty("killSwitch", "true").toBoolean()
        set(value) = put("killSwitch", value.toString())

    /** Last quota the service reported, so the Home screen can show it before connecting. */
    var quota: QuotaInfo?
        get() {
            val remaining = props.getProperty("quotaRemaining")?.toLongOrNull()
            val max = props.getProperty("quotaMax")?.toLongOrNull()
            val reset = props.getProperty("quotaReset")?.toLongOrNull()
            return if (remaining == null && max == null && reset == null) null else QuotaInfo(remaining, max, reset)
        }
        set(value) {
            if (value == null) return
            value.remaining?.let { props.setProperty("quotaRemaining", it.toString()) }
            value.max?.let { props.setProperty("quotaMax", it.toString()) }
            value.resetEpochSeconds?.let { props.setProperty("quotaReset", it.toString()) }
            runCatching { file.outputStream().use { props.store(it, null) } }
        }

    private fun put(key: String, value: String) {
        props.setProperty(key, value)
        runCatching { file.outputStream().use { props.store(it, null) } }
    }
}
