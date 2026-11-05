package ember.subs

import org.json.JSONArray
import org.json.JSONObject

/**
 * Servers from a configuration file the user picked: a WireGuard `.conf` becomes an Xray `wireguard` outbound (Xray
 * speaks WireGuard itself, so it runs like any other server). OpenVPN profiles are recognised only to explain that
 * Ember has no engine for them yet.
 */
object ConfigFiles {

    /** Any file with an `[Interface]` section; an incomplete one gets a specific message from [wireGuard]. */
    fun isWireGuard(text: String): Boolean = Regex("(?im)^\\s*\\[Interface]").containsMatchIn(text)

    fun isOpenVpn(text: String): Boolean = Regex("(?im)^\\s*remote\\s+\\S+").containsMatchIn(text) &&
        Regex("(?im)^\\s*(client|dev\\s+(tun|tap)|<ca>|proto\\s+(udp|tcp))").containsMatchIn(text)

    /** One server per `[Peer]`; the first peer's endpoint is the server. [nameHint] (the file name) names it. */
    fun wireGuard(text: String, nameHint: String): ProxyNode {
        val sections = mutableListOf<Pair<String, MutableMap<String, String>>>()
        for (line in text.lines()) {
            val clean = line.substringBefore('#').trim()
            if (clean.isEmpty()) continue
            if (clean.startsWith("[") && clean.endsWith("]")) {
                sections += clean.trim('[', ']').lowercase() to mutableMapOf()
            } else if ('=' in clean) {
                sections.lastOrNull()?.second?.put(clean.substringBefore('=').trim().lowercase(), clean.substringAfter('=').trim())
            }
        }
        val iface = sections.firstOrNull { it.first == "interface" }?.second ?: error("The WireGuard file has no [Interface] section.")
        val peer = sections.firstOrNull { it.first == "peer" }?.second ?: error("The WireGuard file has no [Peer] section.")
        fun list(value: String?) = value.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val privateKey = iface["privatekey"] ?: error("The WireGuard file has no PrivateKey.")
        val endpoint = peer["endpoint"] ?: error("The WireGuard file has no Endpoint.")
        val publicKey = peer["publickey"] ?: error("The WireGuard file has no peer PublicKey.")
        val addresses = list(iface["address"]).ifEmpty { error("The WireGuard file has no Address.") }

        val peerJson = JSONObject().put("publicKey", publicKey).put("endpoint", endpoint)
            .put("allowedIPs", JSONArray(list(peer["allowedips"]).ifEmpty { listOf("0.0.0.0/0", "::/0") }))
        peer["presharedkey"]?.takeIf { it.isNotBlank() }?.let { peerJson.put("preSharedKey", it) }
        peer["persistentkeepalive"]?.toIntOrNull()?.takeIf { it > 0 }?.let { peerJson.put("keepAlive", it) }
        val settings = JSONObject().put("secretKey", privateKey).put("address", JSONArray(addresses))
            .put("peers", JSONArray().put(peerJson))
        iface["mtu"]?.toIntOrNull()?.let { settings.put("mtu", it) }
        val outbound = JSONObject().put("protocol", "wireguard").put("settings", settings)
        return ShareLinks.fromXrayOutbound(outbound, nameHint) ?: error("The WireGuard endpoint is not valid.")
    }

    /** Splits `host:port` and `[v6]:port`. */
    fun hostAndPort(endpoint: String): Pair<String, Int>? {
        val host = if (endpoint.startsWith("[")) endpoint.substring(1, endpoint.indexOf(']').takeIf { it > 0 } ?: return null)
        else endpoint.substringBeforeLast(':')
        val port = endpoint.substringAfterLast(':').toIntOrNull() ?: return null
        return host.takeIf { it.isNotBlank() }?.let { it to port }
    }
}
