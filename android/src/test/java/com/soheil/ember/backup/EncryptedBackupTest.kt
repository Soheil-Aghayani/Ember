package com.soheil.ember.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class EncryptedBackupTest {
    @Test
    fun encryptedBytesRoundTripAndDoNotRevealTheirContents() {
        val plain = "server links and settings".toByteArray(Charsets.UTF_8)
        val encrypted = EncryptedBackup.encrypt(plain, "pässword-سلام")

        assertTrue(encrypted.copyOfRange(0, 8).contentEquals("EMBERBK1".toByteArray(Charsets.US_ASCII)))
        assertFalse(encrypted.toString(Charsets.ISO_8859_1).contains("server links"))
        assertArrayEquals(plain, EncryptedBackup.decrypt(encrypted, "pässword-سلام"))
    }

    @Test
    fun rejectsWrongPasswordsAndOutOfRangeLengths() {
        val encrypted = EncryptedBackup.encrypt(byteArrayOf(1, 2, 3), "a-password-that-works")

        assertThrows(IllegalArgumentException::class.java) {
            EncryptedBackup.decrypt(encrypted, "a-different-password")
        }
        assertThrows(IllegalArgumentException::class.java) {
            EncryptedBackup.encrypt(byteArrayOf(1), "short")
        }
        assertThrows(IllegalArgumentException::class.java) {
            EncryptedBackup.encrypt(byteArrayOf(1), "x".repeat(129))
        }
    }

    @Test
    fun nonBmpCharactersAreCountedAsUnicodeCharacters() {
        val password = "🔐".repeat(8)
        val encrypted = EncryptedBackup.encrypt(byteArrayOf(7), password)
        assertArrayEquals(byteArrayOf(7), EncryptedBackup.decrypt(encrypted, password))
    }
}
