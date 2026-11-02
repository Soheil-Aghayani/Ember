package ember.subs

import java.net.URLDecoder
import java.security.MessageDigest
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject

private val INFO_HOSTS = setOf("0.0.0.0", "127.0.0.1", "localhost", "::1")

/** One server from a subscription. [raw] is the share link it came from, so the node can be rebuilt later. */
data class ProxyNode(
    val id: String,
    val name: String,
    val protocol: String,
    val host: String,
    val port: Int,
    val raw: String,
) {
    /** Placeholder rows panels add to show traffic left or expiry ("99 GB left"); they cannot be connected to. */
    val isInfo: Boolean get() = host.lowercase() in INFO_HOSTS || port <= 1

    /** Two-letter country code from a flag emoji at the start of the name, which panels put there ("🇹🇷 Turkey 1"). */
    val flagCode: String?
        get() {
            val points = name.codePoints().limit(2).toArray()
            if (points.size < 2 || points.any { it !in REGIONAL_A..REGIONAL_Z }) return null
            return points.map { ('a' + (it - REGIONAL_A)) }.joinToString("")
        }

    /** The name without the leading flag emoji (the app draws the flag itself). */
    val title: String
        get() = if (flagCode == null) name else name.codePoints().skip(2).toArray()
            .let { String(it, 0, it.size) }.trim().ifBlank { name }
}

private const val REGIONAL_A = 0x1F1E6
private const val REGIONAL_Z = 0x1F1FF

/** Reads `vless://`, `vmess://`, `trojan://` and `ss://` share links and turns them into Xray outbounds. */
object ShareLinks {

    fun parse(line: String): ProxyNode? {
        val raw = line.trim()
        return try {
            when {
                raw.startsWith("vless://") -> link(raw, "vless")
                raw.startsWith("trojan://") -> link(raw, "trojan")
                raw.startsWith("vmess://") -> vmess(raw)
                raw.startsWith("ss://") -> shadowsocks(raw)
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    /** The Xray outbound for [raw]. [address] replaces the host (an IP the caller resolved, so the route to it can be pinned). */
    fun outbound(raw: String, address: String, tag: String): JSONObject = when {
        raw.startsWith("vless://") -> {
            val l = split(raw, "vless")
            val user = JSONObject().put("id", l.userInfo).put("encryption", l.query["encryption"] ?: "none")
            l.query["flow"]?.takeIf { it.isNotBlank() }?.let { user.put("flow", it) }
            JSONObject().put("tag", tag).put("protocol", "vless")
                .put("settings", JSONObject().put("vnext", JSONArray().put(
                    JSONObject().put("address", address).put("port", l.port).put("users", JSONArray().put(user)),
                )))
                .put("streamSettings", stream(l.query, "none", l.host))
        }
        raw.startsWith("trojan://") -> {
            val l = split(raw, "trojan")
            JSONObject().put("tag", tag).put("protocol", "trojan")
                .put("settings", JSONObject().put("servers", JSONArray().put(
                    JSONObject().put("address", address).put("port", l.port).put("password", l.userInfo),
                )))
                .put("streamSettings", stream(l.query, "tls", l.host))
        }
        raw.startsWith("vmess://") -> {
            val j = vmessJson(raw)
            val query = mapOf(
                "type" to j.optString("net", "tcp").ifBlank { "tcp" },
                "security" to if (j.optString("tls").isNotBlank() && j.optString("tls") != "none") "tls" else "none",
                "sni" to j.optString("sni"),
                "host" to j.optString("host"),
                "path" to j.optString("path"),
                "alpn" to j.optString("alpn"),
                "fp" to j.optString("fp"),
                "headerType" to j.optString("type"),
                "serviceName" to j.optString("path"),
            ).filterValues { it.isNotBlank() }
            val user = JSONObject().put("id", j.optString("id")).put("alterId", j.optString("aid", "0").toIntOrNull() ?: 0)
                .put("security", j.optString("scy", "auto").ifBlank { "auto" })
            JSONObject().put("tag", tag).put("protocol", "vmess")
                .put("settings", JSONObject().put("vnext", JSONArray().put(
                    JSONObject().put("address", address).put("port", j.optString("port").toInt()).put("users", JSONArray().put(user)),
                )))
                .put("streamSettings", stream(query, "none", j.optString("add")))
        }
        raw.startsWith("ss://") -> {
            val s = ss(raw)
            JSONObject().put("tag", tag).put("protocol", "shadowsocks")
                .put("settings", JSONObject().put("servers", JSONArray().put(
                    JSONObject().put("address", address).put("port", s.port).put("method", s.method).put("password", s.password),
                )))
        }
        else -> error("Unsupported link type")
    }

    private class Link(val userInfo: String, val host: String, val port: Int, val query: Map<String, String>, val name: String)

    private fun decode(text: String): String = URLDecoder.decode(text.replace("+", "%2B"), "UTF-8")

    private fun split(raw: String, scheme: String): Link {
        var rest = raw.removePrefix("$scheme://")
        val name = decode(rest.substringAfter('#', ""))
        rest = rest.substringBefore('#')
        val queryText = rest.substringAfter('?', "")
        rest = rest.substringBefore('?')
        val at = rest.lastIndexOf('@')
        require(at >= 0) { "no user info" }
        val userInfo = decode(rest.substring(0, at))
        val hostPort = rest.substring(at + 1).trimEnd('/')
        val host: String
        val port: Int
        if (hostPort.startsWith("[")) {
            host = hostPort.substring(1, hostPort.indexOf(']'))
            port = hostPort.substringAfter("]:").toInt()
        } else {
            host = hostPort.substringBeforeLast(':')
            port = hostPort.substringAfterLast(':').toInt()
        }
        val query = queryText.split('&').filter { it.isNotEmpty() }.associate {
            decode(it.substringBefore('=')) to decode(it.substringAfter('=', ""))
        }
        return Link(userInfo, host, port, query, name)
    }

    private fun id(raw: String): String =
        MessageDigest.getInstance("SHA-256").digest(raw.toByteArray()).joinToString("") { "%02x".format(it) }.take(12)

    private fun node(raw: String, protocol: String, name: String, host: String, port: Int) =
        ProxyNode(id(raw), name.ifBlank { "$host:$port" }, protocol, host, port, raw)

    private fun link(raw: String, scheme: String): ProxyNode {
        val l = split(raw, scheme)
        return node(raw, scheme, l.name, l.host, l.port)
    }

    private fun vmessJson(raw: String): JSONObject = JSONObject(String(decodeBase64(raw.removePrefix("vmess://").substringBefore('#'))))

    private fun vmess(raw: String): ProxyNode {
        val j = vmessJson(raw)
        return node(raw, "vmess", j.optString("ps"), j.getString("add"), j.optString("port").toInt())
    }

    private class Ss(val method: String, val password: String, val host: String, val port: Int, val name: String)

    private fun ss(raw: String): Ss {
        var rest = raw.removePrefix("ss://")
        val name = decode(rest.substringAfter('#', ""))
        rest = rest.substringBefore('#')
        require("plugin=" !in rest) { "plugins are not supported" }
        rest = rest.substringBefore('?')
        val method: String
        val password: String
        val hostPort: String
        if ('@' in rest) {
            val userInfo = rest.substringBeforeLast('@')
            val plain = runCatching { String(decodeBase64(userInfo)) }.getOrNull()?.takeIf { ':' in it } ?: decode(userInfo)
            method = plain.substringBefore(':')
            password = plain.substringAfter(':')
            hostPort = rest.substringAfterLast('@').trimEnd('/')
        } else {
            val plain = String(decodeBase64(rest))
            method = plain.substringBefore(':')
            password = plain.substringAfter(':').substringBeforeLast('@')
            hostPort = plain.substringAfterLast('@')
        }
        return Ss(method, password, hostPort.substringBeforeLast(':').trim('[', ']'), hostPort.substringAfterLast(':').toInt(), name)
    }

    private fun shadowsocks(raw: String): ProxyNode {
        val s = ss(raw)
        return node(raw, "ss", s.name, s.host, s.port)
    }

    /** Standard or URL-safe base64, with or without padding. */
    fun decodeBase64(text: String): ByteArray {
        val clean = text.filterNot { it.isWhitespace() }.replace('-', '+').replace('_', '/').trimEnd('=')
        return Base64.getDecoder().decode(clean + "=".repeat((4 - clean.length % 4) % 4))
    }

    private fun stream(query: Map<String, String>, defaultSecurity: String, serverHost: String): JSONObject {
        val network = when (val n = query["type"] ?: "tcp") {
            "h2" -> "http"
            "splithttp" -> "xhttp"
            else -> n
        }
        val security = query["security"] ?: defaultSecurity
        val s = JSONObject().put("network", network).put("security", security)
        val sni = query["sni"]?.takeIf { it.isNotBlank() } ?: query["host"]?.takeIf { it.isNotBlank() } ?: serverHost
        when (security) {
            "tls" -> s.put("tlsSettings", JSONObject().apply {
                put("serverName", sni)
                query["alpn"]?.takeIf { it.isNotBlank() }?.let { put("alpn", JSONArray(it.split(','))) }
                put("fingerprint", query["fp"]?.takeIf { it.isNotBlank() } ?: "chrome")
                if (query["allowInsecure"] == "1" || query["insecure"] == "1") put("allowInsecure", true)
            })
            "reality" -> s.put("realitySettings", JSONObject().apply {
                put("serverName", sni)
                put("fingerprint", query["fp"]?.takeIf { it.isNotBlank() } ?: "chrome")
                put("publicKey", query["pbk"] ?: "")
                put("shortId", query["sid"] ?: "")
                put("spiderX", query["spx"] ?: "")
            })
        }
        val host = query["host"]?.takeIf { it.isNotBlank() }
        val path = query["path"]?.takeIf { it.isNotBlank() } ?: "/"
        when (network) {
            "tcp" -> if (query["headerType"] == "http") {
                s.put("tcpSettings", JSONObject().put("header", JSONObject().put("type", "http").put("request", JSONObject()
                    .put("path", JSONArray().put(path))
                    .put("headers", JSONObject().put("Host", JSONArray().put(host ?: serverHost))))))
            }
            "ws" -> s.put("wsSettings", JSONObject().put("path", path).apply {
                host?.let { put("headers", JSONObject().put("Host", it)) }
            })
            "grpc" -> s.put("grpcSettings", JSONObject()
                .put("serviceName", query["serviceName"] ?: "")
                .put("multiMode", query["mode"] == "multi"))
            "http" -> s.put("httpSettings", JSONObject().put("path", path).apply {
                host?.let { put("host", JSONArray(it.split(','))) }
            })
            "httpupgrade" -> s.put("httpupgradeSettings", JSONObject().put("path", path).apply { host?.let { put("host", it) } })
            "xhttp" -> s.put("xhttpSettings", JSONObject().put("path", path).put("mode", query["mode"] ?: "auto").apply { host?.let { put("host", it) } })
        }
        return s
    }
}
