package ember.engine

import java.io.File
import java.util.Properties

/** Small per-user settings file. */
class DesktopSettings(private val file: File) {

    private val props = Properties().apply {
        runCatching { if (file.isFile) file.inputStream().use { load(it) } }
    }

    var wholeDevice: Boolean
        get() = props.getProperty("wholeDevice", "false").toBoolean()
        set(value) = put("wholeDevice", value.toString())

    var killSwitch: Boolean
        get() = props.getProperty("killSwitch", "true").toBoolean()
        set(value) = put("killSwitch", value.toString())

    private fun put(key: String, value: String) {
        props.setProperty(key, value)
        runCatching { file.outputStream().use { props.store(it, null) } }
    }
}
