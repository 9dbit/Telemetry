package com.telemetry.app.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TelemetryCryptoTest {
    @Test
    fun signedHellosVerifyAndBindDeviceIdentity() {
        val identity = TelemetryCrypto.newIdentity()
        val hello = TelemetryCrypto.createHello(identity, TelemetryCrypto.newEphemeral())
        assertEquals(identity.deviceId, hello.deviceId)
        assertTrue(TelemetryCrypto.verifyHello(hello))
        assertFalse(TelemetryCrypto.verifyHello(hello.copy(deviceId = "tlm:device:tampered")))
    }

    @Test
    fun bothPeersDeriveSameEphemeralSessionKeyAndSafetyCode() {
        val a = TelemetryCrypto.newIdentity()
        val b = TelemetryCrypto.newIdentity()
        val ae = TelemetryCrypto.newEphemeral()
        val be = TelemetryCrypto.newEphemeral()
        val ah = TelemetryCrypto.createHello(a, ae)
        val bh = TelemetryCrypto.createHello(b, be)

        val aKey = TelemetryCrypto.deriveSessionKey(ae, ah, bh)
        val bKey = TelemetryCrypto.deriveSessionKey(be, bh, ah)
        assertArrayEquals(aKey, bKey)
        assertEquals(TelemetryCrypto.safetyCode(ah, bh), TelemetryCrypto.safetyCode(bh, ah))
    }

    @Test
    fun encryptedTextOnlyDecryptsWithSharedSessionKey() {
        val a = TelemetryCrypto.newIdentity()
        val b = TelemetryCrypto.newIdentity()
        val ae = TelemetryCrypto.newEphemeral()
        val be = TelemetryCrypto.newEphemeral()
        val ah = TelemetryCrypto.createHello(a, ae)
        val bh = TelemetryCrypto.createHello(b, be)
        val aKey = TelemetryCrypto.deriveSessionKey(ae, ah, bh)
        val bKey = TelemetryCrypto.deriveSessionKey(be, bh, ah)

        val message = TelemetryCrypto.encryptText(aKey, a.deviceId, b.deviceId, "Hello Telemetry")
        assertEquals("Hello Telemetry", TelemetryCrypto.decryptText(bKey, message))
    }

    @Test
    fun deliveryReceiptIsSignedByRecipient() {
        val sender = TelemetryCrypto.newIdentity()
        val recipient = TelemetryCrypto.newIdentity()
        val message = EncryptedMessage(
            messageId = "m-1",
            senderId = sender.deviceId,
            recipientId = recipient.deviceId,
            createdAt = 1L,
            nonce = ByteArray(12),
            ciphertext = byteArrayOf(1, 2, 3)
        )
        val receipt = TelemetryCrypto.createReceipt(recipient, message, receivedAt = 2L)
        assertTrue(TelemetryCrypto.verifyReceipt(receipt, recipient.signingPublicKey))
        assertFalse(
            TelemetryCrypto.verifyReceipt(receipt.copy(messageId = "tampered"), recipient.signingPublicKey)
        )
    }
}
