package com.telemetry.app.transport

import com.telemetry.app.crypto.DeviceIdentity
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

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
    fun createsVerifiesAndOpensTce1WithExactCrossPlatformVector() {
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

        assertEquals("tlm:device:65b60673d6ed884bf01c2c222d82ada0", sender.deviceId)
        assertEquals("tlm:device:68894d58f18f2c34d49eb2f4b110e042", recipient.deviceId)
        assertEquals("TCE1", String(wire.copyOfRange(0, 4), Charsets.US_ASCII))
        val decoded = codec.decode(wire)
        assertEquals(sender.deviceId, decoded.senderId)
        assertEquals(recipient.deviceId, decoded.recipientId)
        assertEquals("application/telemetry+media-manifest", decoded.contentType)
        assertEquals(8, decoded.hopLimit)

        val expectedCanonical = "{\"protocol\":\"telemetry/0.1\",\"messageId\":\"message-tce1-test\",\"conversationId\":\"conversation-tce1-test\",\"senderId\":\"tlm:device:65b60673d6ed884bf01c2c222d82ada0\",\"recipientId\":\"tlm:device:68894d58f18f2c34d49eb2f4b110e042\",\"createdAt\":\"2026-11-30T00:53:20.123Z\",\"hopLimit\":8,\"contentType\":\"application/telemetry+media-manifest\",\"payload\":{\"algorithm\":\"AES-256-GCM\",\"keyAgreement\":\"X25519-HKDF-SHA256\",\"nonce\":\"EBESExQVFhcYGRob\",\"ciphertext\":\"75QeZ7Ajl4i4FAl18FKR2ZI8damKjMfgdbliX_SiB2HGaMffvR9dFr3-4qoaeRpc\",\"tag\":\"qEOgRrWCLm9hkSrlrhuaFw\"}}"
        assertEquals(expectedCanonical, codec.canonicalJson(decoded))
        assertEquals(
            "yPEj1nqtKbGhoiuzLBJYsI8P6WZ5rXM4NVukiSu2xqpU2TguWYTlCq0425S3JlbuvUy6bbaAZYdytYWD4MGOBQ",
            Base64.getUrlEncoder().withoutPadding().encodeToString(decoded.signature)
        )
        assertEquals(
            "54434531000d74656c656d657472792f302e3100116d6573736167652d746365312d746573740016636f6e766572736174696f6e2d746365312d74657374002b746c6d3a6465766963653a3635623630363733643665643838346266303163326332323264383261646130002b746c6d3a6465766963653a36383839346435386631386632633334643439656232663462313130653034320018323032362d31312d33305430303a35333a32302e3132335a0800246170706c69636174696f6e2f74656c656d657472792b6d656469612d6d616e6966657374101112131415161718191a1b00000030ef941e67b0239788b8140975f05291d9923c75a98a8cc7e075b9625ff4a20761c668c7dfbd1f5d16bdfee2aa1a791a5ca843a046b5822e6f61912ae5ae1b9a17c8f123d67aad29b1a1a22bb32c1258b08f0fe96679ad7338355ba4892bb6c6aa54d9382e5984e50aad38db94b72656eebd4cba6db680658772b58583e0c18e05",
            wire.joinToString("") { "%02x".format(it) }
        )

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
    }
}
