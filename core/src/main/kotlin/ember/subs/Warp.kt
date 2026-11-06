package ember.subs

import java.security.KeyPairGenerator
import java.time.Instant
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Cloudflare WARP, the free VPN of Cloudflare's 1.1.1.1 app. It is WireGuard: Ember registers a device with
 * Cloudflare (the same request the official app makes), gets the tunnel's addresses, and the result runs like any
 * WireGuard server. WARP has one exit that follows the user's location; there is no choice of country.
 */
object Warp {

    private const val API = "https://api.cloudflareclient.com/v0a2158/reg"

    /** The ports WARP listens on; the first is the usual one, the others help when a network blocks it. */
    private val PORTS = listOf(2408, 500, 4500)

    class Registration(
        val privateKey: String,
        val addresses: List<String>,
        val peerPublicKey: String,
        val endpointHost: String,
        /** Three bytes Cloudflare ties to the device; WARP drops traffic that does not carry them. */
        val reserved: List<Int>,
    )

    /** A fresh X25519 key pair as the base64 text WireGuard uses: (private, public). */
    fun newKeyPair(): Pair<String, String> {
        val pair = KeyPairGenerator.getInstance("X25519").generateKeyPair()
        // the raw 32 bytes are the tail of the standard encodings
        val private = pair.private.encoded.takeLast(32).toByteArray()
        val public = pair.public.encoded.takeLast(32).toByteArray()
        val b64 = Base64.getEncoder()
        return b64.encodeToString(private) to b64.encodeToString(public)
    }

    /** Reads Cloudflare's answer to the registration. */
    fun parseRegistration(json: String, privateKey: String): Registration {
        val root = JSONObject(json)
        val config = root.getJSONObject("config")
        val peer = config.getJSONArray("peers").getJSONObject(0)
        val addresses = config.getJSONObject("interface").getJSONObject("addresses")
        val list = listOf(addresses.optString("v4"), addresses.optString("v6")).filter { it.isNotBlank() }
            .map { if ('/' in it) it else if (':' in it) "$it/128" else "$it/32" }
        require(list.isNotEmpty()) { "Cloudflare sent no tunnel address." }
        val host = peer.getJSONObject("endpoint").optString("host").substringBeforeLast(':').ifBlank { "engage.cloudflareclient.com" }
        val reserved = runCatching { Base64.getDecoder().decode(config.getString("client_id")).map { it.toInt() and 0xFF } }
            .getOrDefault(emptyList()).take(3)
        return Registration(privateKey, list, peer.getString("public_key"), host, reserved)
    }

    /** One WireGuard file per port, named for it. */
    fun configs(reg: Registration): List<Pair<String, String>> = PORTS.map { port ->
        val text = buildString {
            appendLine("[Interface]")
            appendLine("PrivateKey = ${reg.privateKey}")
            appendLine("Address = ${reg.addresses.joinToString(", ")}")
            appendLine("MTU = 1280")
            if (reg.reserved.size == 3) appendLine("Reserved = ${reg.reserved.joinToString(", ")}")
            appendLine()
            appendLine("[Peer]")
            appendLine("PublicKey = ${reg.peerPublicKey}")
            appendLine("AllowedIPs = 0.0.0.0/0, ::/0")
            appendLine("Endpoint = ${reg.endpointHost}:$port")
        }
        (if (port == PORTS.first()) "Cloudflare WARP" else "Cloudflare WARP ($port)") to text
    }

    /** Registers a new free device with Cloudflare. */
    suspend fun register(): Registration = withContext(Dispatchers.IO) {
        val (privateKey, publicKey) = newKeyPair()
        val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
        val json = "application/json".toMediaType()
        val body = JSONObject()
            .put("key", publicKey).put("install_id", "").put("fcm_token", "").put("tos", Instant.now().toString())
            .put("model", "PC").put("serial_number", "").put("locale", "en_US")
        val request = Request.Builder().url(API).post(body.toString().toRequestBody(json))
            .header("User-Agent", "okhttp/3.12.1").header("CF-Client-Version", "a-6.3-1922").build()
        val answer = client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Cloudflare answered HTTP ${response.code}. Try again later." }
            response.body?.string().orEmpty()
        }
        val registration = parseRegistration(answer, privateKey)
        // switch WARP on for the new device (best effort; the tunnel details above are already usable)
        runCatching {
            val root = JSONObject(answer)
            val patch = Request.Builder().url("$API/${root.getString("id")}")
                .patch(JSONObject().put("warp_enabled", true).toString().toRequestBody(json))
                .header("User-Agent", "okhttp/3.12.1").header("CF-Client-Version", "a-6.3-1922")
                .header("Authorization", "Bearer ${root.getString("token")}").build()
            client.newCall(patch).execute().close()
        }
        registration
    }
}
