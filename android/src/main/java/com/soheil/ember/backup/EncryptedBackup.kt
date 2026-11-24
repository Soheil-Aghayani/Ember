package com.soheil.ember.backup

import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Password envelope shared with the Windows client: PBKDF2-HMAC-SHA256 followed by AES-256-GCM. */
object EncryptedBackup {
    private val magic = "EMBERBK1".toByteArray(Charsets.US_ASCII)
    private const val SALT_BYTES = 16
    private const val NONCE_BYTES = 12
    private const val KEY_BITS = 256
    private const val ITERATIONS = 150_000
    private const val TAG_BITS = 128
    private const val MAX_BYTES = 10_000_000

    fun encrypt(plain: ByteArray, password: String): ByteArray {
        require(password.codePointCount(0, password.length) in 8..128) { "Use a backup password between 8 and 128 characters." }
        require(plain.size <= MAX_BYTES) { "The backup is too large." }
        val random = SecureRandom()
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val key = derive(password, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(magic)
        val encrypted = cipher.doFinal(plain)
        return ByteArrayOutputStream(magic.size + salt.size + nonce.size + encrypted.size).apply {
            write(magic)
            write(salt)
            write(nonce)
            write(encrypted)
        }.toByteArray()
    }

    fun decrypt(data: ByteArray, password: String): ByteArray {
        require(data.size in (magic.size + SALT_BYTES + NONCE_BYTES + 16)..MAX_BYTES) { "That is not an Ember backup." }
        require(data.copyOfRange(0, magic.size).contentEquals(magic)) { "That is not an encrypted Ember backup." }
        val saltStart = magic.size
        val nonceStart = saltStart + SALT_BYTES
        val encryptedStart = nonceStart + NONCE_BYTES
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                derive(password, data.copyOfRange(saltStart, nonceStart)),
                GCMParameterSpec(TAG_BITS, data.copyOfRange(nonceStart, encryptedStart)),
            )
            cipher.updateAAD(magic)
            cipher.doFinal(data, encryptedStart, data.size - encryptedStart).also {
                require(it.size <= MAX_BYTES) { "The backup is too large." }
            }
        } catch (_: AEADBadTagException) {
            throw IllegalArgumentException("The password is incorrect or the backup is damaged.")
        } catch (failure: IllegalArgumentException) {
            throw failure
        } catch (_: Exception) {
            throw IllegalArgumentException("The password is incorrect or the backup is damaged.")
        }
    }

    private fun derive(password: String, salt: ByteArray): SecretKeySpec {
        // PBKDF2 over explicit UTF-8 bytes keeps passwords, including Persian text, interoperable with Rust on Windows.
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(password.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        var block = mac.doFinal(salt + byteArrayOf(0, 0, 0, 1))
        val derived = block.copyOf()
        repeat(ITERATIONS - 1) {
            block = mac.doFinal(block)
            for (index in derived.indices) derived[index] = (derived[index].toInt() xor block[index].toInt()).toByte()
        }
        return SecretKeySpec(derived, "AES").also { derived.fill(0); block.fill(0) }
    }
}
