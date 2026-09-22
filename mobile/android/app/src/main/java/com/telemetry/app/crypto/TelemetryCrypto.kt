package com.telemetry.app.crypto

import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

private const val HELLO_VERSION = "telemetry/m1b/hello"
private const val SESSION_CONTEXT = "telemetry/m1b/session"
private const val MESSAGE_CONTEXT = "telemetry/m1b/message"
private const val RECEIPT_CONTEXT = "telemetry/m1b/receipt"
private const val MAX_CLOCK_SKEW_MS = 15 * 60 * 1000L

data class DeviceIdentity(
    val ed25519Seed: ByteArray,
    val x25519Seed: ByteArray
) {
    val signingPublicKey: ByteArray
        get() = Ed25519PrivateKeyParameters(ed25519Seed, 0).generatePublicKey().encoded
    val exchangePublicKey: ByteArray
        get() = X25519PrivateKeyParameters(x25519Seed, 0).generatePublicKey().encoded
    val deviceId: String
        get() = TelemetryCrypto.deviceId(signingPublicKey)
}

data class EphemeralKeyPair(
    val privateSeed: ByteArray,
    val publicKey: ByteArray
)

data class SessionHello(
    val deviceId: String,
    val signingPublicKey: ByteArray,
    val exchangePublicKey: ByteArray,
    val ephemeralPublicKey: ByteArray,
    val nonce: ByteArray,
    val issuedAt: Long,
    val signature: ByteArray
)

data class EncryptedMessage(
    val messageId: String,
    val senderId: String,
    val recipientId: String,
    val createdAt: Long,
    val nonce: ByteArray,
    val ciphertext: ByteArray
)

data class DeliveryReceipt(
    val messageId: String,
    val senderId: String,
    val recipientId: String,
    val receivedAt: Long,
    val status: String,
    val signature: ByteArray
)

object TelemetryCrypto {
    private val random = SecureRandom()

    fun newIdentity(): DeviceIdentity = DeviceIdentity(
        ed25519Seed = randomBytes(32),
        x25519Seed = randomBytes(32)
    )

    fun newEphemeral(): EphemeralKeyPair {
        val seed = randomBytes(32)
        val privateKey = X25519PrivateKeyParameters(seed, 0)
        return EphemeralKeyPair(seed, privateKey.generatePublicKey().encoded)
    }

    fun deviceId(signingPublicKey: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(signingPublicKey)
        return "tlm:device:" + digest.take(16).joinToString("") { "%02x".format(it) }
    }

    fun createHello(
        identity: DeviceIdentity,
        ephemeral: EphemeralKeyPair,
        issuedAt: Long = System.currentTimeMillis(),
        nonce: ByteArray = randomBytes(16)
    ): SessionHello {
        val unsigned = SessionHello(
            deviceId = identity.deviceId,
            signingPublicKey = identity.signingPublicKey,
            exchangePublicKey = identity.exchangePublicKey,
            ephemeralPublicKey = ephemeral.publicKey,
            nonce = nonce,
            issuedAt = issuedAt,
            signature = byteArrayOf()
        )
        return unsigned.copy(signature = sign(identity.ed25519Seed, helloCanonical(unsigned)))
    }

    fun verifyHello(
        hello: SessionHello,
        now: Long = System.currentTimeMillis(),
        maxClockSkewMs: Long = MAX_CLOCK_SKEW_MS
    ): Boolean = try {
        if (hello.signingPublicKey.size != 32 || hello.exchangePublicKey.size != 32 ||
            hello.ephemeralPublicKey.size != 32 || hello.nonce.size != 16 ||
            hello.signature.size != 64
        ) return false
        if (deviceId(hello.signingPublicKey) != hello.deviceId) return false
        if (abs(now - hello.issuedAt) > maxClockSkewMs) return false
        verify(hello.signingPublicKey, helloCanonical(hello), hello.signature)
    } catch (_: Throwable) {
        false
    }

    fun deriveSessionKey(
        localEphemeral: EphemeralKeyPair,
        localHello: SessionHello,
        remoteHello: SessionHello
    ): ByteArray {
        require(verifyHello(localHello)) { "local hello is invalid or expired" }
        require(verifyHello(remoteHello)) { "remote hello is invalid or expired" }

        val privateKey = X25519PrivateKeyParameters(localEphemeral.privateSeed, 0)
        val peerPublic = X25519PublicKeyParameters(remoteHello.ephemeralPublicKey, 0)
        val sharedSecret = ByteArray(32)
        privateKey.generateSecret(peerPublic, sharedSecret, 0)

        val ordered = listOf(localHello, remoteHello).sortedBy { it.deviceId }
        val info = buildString {
            append(SESSION_CONTEXT)
            ordered.forEach {
                append('|').append(it.deviceId)
                append('|').append(hex(it.nonce))
                append('|').append(hex(it.ephemeralPublicKey))
            }
        }.toByteArray(StandardCharsets.UTF_8)

        val hkdf = HKDFBytesGenerator(SHA256Digest())
        hkdf.init(HKDFParameters(sharedSecret, null, info))
        return ByteArray(32).also { hkdf.generateBytes(it, 0, it.size) }
    }

    fun safetyCode(a: SessionHello, b: SessionHello): String {
        val ordered = listOf(a, b).sortedBy { it.deviceId }
        val digest = MessageDigest.getInstance("SHA-256")
        ordered.forEach {
            digest.update(it.deviceId.toByteArray(StandardCharsets.UTF_8))
            digest.update(it.signingPublicKey)
            digest.update(it.ephemeralPublicKey)
            digest.update(it.nonce)
        }
        val hash = digest.digest()
        val value = (ByteBuffer.wrap(hash.copyOfRange(0, 4)).int.toLong() and 0xffffffffL) % 1_000_000L
        return value.toString().padStart(6, '0').chunked(3).joinToString(" ")
    }

    fun encryptText(
        sessionKey: ByteArray,
        senderId: String,
        recipientId: String,
        text: String,
        createdAt: Long = System.currentTimeMillis(),
        messageId: String = UUID.randomUUID().toString()
    ): EncryptedMessage {
        require(sessionKey.size == 32)
        val nonce = randomBytes(12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(sessionKey, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(messageAad(messageId, senderId, recipientId, createdAt))
        val encrypted = cipher.doFinal(text.toByteArray(StandardCharsets.UTF_8))
        return EncryptedMessage(messageId, senderId, recipientId, createdAt, nonce, encrypted)
    }

    fun decryptText(sessionKey: ByteArray, message: EncryptedMessage): String {
        require(sessionKey.size == 32)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(sessionKey, "AES"), GCMParameterSpec(128, message.nonce))
        cipher.updateAAD(messageAad(message.messageId, message.senderId, message.recipientId, message.createdAt))
        return String(cipher.doFinal(message.ciphertext), StandardCharsets.UTF_8)
    }

    fun createReceipt(
        identity: DeviceIdentity,
        message: EncryptedMessage,
        receivedAt: Long = System.currentTimeMillis(),
        status: String = "DELIVERED"
    ): DeliveryReceipt {
        val unsigned = DeliveryReceipt(
            messageId = message.messageId,
            senderId = message.senderId,
            recipientId = identity.deviceId,
            receivedAt = receivedAt,
            status = status,
            signature = byteArrayOf()
        )
        return unsigned.copy(signature = sign(identity.ed25519Seed, receiptCanonical(unsigned)))
    }

    fun verifyReceipt(receipt: DeliveryReceipt, peerSigningPublicKey: ByteArray): Boolean = try {
        if (deviceId(peerSigningPublicKey) != receipt.recipientId) return false
        verify(peerSigningPublicKey, receiptCanonical(receipt), receipt.signature)
    } catch (_: Throwable) {
        false
    }

    private fun helloCanonical(hello: SessionHello): ByteArray = joinCanonical(
        HELLO_VERSION,
        hello.deviceId,
        hex(hello.signingPublicKey),
        hex(hello.exchangePublicKey),
        hex(hello.ephemeralPublicKey),
        hex(hello.nonce),
        hello.issuedAt.toString()
    )

    private fun receiptCanonical(receipt: DeliveryReceipt): ByteArray = joinCanonical(
        RECEIPT_CONTEXT,
        receipt.messageId,
        receipt.senderId,
        receipt.recipientId,
        receipt.receivedAt.toString(),
        receipt.status
    )

    private fun messageAad(messageId: String, senderId: String, recipientId: String, createdAt: Long): ByteArray =
        joinCanonical(MESSAGE_CONTEXT, messageId, senderId, recipientId, createdAt.toString())

    private fun joinCanonical(vararg parts: String): ByteArray =
        parts.joinToString("\u001f").toByteArray(StandardCharsets.UTF_8)

    private fun sign(seed: ByteArray, bytes: ByteArray): ByteArray {
        val signer = Ed25519Signer()
        signer.init(true, Ed25519PrivateKeyParameters(seed, 0))
        signer.update(bytes, 0, bytes.size)
        return signer.generateSignature()
    }

    private fun verify(publicKey: ByteArray, bytes: ByteArray, signature: ByteArray): Boolean {
        val signer = Ed25519Signer()
        signer.init(false, Ed25519PublicKeyParameters(publicKey, 0))
        signer.update(bytes, 0, bytes.size)
        return signer.verifySignature(signature)
    }

    private fun randomBytes(size: Int): ByteArray = ByteArray(size).also(random::nextBytes)
    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}
