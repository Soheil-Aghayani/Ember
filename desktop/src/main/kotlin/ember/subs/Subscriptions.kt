package ember.subs

import com.soheil.ember.data.AppLogger
import java.io.File
import java.net.URI
import java.util.concurrent.TimeUnit
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
data class NodeResult(val pingMs: Int?, val mbps: Double?, val testedAtMs: Long)

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

object SubscriptionParser {

    fun parseNodes(body: String): List<ProxyNode> {
        val text = body.trim()
        val lines = if ("://" in text) {
            text.lines()
        } else {
            runCatching { String(ShareLinks.decodeBase64(text)).lines() }.getOrDefault(emptyList())
        }
        val nodes = lines.mapNotNull(ShareLinks::parse).distinctBy { it.id }
        if (nodes.isEmpty()) {
            if (text.startsWith("{") || text.startsWith("[")) {
                error("This subscription is in a format Ember does not read yet (JSON). Ask the provider for the standard link list.")
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

    /** The last IPv4 address each server's name resolved to, used when a later lookup fails. */
    private val addresses = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun rememberAddress(node: ProxyNode, ip: String) {
        if (addresses.put(node.id, ip) != ip) save()
    }

    fun rememberedAddress(node: ProxyNode): String? = addresses[node.id]

    init {
        load()
    }

    fun nodeById(id: String): ProxyNode? = _subscriptions.value.firstNotNullOfOrNull { s -> s.nodes.firstOrNull { it.id == id } }

    suspend fun add(url: String, name: String): Subscription {
        val clean = url.trim()
        val host = runCatching { URI(clean).host }.getOrNull()
        require(clean.startsWith("https://") || clean.startsWith("http://")) { "A subscription link starts with https://" }
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

    suspend fun refresh(id: String) {
        val current = _subscriptions.value.firstOrNull { it.id == id } ?: return
        val fetched = fetch(current.url)
        replace(current.copy(nodes = fetched.nodes, usage = fetched.usage, updatedAtMs = System.currentTimeMillis()))
    }

    /** Renames a subscription and, when [url] changed, reads the new link (the old one stays if the new one fails). */
    suspend fun update(id: String, name: String, url: String) {
        val current = _subscriptions.value.firstOrNull { it.id == id } ?: return
        val cleanUrl = url.trim()
        val cleanName = name.trim().ifBlank { current.name }
        if (cleanUrl == current.url) {
            replace(current.copy(name = cleanName))
            return
        }
        require(cleanUrl.startsWith("https://") || cleanUrl.startsWith("http://")) { "A subscription link starts with https://" }
        val fetched = fetch(cleanUrl)
        replace(current.copy(name = cleanName, url = cleanUrl, nodes = fetched.nodes, usage = fetched.usage, updatedAtMs = System.currentTimeMillis()))
    }

    /** The tested server with the best speed (or, failing that, the lowest delay) across all subscriptions. */
    fun bestNode(): ProxyNode? {
        val candidates = _subscriptions.value.flatMap { it.nodes }.filterNot { it.isInfo }
        val results = _results.value
        return candidates.filter { results[it.id]?.pingMs != null }.maxWithOrNull(
            compareBy<ProxyNode> { results[it.id]?.mbps ?: -1.0 }.thenBy { -(results[it.id]?.pingMs ?: Int.MAX_VALUE) },
        ) ?: candidates.firstOrNull()
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
                results.put(id, JSONObject().put("ping", r.pingMs ?: JSONObject.NULL).put("mbps", r.mbps ?: JSONObject.NULL).put("at", r.testedAtMs))
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
                    )
                }
            }
        }.onFailure { AppLogger.w(TAG, "could not read the saved subscriptions", it) }
    }
}
