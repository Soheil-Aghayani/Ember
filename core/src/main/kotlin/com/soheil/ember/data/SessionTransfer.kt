package com.soheil.ember.data

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Moves a signed-in session to another device without typing the account password there: the signed-in device
 * shows a [Created.code] and a [Created.passphrase]; the new device needs both. The code holds the OAuth refresh
 * token encrypted with a key made from the passphrase, so the code alone (for example in a chat) is useless.
 */
object SessionTransfer {
    const val VALID_MINUTES = 10L

    private const val PREFIX = "EMBER1-"
    private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    private const val PASSPHRASE_LENGTH = 10
    private const val KDF_ROUNDS = 150_000
    private const val SALT_BYTES = 16
    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128

    class Created(val code: String, val passphrase: String)

    private val random = SecureRandom()

    fun create(refreshToken: String, nowMillis: Long = System.currentTimeMillis()): Created {
        val passphrase = buildString { repeat(PASSPHRASE_LENGTH) { append(ALPHABET[random.nextInt(ALPHABET.length)]) } }
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val expires = nowMillis / 1000 + VALID_MINUTES * 60
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, key(passphrase, salt), GCMParameterSpec(TAG_BITS, nonce))
            updateAAD(PREFIX.toByteArray())
        }
        val sealed = cipher.doFinal("$expires\n$refreshToken".toByteArray())
        val code = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(salt + nonce + sealed)
        return Created(code, passphrase.chunked(5).joinToString("-"))
    }

    /** The refresh token inside [code], or a failure saying what is wrong with the code or passphrase. */
    fun open(code: String, passphrase: String, nowMillis: Long = System.currentTimeMillis()): Result<String> = runCatching {
        val clean = code.filterNot { it.isWhitespace() }
        require(clean.startsWith(PREFIX)) { "This is not an Ember sign-in code." }
        val raw = try {
            Base64.getUrlDecoder().decode(clean.removePrefix(PREFIX))
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("The code is damaged. Copy it again, all of it.")
        }
        require(raw.size > SALT_BYTES + NONCE_BYTES + TAG_BITS / 8) { "The code is too short. Copy it again, all of it." }
        val salt = raw.copyOfRange(0, SALT_BYTES)
        val nonce = raw.copyOfRange(SALT_BYTES, SALT_BYTES + NONCE_BYTES)
        val sealed = raw.copyOfRange(SALT_BYTES + NONCE_BYTES, raw.size)
        val plain = try {
            Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key(passphrase.uppercase().filter { it in ALPHABET }, salt), GCMParameterSpec(TAG_BITS, nonce))
                updateAAD(PREFIX.toByteArray())
            }.doFinal(sealed).decodeToString()
        } catch (_: java.security.GeneralSecurityException) {
            throw IllegalArgumentException("The passphrase does not match this code.")
        }
        val (expires, token) = plain.split('\n', limit = 2).let { (it.getOrNull(0)?.toLongOrNull() ?: 0L) to it.getOrNull(1).orEmpty() }
        require(token.isNotEmpty()) { "The code is damaged. Copy it again, all of it." }
        require(nowMillis / 1000 <= expires) { "This code has expired. Create a new one on the signed-in device." }
        token
    }

    private fun key(passphrase: String, salt: ByteArray): SecretKeySpec {
        val derived = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(passphrase.toCharArray(), salt, KDF_ROUNDS, 256)).encoded
        return SecretKeySpec(derived, "AES")
    }
}
