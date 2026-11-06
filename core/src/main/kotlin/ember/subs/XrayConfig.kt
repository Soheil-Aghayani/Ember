package ember.subs

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** Builds Xray configurations for one connection or for testing many servers at once. */
object XrayConfig {

    /** [verbose] logs why connections fail but also names the sites visited, so it is only for hunting a problem. */
    private fun base(logFile: File, verbose: Boolean = false) =
        JSONObject().put("log", JSONObject().put("loglevel", if (verbose) "info" else "warning").put("error", logFile.absolutePath))

    private fun socksInbound(tag: String, port: Int) = JSONObject()
        .put("tag", tag).put("listen", "127.0.0.1").put("port", port).put("protocol", "socks")
        .put("settings", JSONObject().put("auth", "noauth").put("udp", true).put("ip", "127.0.0.1"))

    /** One server behind a local SOCKS5 port (and a local HTTP proxy when [httpPort] is given). */
    /**
     * One server behind a local SOCKS5 port (and a local HTTP proxy when [httpPort] is given). [bypass] maps hostnames
     * the user wants to reach without the VPN to the IPv4 addresses Ember looked up for them: Xray sends exactly those
     * names straight out. (Whole-device mode hands applications made-up addresses for every name, so a route to a
     * website's real address is never used by them; the decision has to be made here, where the name is still known.)
     */
    fun single(
        node: ProxyNode, address: String, socksPort: Int, httpPort: Int?, logFile: File,
        verbose: Boolean = false, bypass: Map<String, List<String>> = emptyMap(),
    ): JSONObject {
        val inbounds = JSONArray().put(socksInbound("in", socksPort))
        if (httpPort != null) {
            inbounds.put(JSONObject().put("tag", "http").put("listen", "127.0.0.1").put("port", httpPort).put("protocol", "http"))
        }
        // QUIC (UDP 443) is dropped here: many servers carry no UDP, and a browser then waits for a QUIC attempt that
        // never answers before it falls back to TCP. Dropping it makes sites use TCP straight away.
        val outbounds = JSONArray()
        ShareLinks.outbounds(node.raw, address, "proxy").forEach { outbounds.put(it) }
        outbounds.put(JSONObject().put("tag", "block").put("protocol", "blackhole"))
        val rules = JSONArray()
        val config = base(logFile, verbose)
        if (bypass.isNotEmpty()) {
            outbounds.put(JSONObject().put("tag", "direct").put("protocol", "freedom")
                .put("settings", JSONObject().put("domainStrategy", "UseIPv4")))
            val hosts = JSONObject()
            for ((name, ips) in bypass) hosts.put("full:$name", JSONArray(ips))
            // Only exact names are routed directly, and each one has its addresses here, so Xray never has to ask a
            // resolver (which would hand back a made-up address and loop through the tunnel).
            config.put("dns", JSONObject().put("hosts", hosts).put("servers", JSONArray().put("localhost")))
            rules.put(JSONObject().put("type", "field").put("domain", JSONArray(bypass.keys.map { "full:$it" })).put("outboundTag", "direct"))
        }
        rules.put(JSONObject().put("type", "field").put("network", "udp").put("port", "443").put("outboundTag", "block"))
        return config.put("inbounds", inbounds).put("outbounds", outbounds)
            .put("routing", JSONObject().put("domainStrategy", "AsIs").put("rules", rules))
    }

    /** Each server gets its own local SOCKS5 port, so all of them can be tested at the same time. */
    fun many(entries: List<Triple<ProxyNode, String, Int>>, logFile: File): JSONObject {
        val inbounds = JSONArray()
        val outbounds = JSONArray()
        val rules = JSONArray()
        entries.forEachIndexed { i, (node, address, port) ->
            inbounds.put(socksInbound("in$i", port))
            ShareLinks.outbounds(node.raw, address, "out$i").forEach { outbounds.put(it) }
            rules.put(JSONObject().put("type", "field").put("inboundTag", JSONArray().put("in$i")).put("outboundTag", "out$i"))
        }
        return base(logFile).put("inbounds", inbounds).put("outbounds", outbounds)
            .put("routing", JSONObject().put("domainStrategy", "AsIs").put("rules", rules))
    }
}
