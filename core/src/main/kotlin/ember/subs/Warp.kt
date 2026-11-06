package ember.subs

import java.security.SecureRandom
import java.math.BigInteger
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
        val private = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val public = X25519.publicKey(private)
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

/**
 * The public half of an X25519 key (RFC 7748, scalar times the base point). Written out so it works on every Android
 * version: the platform only offers X25519 from Android 13.
 */
object X25519 {
    private val P = BigInteger.ONE.shiftLeft(255) - BigInteger.valueOf(19)
    private val A24 = BigInteger.valueOf(121665)

    fun publicKey(privateKey: ByteArray): ByteArray {
        require(privateKey.size == 32) { "an X25519 key is 32 bytes" }
        val clamped = privateKey.copyOf()
        clamped[0] = (clamped[0].toInt() and 248).toByte()
        clamped[31] = ((clamped[31].toInt() and 127) or 64).toByte()
        val k = BigInteger(1, clamped.reversedArray())
        val x1 = BigInteger.valueOf(9)
        var x2 = BigInteger.ONE
        var z2 = BigInteger.ZERO
        var x3 = x1
        var z3 = BigInteger.ONE
        var swap = false
        for (bit in 254 downTo 0) {
            val kt = k.testBit(bit)
            if (swap != kt) {
                x2 = x3.also { x3 = x2 }
                z2 = z3.also { z3 = z2 }
            }
            swap = kt
            val a = (x2 + z2).mod(P)
            val aa = (a * a).mod(P)
            val b = (x2 - z2).mod(P)
            val bb = (b * b).mod(P)
            val e = (aa - bb).mod(P)
            val c = (x3 + z3).mod(P)
            val d = (x3 - z3).mod(P)
            val da = (d * a).mod(P)
            val cb = (c * b).mod(P)
            x3 = (da + cb).pow(2).mod(P)
            z3 = (x1 * (da - cb).pow(2)).mod(P)
            x2 = (aa * bb).mod(P)
            z2 = (e * (aa + A24 * e)).mod(P)
        }
        if (swap) {
            x2 = x3.also { x3 = x2 }
            z2 = z3.also { z3 = z2 }
        }
        val result = (x2 * z2.modPow(P - BigInteger.TWO, P)).mod(P)
        val out = ByteArray(32)
        val big = result.toByteArray().reversedArray() // little-endian, possibly with a sign byte at the end
        System.arraycopy(big, 0, out, 0, minOf(32, big.size))
        return out
    }
}
