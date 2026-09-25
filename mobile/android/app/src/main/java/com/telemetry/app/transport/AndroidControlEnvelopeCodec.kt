package com.telemetry.app.transport

import com.telemetry.app.crypto.DeviceIdentity
import com.telemetry.app.crypto.TelemetryCrypto
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

private const val CONTROL_PROTOCOL = "telemetry/0.1"
private const val CONTROL_ALGORITHM = "AES-256-GCM"
private const val CONTROL_KEY_AGREEMENT = "X25519-HKDF-SHA256"
private const val CONTROL_MAX_STRING_BYTES = 1024
private const val CONTROL_MAX_CIPHERTEXT_BYTES = 64 * 1024
private const val CONTROL_NONCE_BYTES = 12
private const val CONTROL_TAG_BYTES = 16
private const val CONTROL_SIGNATURE_BYTES = 64

private val CONTROL_ISO_MILLIS: DateTimeFormatter = DateTimeFormatter
    .ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
    .withZone(ZoneOffset.UTC)

data class NativeControlSealedPayload(
    val nonce: ByteArray,
    val ciphertext: ByteArray,
    val tag: ByteArray
) {
    init {
        require(nonce.size == CONTROL_NONCE_BYTES) { "control nonce must be 12 bytes" }
        require(ciphertext.isNotEmpty() && ciphertext.size <= CONTROL_MAX_CIPHERTEXT_BYTES) { "control ciphertext length invalid" }
        require(tag.size == CONTROL_TAG_BYTES) { "control tag must be 16 bytes" }
    }
}

data class NativeSignedControlEnvelope(
    val protocol: String = CONTROL_PROTOCOL,
    val messageId: String,
    val conversationId: String,
    val senderId: String,
    val recipientId: String,
    val createdAt: String,
    val hopLimit: Int,
    val contentType: String,
    val payload: NativeControlSealedPayload,
    val signature: ByteArray
)

data class NativeOpenedControlEnvelope(
    val messageId: String,
    val conversationId: String,
    val senderId: String,
    val recipientId: String,
    val createdAt: String,
    val hopLimit: Int,
    val contentType: String,
    val plaintext: ByteArray
)

class AndroidControlEnvelopeCodec(
    private val identity: DeviceIdentity,
    private val random: SecureRandom = SecureRandom()
) {
    companion object {
        private val MAGIC = byteArrayOf('T'.code.toByte(), 'C'.code.toByte(), 'E'.code.toByte(), '1'.code.toByte())

        fun isTce1(bytes: ByteArray): Boolean =
            bytes.size >= MAGIC.size && bytes.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)
    }

    fun createAndEncode(
        sessionKey: ByteArray,
        recipientId: String,
        contentType: String,
        plaintext: ByteArray,
        conversationId: String = UUID.randomUUID().toString(),
        messageId: String = UUID.randomUUID().toString(),
        createdAtEpochMs: Long = System.currentTimeMillis(),
        hopLimit: Int = 8,
        nonce: ByteArray = ByteArray(CONTROL_NONCE_BYTES).also(random::nextBytes)
    ): ByteArray {
        require(sessionKey.size == 32) { "session key must be 32 bytes" }
        require(recipientId.isNotBlank()) { "control recipient is required" }
        require(contentType.isNotBlank()) { "control contentType is required" }
        require(plaintext.isNotEmpty() && plaintext.size <= CONTROL_MAX_CIPHERTEXT_BYTES) { "control plaintext length invalid" }
        require(hopLimit in 1..32) { "control hopLimit invalid" }
        require(nonce.size == CONTROL_NONCE_BYTES) { "control nonce must be 12 bytes" }

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(sessionKey, "AES"), GCMParameterSpec(128, nonce))
        val sealed = cipher.doFinal(plaintext)
        val payload = NativeControlSealedPayload(
            nonce = nonce.copyOf(),
            ciphertext = sealed.copyOfRange(0, sealed.size - CONTROL_TAG_BYTES),
            tag = sealed.copyOfRange(sealed.size - CONTROL_TAG_BYTES, sealed.size)
        )
        val unsigned = NativeSignedControlEnvelope(
            messageId = messageId,
            conversationId = conversationId,
            senderId = identity.deviceId,
            recipientId = recipientId,
            createdAt = CONTROL_ISO_MILLIS.format(Instant.ofEpochMilli(createdAtEpochMs)),
            hopLimit = hopLimit,
            contentType = contentType,
            payload = payload,
            signature = byteArrayOf()
        )
        val signature = sign(canonicalBytes(unsigned))
        return encode(unsigned.copy(signature = signature))
    }

    fun decode(bytes: ByteArray): NativeSignedControlEnvelope {
        require(bytes.size > MAGIC.size + CONTROL_SIGNATURE_BYTES) { "control envelope wire too short" }
        val source = ByteArrayInputStream(bytes)
        val input = DataInputStream(source)
        val magic = ByteArray(MAGIC.size).also(input::readFully)
        require(magic.contentEquals(MAGIC)) { "unsupported control envelope wire magic" }

        val protocol = readString(input)
        val messageId = readString(input)
        val conversationId = readString(input)
        val senderId = readString(input)
        val recipientId = readString(input)
        val createdAt = readString(input)
        val hopLimit = input.readUnsignedByte()
        require(hopLimit in 1..32) { "control hopLimit invalid" }
        val contentType = readString(input)
        val nonce = ByteArray(CONTROL_NONCE_BYTES).also(input::readFully)
        val ciphertextLength = input.readInt()
        require(ciphertextLength in 1..CONTROL_MAX_CIPHERTEXT_BYTES) { "control ciphertext length invalid" }
        require(source.available() >= ciphertextLength + CONTROL_TAG_BYTES + CONTROL_SIGNATURE_BYTES) { "truncated control envelope wire" }
        val ciphertext = ByteArray(ciphertextLength).also(input::readFully)
        val tag = ByteArray(CONTROL_TAG_BYTES).also(input::readFully)
        require(source.available() == CONTROL_SIGNATURE_BYTES) { "invalid control signature/trailing bytes" }
        val signature = ByteArray(CONTROL_SIGNATURE_BYTES).also(input::readFully)
        require(source.available() == 0) { "unexpected trailing control envelope bytes" }

        return NativeSignedControlEnvelope(
            protocol = protocol,
            messageId = messageId,
            conversationId = conversationId,
            senderId = senderId,
            recipientId = recipientId,
            createdAt = createdAt,
            hopLimit = hopLimit,
            contentType = contentType,
            payload = NativeControlSealedPayload(nonce, ciphertext, tag),
            signature = signature
        ).also(::validateEnvelope)
    }

    fun verifyAndOpen(
        bytes: ByteArray,
        sessionKey: ByteArray,
        signingPublicKey: ByteArray,
        expectedRecipientId: String = identity.deviceId
    ): NativeOpenedControlEnvelope {
        require(sessionKey.size == 32) { "session key must be 32 bytes" }
        require(signingPublicKey.size == 32) { "invalid control signing public key" }
        val envelope = decode(bytes)
        require(envelope.recipientId == expectedRecipientId) { "control recipient mismatch" }
        require(TelemetryCrypto.deviceId(signingPublicKey) == envelope.senderId) { "control sender identity mismatch" }
        require(verify(signingPublicKey, canonicalBytes(envelope), envelope.signature)) { "invalid control envelope signature" }

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(sessionKey, "AES"), GCMParameterSpec(128, envelope.payload.nonce))
        val plaintext = cipher.doFinal(envelope.payload.ciphertext + envelope.payload.tag)
        return NativeOpenedControlEnvelope(
            messageId = envelope.messageId,
            conversationId = envelope.conversationId,
            senderId = envelope.senderId,
            recipientId = envelope.recipientId,
            createdAt = envelope.createdAt,
            hopLimit = envelope.hopLimit,
            contentType = envelope.contentType,
            plaintext = plaintext
        )
    }

    fun encode(envelope: NativeSignedControlEnvelope): ByteArray {
        validateEnvelope(envelope)
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { out ->
            out.write(MAGIC)
            writeString(out, envelope.protocol)
            writeString(out, envelope.messageId)
            writeString(out, envelope.conversationId)
            writeString(out, envelope.senderId)
            writeString(out, envelope.recipientId)
            writeString(out, envelope.createdAt)
            out.writeByte(envelope.hopLimit)
            writeString(out, envelope.contentType)
            out.write(envelope.payload.nonce)
            out.writeInt(envelope.payload.ciphertext.size)
            out.write(envelope.payload.ciphertext)
            out.write(envelope.payload.tag)
            out.write(envelope.signature)
        }
        return output.toByteArray()
    }

    fun canonicalJson(envelope: NativeSignedControlEnvelope): String = buildString {
        append('{')
        append("\"protocol\":").append(jsonString(envelope.protocol)).append(',')
        append("\"messageId\":").append(jsonString(envelope.messageId)).append(',')
        append("\"conversationId\":").append(jsonString(envelope.conversationId)).append(',')
        append("\"senderId\":").append(jsonString(envelope.senderId)).append(',')
        append("\"recipientId\":").append(jsonString(envelope.recipientId)).append(',')
        append("\"createdAt\":").append(jsonString(envelope.createdAt)).append(',')
        append("\"hopLimit\":").append(envelope.hopLimit).append(',')
        append("\"contentType\":").append(jsonString(envelope.contentType)).append(',')
        append("\"payload\":{")
        append("\"algorithm\":").append(jsonString(CONTROL_ALGORITHM)).append(',')
        append("\"keyAgreement\":").append(jsonString(CONTROL_KEY_AGREEMENT)).append(',')
        append("\"nonce\":").append(jsonString(b64url(envelope.payload.nonce))).append(',')
        append("\"ciphertext\":").append(jsonString(b64url(envelope.payload.ciphertext))).append(',')
        append("\"tag\":").append(jsonString(b64url(envelope.payload.tag)))
        append("}}")
    }

    private fun validateEnvelope(envelope: NativeSignedControlEnvelope) {
        require(envelope.protocol == CONTROL_PROTOCOL) { "unsupported control protocol" }
        require(envelope.messageId.isNotBlank()) { "control messageId is required" }
        require(envelope.conversationId.isNotBlank()) { "control conversationId is required" }
        require(envelope.senderId.isNotBlank() && envelope.recipientId.isNotBlank()) { "control identities are required" }
        require(runCatching { Instant.parse(envelope.createdAt) }.isSuccess) { "control createdAt invalid" }
        require(envelope.hopLimit in 1..32) { "control hopLimit invalid" }
        require(envelope.contentType.isNotBlank()) { "control contentType is required" }
        require(envelope.signature.size == CONTROL_SIGNATURE_BYTES) { "control signature must be 64 bytes" }
    }

    private fun canonicalBytes(envelope: NativeSignedControlEnvelope): ByteArray =
        canonicalJson(envelope).toByteArray(StandardCharsets.UTF_8)

    private fun sign(bytes: ByteArray): ByteArray {
        val signer = Ed25519Signer()
        signer.init(true, Ed25519PrivateKeyParameters(identity.ed25519Seed, 0))
        signer.update(bytes, 0, bytes.size)
        return signer.generateSignature()
    }

    private fun verify(publicKey: ByteArray, bytes: ByteArray, signature: ByteArray): Boolean {
        val verifier = Ed25519Signer()
        verifier.init(false, Ed25519PublicKeyParameters(publicKey, 0))
        verifier.update(bytes, 0, bytes.size)
        return verifier.verifySignature(signature)
    }

    private fun writeString(out: DataOutputStream, value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        require(bytes.isNotEmpty() && bytes.size <= CONTROL_MAX_STRING_BYTES) { "control string length invalid" }
        out.writeShort(bytes.size)
        out.write(bytes)
    }

    private fun readString(input: DataInputStream): String {
        val length = input.readUnsignedShort()
        require(length in 1..CONTROL_MAX_STRING_BYTES) { "control string length invalid" }
        return String(ByteArray(length).also(input::readFully), StandardCharsets.UTF_8)
    }

    private fun b64url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun jsonString(value: String): String {
        val out = StringBuilder(value.length + 2).append('"')
        for (ch in value) {
            when (ch) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> if (ch.code < 0x20) out.append(String.format("\\u%04x", ch.code)) else out.append(ch)
            }
        }
        return out.append('"').toString()
    }
}
