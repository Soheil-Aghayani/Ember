package com.soheil.ember.data

import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** What FxA needs to turn the browser's answer into tokens, see [FxaAuthRepository.signInWithAuthorizationCode]. */
class BrowserAuthorization(val code: String, val verifier: String, val redirectUri: String)

/**
 * Sign-in in the user's own browser, the way Mozilla's own apps do it: the page at accounts.firefox.com takes the
 * email and password (or a passkey, Google or Apple sign-in), then sends the browser back to a port this app listens
 * on, with a one-time code. The app never sees the password, and a real browser gets through Mozilla's bot check.
 */
class BrowserSignIn(private val openBrowser: (String) -> Unit) {

    suspend fun authorize(timeoutSeconds: Int = 300): Result<BrowserAuthorization> = withContext(Dispatchers.IO) {
        runCatching {
            val random = SecureRandom()
            val verifier = ByteArray(48).also(random::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
            val challenge = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
            val state = ByteArray(16).also(random::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
                server.soTimeout = 1000
                val redirect = "http://127.0.0.1:${server.localPort}/callback"
                openBrowser(authorizationUrl(redirect, state, challenge))

                val deadline = System.currentTimeMillis() + timeoutSeconds * 1000L
                while (System.currentTimeMillis() < deadline) {
                    currentCoroutineContext().ensureActive()
                    val socket = try {
                        server.accept()
                    } catch (_: SocketTimeoutException) {
                        continue
                    }
                    socket.use {
                        it.soTimeout = 5000
                        val line = it.getInputStream().bufferedReader().readLine().orEmpty()
                        val query = line.split(' ').getOrNull(1)?.substringAfter('?', "").orEmpty()
                        val params = query.split('&').filter { p -> p.isNotEmpty() }.associate { p ->
                            val (k, v) = p.split('=', limit = 2).let { kv -> kv[0] to kv.getOrElse(1) { "" } }
                            URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8")
                        }
                        val ok = params["state"] == state && !params["code"].isNullOrEmpty()
                        val message = if (ok) "You are signed in. You can close this tab and return to Ember."
                        else "Sign-in was not completed. Return to Ember and try again."
                        val body = "<!doctype html><meta charset=utf-8><title>Ember</title>" +
                            "<body style=\"font-family:system-ui;text-align:center;margin-top:20vh\"><h2>$message</h2>"
                        val bytes = body.toByteArray()
                        it.getOutputStream().apply {
                            write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray())
                            write(bytes)
                            flush()
                        }
                        if (ok) return@runCatching BrowserAuthorization(params.getValue("code"), verifier, redirect)
                        val error = params["error_description"] ?: params["error"]
                        if (error != null) throw IllegalStateException("Mozilla refused the sign-in: $error")
                    }
                }
                throw IllegalStateException("Sign-in in the browser took too long. Try again.")
            }
        }.onFailure { if (it is CancellationException) throw it }
    }

    private fun authorizationUrl(redirect: String, state: String, challenge: String): String {
        fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        return "https://accounts.firefox.com/authorization?client_id=${enc(FIREFOX_CLIENT_ID)}&scope=${enc(OAUTH_SCOPE)}" +
            "&state=${enc(state)}&response_type=code&access_type=offline&code_challenge=${enc(challenge)}" +
            "&code_challenge_method=S256&redirect_uri=${enc(redirect)}"
    }
}
