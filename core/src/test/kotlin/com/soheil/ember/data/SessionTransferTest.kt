package com.soheil.ember.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class SessionTransferTest {
    @Test
    fun qrPayloadOpensTheEncryptedRefreshToken() {
        val transfer = SessionTransfer.create(TEST_TOKEN, NOW)

        assertEquals("${transfer.code}#${transfer.passphrase}", transfer.qrPayload)
        assertEquals(TEST_TOKEN, SessionTransfer.openPayload(transfer.qrPayload, NOW).getOrThrow())
    }

    @Test
    fun wrongPassphraseIsRejected() {
        val transfer = SessionTransfer.create(TEST_TOKEN, NOW)
        val first = transfer.passphrase.first()
        val replacement = if (first == 'A') 'B' else 'A'
        val wrongPassphrase = replacement + transfer.passphrase.drop(1)

        assertFailureMessage(
            "The passphrase does not match this code.",
            SessionTransfer.open(transfer.code, wrongPassphrase, NOW).exceptionOrNull(),
        )
    }

    @Test
    fun damagedCodeIsRejected() {
        val transfer = SessionTransfer.create(TEST_TOKEN, NOW)

        assertFailureMessage(
            "The code is damaged. Copy it again, all of it.",
            SessionTransfer.open("${transfer.code}!", transfer.passphrase, NOW).exceptionOrNull(),
        )
    }

    @Test
    fun expiredCodeIsRejected() {
        val transfer = SessionTransfer.create(TEST_TOKEN, NOW)

        assertFailureMessage(
            "This code has expired. Create a new one on the signed-in device.",
            SessionTransfer.open(transfer.code, transfer.passphrase, NOW + 601_000).exceptionOrNull(),
        )
    }

    private fun assertFailureMessage(expected: String, failure: Throwable?) {
        assertNotNull("Expected sign-in transfer to fail", failure)
        assertEquals(expected, failure?.message)
    }

    private companion object {
        const val TEST_TOKEN = "fake-refresh-token-for-local-test"
        const val NOW = 1_700_000_000_000L
    }
}
