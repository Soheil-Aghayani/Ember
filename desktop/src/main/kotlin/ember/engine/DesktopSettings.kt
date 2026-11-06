package ember.engine

import com.soheil.ember.vpn.tun.HevSocks5TunnelConfig
import java.io.File
import java.util.Properties

val MTU_RANGE = 1200..1500
val TCP_BUFFER_RANGE = 65_536..4_194_304
const val DEFAULT_TCP_BUFFER_BYTES = 524_288

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

    /** Sends the whole computer through the server (a Wintun adapter); off means only programs that use the local proxy. */
    var wholeDevice: Boolean
        get() = props.getProperty("wholeDevice", "true").toBoolean()
        set(value) = put("wholeDevice", value.toString())

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

    /**
     * Addresses that skip the VPN in whole-device mode: IPv4 addresses, IPv4 ranges (10.1.0.0/16) or domain names
     * (looked up when you connect). One entry per item.
     */
    var excludedAddresses: List<String>
        get() = props.getProperty("excludedAddresses", "").split(';').map { it.trim() }.filter { it.isNotEmpty() }
        set(value) = put("excludedAddresses", value.map { it.trim() }.filter { it.isNotEmpty() && !it.contains(';') }.distinct().joinToString(";"))

    /** What the [appRuleApps] list does (see [AppRuleMode]). */
    var appRuleMode: String
        get() = props.getProperty("appRuleMode", AppRuleMode.OFF).takeIf { it == AppRuleMode.SKIP || it == AppRuleMode.ONLY } ?: AppRuleMode.OFF
        set(value) = put("appRuleMode", value)

    /** Program file names (firefox.exe) the app rules apply to. */
    var appRuleApps: List<String>
        get() = props.getProperty("appRuleApps", "").split(';').map { it.trim() }.filter { it.isNotEmpty() }
        set(value) = put("appRuleApps", value.map { it.trim() }.filter { it.isNotEmpty() && !it.contains(';') && !it.contains('\\') && !it.contains('/') }.distinct().joinToString(";"))

    /** Connect by itself when the computer joins a network that is not in [trustedNetworks]. */
    var autoConnectUntrusted: Boolean
        get() = props.getProperty("autoConnectUntrusted", "false").toBoolean()
        set(value) = put("autoConnectUntrusted", value.toString())

    /** Network ids (see [NetworkIdentity]) where Ember does not connect by itself, for example the home Wi-Fi. */
    var trustedNetworks: List<String>
        get() = props.getProperty("trustedNetworks", "").split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        set(value) = put("trustedNetworks", value.map { it.trim() }.filter { it.isNotEmpty() && !it.contains('\n') }.distinct().joinToString("\n"))

    /** The server (see [ember.subs.ProxyNode.id]) that Connect uses. */
    var selectedNodeId: String
        get() = props.getProperty("selectedNodeId", "")
        set(value) = put("selectedNodeId", value)

    /** Servers the user starred; they sit at the top of every list. */
    var favoriteNodes: Set<String>
        get() = props.getProperty("favoriteNodes", "").split(';').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        set(value) = put("favoriteNodes", value.filter { it.isNotBlank() && !it.contains(';') }.joinToString(";"))

    /** Writes a detailed Xray log (it names the sites visited): only for tracking down a problem. */
    var diagnosticLog: Boolean
        get() = props.getProperty("diagnosticLog", "false").toBoolean()
        set(value) = put("diagnosticLog", value.toString())

    /** Carries IPv6 through the tunnel too; off keeps public IPv6 blocked. */
    var ipv6: Boolean
        get() = props.getProperty("ipv6", "true").toBoolean()
        set(value) = put("ipv6", value.toString())

    /** True from a successful connect until the user disconnects: still true after a crash or a restart. */
    var wasConnected: Boolean
        get() = props.getProperty("wasConnected", "false").toBoolean()
        set(value) = put("wasConnected", value.toString())

    var adminNoticeSeen: Boolean
        get() = props.getProperty("adminNoticeSeen", "false").toBoolean()
        set(value) = put("adminNoticeSeen", value.toString())

    /** Switch to the next-best server by itself when the connected one stops passing traffic. */
    var autoFailover: Boolean
        get() = props.getProperty("autoFailover", "true").toBoolean()
        set(value) = put("autoFailover", value.toString())

    var killSwitch: Boolean
        get() = props.getProperty("killSwitch", "true").toBoolean()
        set(value) = put("killSwitch", value.toString())

    private fun put(key: String, value: String) {
        props.setProperty(key, value)
        runCatching { file.outputStream().use { props.store(it, null) } }
    }
}
