package com.telemetry.app.transport

import com.telemetry.app.crypto.DeviceIdentity
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidControlEnvelopeCodecTest {
    private val sender = DeviceIdentity(
        ed25519Seed = ByteArray(32) { (it + 1).toByte() },
        x25519Seed = ByteArray(32) { (0x40 + it).toByte() }
    )
    private val recipient = DeviceIdentity(
        ed25519Seed = ByteArray(32) { (0x60 + it).toByte() },
        x25519Seed = ByteArray(32) { (0x20 + it).toByte() }
    )
    private val key = ByteArray(32) { (0xa0 + it).toByte() }
    private val nonce = ByteArray(12) { (0x10 + it).toByte() }

    @Test
    fun createsVerifiesAndOpensTce1() {
        val codec = AndroidControlEnvelopeCodec(sender)
        val plaintext = "{\"kind\":\"media-manifest\",\"assetId\":\"asset-demo\"}".toByteArray()
        val wire = codec.createAndEncode(
            sessionKey = key,
            recipientId = recipient.deviceId,
            contentType = "application/telemetry+media-manifest",
            plaintext = plaintext,
            conversationId = "conversation-tce1-test",
            messageId = "message-tce1-test",
            createdAtEpochMs = 1_796_000_000_123L,
            hopLimit = 8,
            nonce = nonce
        )

        assertEquals("TCE1", String(wire.copyOfRange(0, 4), Charsets.US_ASCII))
        val decoded = codec.decode(wire)
        assertEquals(sender.deviceId, decoded.senderId)
        assertEquals(recipient.deviceId, decoded.recipientId)
        assertEquals("application/telemetry+media-manifest", decoded.contentType)
        assertEquals(8, decoded.hopLimit)

        val opened = AndroidControlEnvelopeCodec(recipient).verifyAndOpen(
            bytes = wire,
            sessionKey = key,
            signingPublicKey = sender.signingPublicKey,
            expectedRecipientId = recipient.deviceId
        )
        assertArrayEquals(plaintext, opened.plaintext)
        assertEquals("message-tce1-test", opened.messageId)
    }

    @Test
    fun rejectsTamperedSignatureAndCiphertext() {
        val codec = AndroidControlEnvelopeCodec(sender)
        val wire = codec.createAndEncode(
            sessionKey = key,
            recipientId = recipient.deviceId,
            contentType = "application/telemetry+control",
            plaintext = "hello".toByteArray(),
            conversationId = "conversation-tce1-test",
            messageId = "message-tce1-test",
            createdAtEpochMs = 1_796_000_000_123L,
            nonce = nonce
        )

        val signatureTampered = wire.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        val signatureRejected = runCatching {
            AndroidControlEnvelopeCodec(recipient).verifyAndOpen(
                signatureTampered,
                key,
                sender.signingPublicKey,
                recipient.deviceId
            )
        }.isFailure
        assertTrue(signatureRejected)

        val ciphertextTampered = wire.copyOf()
        val decoded = codec.decode(wire)
        val encodedAgain = codec.encode(decoded.copy(payload = decoded.payload.copy(
            ciphertext = decoded.payload.ciphertext.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        )))
        val cipherRejected = runCatching {
            AndroidControlEnvelopeCodec(recipient).verifyAndOpen(
                encodedAgain,
                key,
                sender.signingPublicKey,
                recipient.deviceId
            )
        }.isFailure
        assertTrue(cipherRejected)
        assertTrue(ciphertextTampered.isNotEmpty())
    }
}
