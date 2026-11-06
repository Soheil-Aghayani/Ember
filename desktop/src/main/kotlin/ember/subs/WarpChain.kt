package ember.subs

import java.util.Base64
import org.json.JSONObject

/**
 * Cloudflare WARP carried through one of the user's own servers. Some networks drop WireGuard traffic to Cloudflare, so
 * the tunnel's packets are sent inside a connection to the chosen server instead (Xray's `dialerProxy`): the network
 * sees only that connection, and the traffic comes out of Cloudflare. The server must carry UDP.
 *
 * The node is stored as `warpchain:<name>:<carrier link>:<WARP outbound>` (each part in base64). Its host and port are
 * the carrier's, so lookups, checks and the kill switch all deal with the server the network really sees.
 */
object WarpChain {

    const val PREFIX = "warpchain:"

    /** WARP's usual address; used as a number so no name lookup is needed on the way. */
    private const val ENDPOINT = "162.159.192.1:2408"

    /** A chained node for [carrier], from the WireGuard outbound [warp] of a registered WARP device. */
    fun create(carrier: ProxyNode, warp: JSONObject): ProxyNode {
        val wireGuard = JSONObject(warp.toString())
        wireGuard.remove("ember_name")
        wireGuard.remove("tag")
        wireGuard.getJSONObject("settings").getJSONArray("peers").getJSONObject(0).put("endpoint", ENDPOINT)
        // keep the carrier's flag in front of the name so the list shows where the traffic goes through
        val flags = carrier.name.substringBefore(carrier.title, "")
        val name = "${flags}WARP via ${carrier.title}"
        val b64 = Base64.getEncoder()
        val raw = PREFIX + listOf(name, carrier.raw, wireGuard.toString()).joinToString(":") { b64.encodeToString(it.toByteArray()) }
        return ShareLinks.node(raw, "warp", name, carrier.host, carrier.port)
    }

    private fun parts(raw: String): List<String>? = runCatching {
        val b64 = Base64.getDecoder()
        raw.removePrefix(PREFIX).split(':').map { String(b64.decode(it)) }.takeIf { it.size == 3 }
    }.getOrNull()

    /** Rebuilds a stored node (same raw text, so the same id). */
    fun fromRaw(raw: String): ProxyNode? {
        val (name, carrierRaw, _) = parts(raw) ?: return null
        val carrier = ShareLinks.parse(carrierRaw) ?: return null
        return ShareLinks.node(raw, "warp", name, carrier.host, carrier.port)
    }

    /** The two Xray outbounds: WARP (named [tag]) and the carrier it dials through, which connects to [address]. */
    fun outbounds(raw: String, address: String, tag: String): List<JSONObject> {
        val (_, carrierRaw, warp) = parts(raw) ?: error("This WARP server could not be read.")
        val carrierTag = "$tag-carrier"
        val wireGuard = JSONObject(warp).put("tag", tag)
            .put("streamSettings", JSONObject().put("sockopt", JSONObject().put("dialerProxy", carrierTag)))
        return listOf(wireGuard, ShareLinks.outbound(carrierRaw, address, carrierTag))
    }
}
