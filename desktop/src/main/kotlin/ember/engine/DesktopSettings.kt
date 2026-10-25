package ember.engine

import com.soheil.ember.data.TlsSplitMode
import com.soheil.ember.vpn.tun.HevSocks5TunnelConfig
import java.io.File
import java.util.Properties

val MTU_RANGE = 1200..1500
val TCP_BUFFER_RANGE = 65_536..4_194_304
const val DEFAULT_TCP_BUFFER_BYTES = 524_288

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

    var language: String
        get() = props.getProperty("language")?.takeIf { it == "en" || it == "fa" }
            ?: if (java.util.Locale.getDefault().language == "fa") "fa" else "en"
        set(value) = put("language", if (value == "fa") "fa" else "en")

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

    /** Splits the TLS handshake to Mozilla's server into pieces, which can get past networks that block the VPN. */
    var tlsSplitMode: TlsSplitMode
        get() = runCatching { TlsSplitMode.valueOf(props.getProperty("tlsSplitMode", "OFF")) }.getOrDefault(TlsSplitMode.OFF)
        set(value) = put("tlsSplitMode", value.name)

    /** Largest packet the whole-device tunnel sends. Lower it if some sites hang or load partially. */
    var mtu: Int
        get() = props.getProperty("mtu")?.toIntOrNull()?.coerceIn(MTU_RANGE) ?: HevSocks5TunnelConfig.TUN_MTU
        set(value) = put("mtu", value.coerceIn(MTU_RANGE).toString())

    /**
     * Per-connection TCP buffer of the whole-device tunnel. One connection moves at most buffer / round-trip time,
     * so the library default of 64 KB caps a single download at about 0.8 MB/s on a 80 ms path.
     */
    var tcpBufferBytes: Int
        get() = props.getProperty("tcpBufferBytes")?.toIntOrNull()?.coerceIn(TCP_BUFFER_RANGE) ?: DEFAULT_TCP_BUFFER_BYTES
        set(value) = put("tcpBufferBytes", value.coerceIn(TCP_BUFFER_RANGE).toString())

    /** Picks the quickest of a few recommended servers instead of a random one. */
    var fastestServer: Boolean
        get() = props.getProperty("fastestServer", "true").toBoolean()
        set(value) = put("fastestServer", value.toString())

    /**
     * Addresses that skip the VPN in whole-device mode: IPv4 addresses, IPv4 ranges (10.1.0.0/16) or domain names
     * (looked up when you connect). One entry per item.
     */
    var excludedAddresses: List<String>
        get() = props.getProperty("excludedAddresses", "").split(';').map { it.trim() }.filter { it.isNotEmpty() }
        set(value) = put("excludedAddresses", value.map { it.trim() }.filter { it.isNotEmpty() && !it.contains(';') }.distinct().joinToString(";"))

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
