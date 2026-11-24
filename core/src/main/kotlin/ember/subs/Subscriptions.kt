package ember.subs

import com.soheil.ember.data.AppLogger
import java.io.File
import java.net.URI
import java.util.concurrent.TimeUnit
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

private const val TAG = "Subscriptions"
private const val FETCH_AGENT = "Ember/1.0 (v2rayN compatible)"

/** Data use and expiry a panel reports in the `subscription-userinfo` header. */
data class SubscriptionUsage(val uploaded: Long, val downloaded: Long, val total: Long, val expiresAtEpochSeconds: Long?) {
    val used: Long get() = uploaded + downloaded
}

/** What the last speed test measured for one server. [pingMs] null means the server did not answer. */
data class NodeResult(
    val pingMs: Int?,
    val mbps: Double?,
    val testedAtMs: Long,
    val jitterMs: Int? = null,
    val successRate: Double? = null,
)

/** Name of the subscription that holds the Cloudflare WARP servers. */
const val WARP_NAME = "Cloudflare WARP"

/** Stored as the selected server when Connect should pick the fastest tested one. */
const val AUTO_NODE = "auto"

data class Subscription(
    val id: String,
    val name: String,
    val url: String,
    val nodes: List<ProxyNode>,
    val usage: SubscriptionUsage?,
    val updatedAtMs: Long,
)

data class SubscriptionRefreshPreview(
    val token: String,
    val added: Int,
    val removed: Int,
    val unchanged: Int,
    val total: Int,
)

object SubscriptionParser {

    fun parseNodes(body: String, nameHint: String = ""): List<ProxyNode> {
        val text = body.trim().removePrefix("﻿")
        if (ConfigFiles.isWireGuard(text)) return listOf(ConfigFiles.wireGuard(text, nameHint))
        if (ConfigFiles.isOpenVpn(text)) return listOf(OpenVpnProfile.node(text, nameHint))
        if (text.startsWith("{") || text.startsWith("[")) {
            val fromJson = runCatching { ShareLinks.fromXrayJson(text) }.getOrDefault(emptyList()).distinctBy { it.id }
            if (fromJson.isNotEmpty()) return fromJson
        }
        val lines = if ("://" in text) {
            text.lines()
        } else {
            runCatching { String(ShareLinks.decodeBase64(text)).lines() }.getOrDefault(emptyList())
        }
        val nodes = lines.mapNotNull(ShareLinks::parse).distinctBy { it.id }
        if (nodes.isEmpty()) {
            if (text.startsWith("{") || text.startsWith("[")) {
                error("This subscription is in a format Ember does not read (JSON without Xray servers). Ask the provider for the standard link list.")
            }
            error("No servers were found in this subscription.")
        }
        return nodes
    }

    /** `upload=1; download=2; total=3; expire=1700000000` */
    fun parseUserInfo(header: String?): SubscriptionUsage? {
        if (header.isNullOrBlank()) return null
        val values = header.split(';').mapNotNull {
            val key = it.substringBefore('=', "").trim().lowercase()
            val value = it.substringAfter('=', "").trim().toDoubleOrNull()?.toLong()
            if (key.isEmpty() || value == null) null else key to value
        }.toMap()
        val total = values["total"] ?: return null
        return SubscriptionUsage(values["upload"] ?: 0, values["download"] ?: 0, total, values["expire"]?.takeIf { it > 0 })
    }

    /** `profile-title` is plain text or `base64:<text>`. */
    fun parseTitle(header: String?): String? {
        if (header.isNullOrBlank()) return null
        return if (header.startsWith("base64:")) {
            runCatching { String(ShareLinks.decodeBase64(header.removePrefix("base64:"))) }.getOrNull()
        } else header
    }
}

/** The user's subscription links and the servers last read from them, kept in one JSON file in the app data folder. */
class SubscriptionStore(private val file: File) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val _subscriptions = MutableStateFlow<List<Subscription>>(emptyList())
    val subscriptions: StateFlow<List<Subscription>> = _subscriptions

    private val _results = MutableStateFlow<Map<String, NodeResult>>(emptyMap())
    val results: StateFlow<Map<String, NodeResult>> = _results

    private data class PendingRefresh(val subscriptionId: String, val sourceUrl: String, val oldNodeIds: Set<String>, val fetched: Fetched)
    private val pendingRefreshes = java.util.concurrent.ConcurrentHashMap<String, PendingRefresh>()

    /** The last IPv4 address each server's name resolved to, used when a later lookup fails. */
    private val addresses = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun rememberAddress(node: ProxyNode, ip: String) {
        if (addresses.put(node.id, ip) != ip) save()
    }

    fun rememberedAddress(node: ProxyNode): String? = addresses[node.id]

    fun backupBytes(): ByteArray = if (file.isFile) file.readBytes() else "{\"subscriptions\":[]}".toByteArray()

    /** Validates a complete backup in a temporary store, then atomically replaces the saved subscription file. */
    fun restoreBackupBytes(bytes: ByteArray) {
        require(bytes.size <= 5_000_000) { "The saved subscriptions are too large." }
        val root = runCatching { JSONObject(bytes.toString(Charsets.UTF_8)) }
            .getOrElse { throw IllegalArgumentException("The saved subscriptions are damaged.") }
        val expected = root.optJSONArray("subscriptions")
            ?: throw IllegalArgumentException("The backup does not contain subscriptions.")
        val temp = File(file.parentFile, "${file.name}.restore-${java.util.UUID.randomUUID()}")
        try {
            temp.writeBytes(bytes)
            val verified = SubscriptionStore(temp)
            require(verified.subscriptions.value.size == expected.length()) { "The saved subscriptions are damaged." }
            file.parentFile?.mkdirs()
            runCatching { Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
                .recoverCatching { Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING) }
                .getOrElse { throw IllegalStateException("The saved subscriptions could not be replaced.", it) }
            addresses.clear()
            _subscriptions.value = emptyList()
            _results.value = emptyMap()
            load()
        } finally {
            temp.delete()
        }
    }

    init {
        load()
    }

    fun nodeById(id: String): ProxyNode? = _subscriptions.value.firstNotNullOfOrNull { s -> s.nodes.firstOrNull { it.id == id } }

    suspend fun add(url: String, name: String): Subscription {
        val clean = url.trim()
        if (!clean.startsWith("https://") && !clean.startsWith("http://")) return addPasted(clean, name)
        val host = runCatching { URI(clean).host }.getOrNull()
        require(!host.isNullOrBlank()) { "That does not look like a link." }
        val fetched = fetch(clean)
        val subscription = Subscription(
            id = java.util.UUID.randomUUID().toString().take(8),
            name = name.trim().ifBlank { fetched.title ?: host!! },
            url = clean,
            nodes = fetched.nodes,
            usage = fetched.usage,
            updatedAtMs = System.currentTimeMillis(),
        )
        _subscriptions.value = _subscriptions.value + subscription
        save()
        return subscription
    }

    /** Registers a free Cloudflare WARP device and adds its servers (one per port). */
    suspend fun addWarp(): Subscription {
        require(_subscriptions.value.none { it.name == WARP_NAME }) { "Cloudflare WARP is already in your list." }
        val registration = Warp.register()
        val nodes = Warp.configs(registration).map { (name, text) -> ConfigFiles.wireGuard(text, name) }
        val subscription = Subscription(
            id = java.util.UUID.randomUUID().toString().take(8), name = WARP_NAME, url = "", nodes = nodes,
            usage = null, updatedAtMs = System.currentTimeMillis(),
        )
        _subscriptions.value = _subscriptions.value + subscription
        save()
        return subscription
    }

    /**
     * Adds Cloudflare WARP carried through [carrier] (a server of the user's), for networks that drop WireGuard to
     * Cloudflare. Registers a WARP device first when there is none yet.
     */
    suspend fun addWarpChain(carrier: ProxyNode): ProxyNode {
        val warp = _subscriptions.value.firstOrNull { it.name == WARP_NAME } ?: addWarp()
        val wireGuard = warp.nodes.first { it.protocol == "wireguard" }
        val chain = WarpChain.create(carrier, JSONObject(wireGuard.raw.removePrefix(ShareLinks.JSON_PREFIX)))
        require(warp.nodes.none { it.id == chain.id }) { "WARP through this server is already in your list." }
        replace(warp.copy(nodes = warp.nodes + chain))
        return chain
    }

    /** Server links pasted directly (one per line): kept as a list without a link to refresh. */
    private fun addPasted(text: String, name: String): Subscription {
        val nodes = try {
            SubscriptionParser.parseNodes(text, name.trim())
        } catch (failure: IllegalStateException) {
            // Messages for files Ember recognises (WireGuard problems, OpenVPN) are specific; the rest get the general hint.
            val specific = failure.message?.takeIf { it.startsWith("The WireGuard") || it.startsWith("The OpenVPN") }
            throw IllegalArgumentException(specific ?: "That is neither a subscription link (https://…) nor server links, a WireGuard or OpenVPN file or Xray JSON.")
        }
        val subscription = Subscription(
            id = java.util.UUID.randomUUID().toString().take(8),
            name = name.trim().ifBlank { "Pasted servers" },
            url = "",
            nodes = nodes,
            usage = null,
            updatedAtMs = System.currentTimeMillis(),
        )
        _subscriptions.value = _subscriptions.value + subscription
        save()
        return subscription
    }

    suspend fun refresh(id: String) {
        val current = _subscriptions.value.firstOrNull { it.id == id }?.takeIf { it.url.isNotBlank() } ?: return
        val fetched = fetch(current.url)
        replace(current.copy(nodes = fetched.nodes, usage = fetched.usage, updatedAtMs = System.currentTimeMillis()))
    }

    /** Fetches a refresh without replacing the last good list; only [applyRefreshPreview] commits it. */
    suspend fun previewRefresh(id: String): SubscriptionRefreshPreview {
        val current = _subscriptions.value.firstOrNull { it.id == id }?.takeIf { it.url.isNotBlank() }
            ?: error("This subscription cannot be updated.")
        val fetched = fetch(current.url)
        val old = current.nodes.mapTo(hashSetOf()) { it.id }
        val new = fetched.nodes.mapTo(hashSetOf()) { it.id }
        val token = java.util.UUID.randomUUID().toString()
        pendingRefreshes.entries.removeIf { it.value.subscriptionId == id }
        pendingRefreshes[token] = PendingRefresh(id, current.url, old, fetched)
        return SubscriptionRefreshPreview(token, (new - old).size, (old - new).size, (old intersect new).size, fetched.nodes.size)
    }

    /** Applies exactly the previewed data, and refuses to commit if the subscription changed meanwhile. */
    fun applyRefreshPreview(token: String) {
        val pending = pendingRefreshes.remove(token) ?: error("This preview has expired. Update the subscription again.")
        val current = _subscriptions.value.firstOrNull { it.id == pending.subscriptionId }
            ?: error("This subscription was removed. The saved list was left unchanged.")
        if (current.url != pending.sourceUrl || current.nodes.map { it.id }.toSet() != pending.oldNodeIds) {
            error("This subscription changed after the preview. Update it again to review the latest version.")
        }
        replace(current.copy(nodes = pending.fetched.nodes, usage = pending.fetched.usage, updatedAtMs = System.currentTimeMillis()))
    }

    fun discardRefreshPreview(token: String) {
        pendingRefreshes.remove(token)
    }

    /** Renames a subscription and, when [url] changed, reads the new link (the old one stays if the new one fails). */
    suspend fun update(id: String, name: String, url: String) {
        val current = _subscriptions.value.firstOrNull { it.id == id } ?: return
        val cleanUrl = url.trim()
        val cleanName = name.trim().ifBlank { current.name }
        if (cleanUrl == current.url || current.url.isBlank()) {
            replace(current.copy(name = cleanName))
            return
        }
        require(cleanUrl.startsWith("https://") || cleanUrl.startsWith("http://")) { "A subscription link starts with https://" }
        val fetched = fetch(cleanUrl)
        replace(current.copy(name = cleanName, url = cleanUrl, nodes = fetched.nodes, usage = fetched.usage, updatedAtMs = System.currentTimeMillis()))
    }

    /** The most reliable measured server, then low jitter, speed and delay; untested servers are the fallback. */
    fun bestNode(): ProxyNode? {
        // OpenVPN servers cannot be measured by Xray, so they are never the "fastest" pick.
        val candidates = _subscriptions.value.flatMap { it.nodes }.filterNot { it.isInfo || it.protocol == "openvpn" }
        val results = _results.value
        val measured = candidates.filter { results[it.id]?.pingMs != null }
        return sortNodes(measured, results, emptySet()).firstOrNull() ?: candidates.firstOrNull()
    }

    fun remove(id: String) {
        _subscriptions.value = _subscriptions.value.filterNot { it.id == id }
        save()
    }

    fun recordResult(node: ProxyNode, result: NodeResult) {
        _results.value = _results.value + (node.id to result)
    }

    fun saveResults() = save()

    private class Fetched(val nodes: List<ProxyNode>, val usage: SubscriptionUsage?, val title: String?)

    private suspend fun fetch(url: String): Fetched = withContext(Dispatchers.IO) {
        // The link holds the account's secret: log only the host.
        AppLogger.i(TAG, "fetching a subscription from ${runCatching { URI(url).host }.getOrNull()}")
        val request = Request.Builder().url(url).header("User-Agent", FETCH_AGENT).header("Accept", "*/*").build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "The subscription server answered HTTP ${response.code}." }
            val body = response.body?.string().orEmpty()
            val nodes = SubscriptionParser.parseNodes(body)
            AppLogger.i(TAG, "subscription read: ${nodes.size} entries, ${nodes.count { it.isInfo }} are information rows")
            Fetched(
                nodes,
                SubscriptionParser.parseUserInfo(response.header("subscription-userinfo")),
                SubscriptionParser.parseTitle(response.header("profile-title")),
            )
        }
    }

    private fun replace(updated: Subscription) {
        _subscriptions.value = _subscriptions.value.map { if (it.id == updated.id) updated else it }
        save()
    }

    @Synchronized
    private fun save() {
        runCatching {
            val array = JSONArray()
            for (s in _subscriptions.value) {
                array.put(JSONObject()
                    .put("id", s.id).put("name", s.name).put("url", s.url).put("updatedAt", s.updatedAtMs)
                    .put("nodes", JSONArray(s.nodes.map { it.raw }))
                    .apply {
                        s.usage?.let {
                            put("usage", JSONObject().put("up", it.uploaded).put("down", it.downloaded).put("total", it.total)
                                .apply { it.expiresAtEpochSeconds?.let { e -> put("expire", e) } })
                        }
                    })
            }
            val results = JSONObject()
            for ((id, r) in _results.value) {
                results.put(id, JSONObject()
                    .put("ping", r.pingMs ?: JSONObject.NULL)
                    .put("mbps", r.mbps ?: JSONObject.NULL)
                    .put("jitter", r.jitterMs ?: JSONObject.NULL)
                    .put("successRate", r.successRate ?: JSONObject.NULL)
                    .put("at", r.testedAtMs))
            }
            val remembered = JSONObject().apply { addresses.forEach { (id, ip) -> put(id, ip) } }
            file.writeText(JSONObject().put("subscriptions", array).put("results", results).put("addresses", remembered).toString())
        }.onFailure { AppLogger.w(TAG, "could not save the subscriptions", it) }
    }

    private fun load() {
        if (!file.isFile) return
        runCatching {
            val root = JSONObject(file.readText())
            val list = root.optJSONArray("subscriptions") ?: JSONArray()
            _subscriptions.value = (0 until list.length()).map { i ->
                val o = list.getJSONObject(i)
                val nodes = o.optJSONArray("nodes")?.let { a -> (0 until a.length()).mapNotNull { ShareLinks.parse(a.getString(it)) } }.orEmpty()
                Subscription(
                    id = o.getString("id"), name = o.getString("name"), url = o.getString("url"), nodes = nodes,
                    usage = o.optJSONObject("usage")?.let {
                        SubscriptionUsage(it.optLong("up"), it.optLong("down"), it.optLong("total"), it.optLong("expire").takeIf { e -> e > 0 })
                    },
                    updatedAtMs = o.optLong("updatedAt"),
                )
            }
            root.optJSONObject("addresses")?.let { a -> a.keys().forEach { id -> addresses[id] = a.getString(id) } }
            val results = root.optJSONObject("results")
            if (results != null) {
                _results.value = results.keys().asSequence().associateWith { id ->
                    val r = results.getJSONObject(id)
                    NodeResult(
                        r.optInt("ping", -1).takeIf { it >= 0 && !r.isNull("ping") },
                        r.optDouble("mbps", Double.NaN).takeIf { !it.isNaN() && !r.isNull("mbps") },
                        r.optLong("at"),
                        r.optInt("jitter", -1).takeIf { it >= 0 && !r.isNull("jitter") },
                        r.optDouble("successRate", Double.NaN).takeIf { !it.isNaN() && !r.isNull("successRate") },
                    )
                }
            }
        }.onFailure { AppLogger.w(TAG, "could not read the saved subscriptions", it) }
    }
}
