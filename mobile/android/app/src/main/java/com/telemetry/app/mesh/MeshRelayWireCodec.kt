package com.telemetry.app.mesh

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets

object MeshRelayWireCodec {
    private val MAGIC = byteArrayOf('T'.code.toByte(), 'M'.code.toByte(), 'R'.code.toByte(), '1'.code.toByte())
    private const val MAX_STRING_BYTES = 512
    private const val MAX_RELAY_PATH = 32
    private const val MAX_ENVELOPE_BYTES = 2 * 1024 * 1024

    fun encode(frame: OpaqueRelayFrame): ByteArray {
        require(frame.header.relayPath.size <= MAX_RELAY_PATH) { "relay path too long" }
        require(frame.encodedEnvelope.size in 1..MAX_ENVELOPE_BYTES) { "encoded envelope size invalid" }

        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use { out ->
            out.write(MAGIC)
            writeString(out, frame.header.messageId)
            writeString(out, frame.header.senderId)
            writeString(out, frame.header.recipientId)
            out.writeByte(frame.header.hopCount)
            out.writeByte(frame.header.hopLimit)
            out.writeByte(frame.header.relayPath.size)
            frame.header.relayPath.forEach { writeString(out, it) }
            out.writeLong(frame.header.createdAtEpochMs)
            out.writeLong(frame.header.expiresAtEpochMs)
            out.writeInt(frame.encodedEnvelope.size)
            out.write(frame.encodedEnvelope)
        }
        return buffer.toByteArray()
    }

    fun decode(bytes: ByteArray): OpaqueRelayFrame {
        require(bytes.size >= MAGIC.size + 1) { "relay frame too short" }
        val input = ByteArrayInputStream(bytes)
        val data = DataInputStream(input)

        val magic = ByteArray(MAGIC.size).also(data::readFully)
        require(magic.contentEquals(MAGIC)) { "unsupported relay wire magic" }

        val messageId = readString(data)
        val senderId = readString(data)
        val recipientId = readString(data)
        val hopCount = data.readUnsignedByte()
        val hopLimit = data.readUnsignedByte()
        val relayPathCount = data.readUnsignedByte()
        require(relayPathCount <= MAX_RELAY_PATH) { "relay path too long" }
        val relayPath = List(relayPathCount) { readString(data) }
        val createdAt = data.readLong()
        val expiresAt = data.readLong()
        val envelopeLength = data.readInt().toLong() and 0xffffffffL
        require(envelopeLength in 1..MAX_ENVELOPE_BYTES.toLong()) { "encoded envelope size invalid" }
        require(envelopeLength <= input.available().toLong()) { "truncated encoded envelope" }
        val envelope = ByteArray(envelopeLength.toInt()).also(data::readFully)
        require(input.available() == 0) { "unexpected trailing relay bytes" }

        return OpaqueRelayFrame(
            header = MeshRelayHeader(
                messageId = messageId,
                senderId = senderId,
                recipientId = recipientId,
                hopCount = hopCount,
                hopLimit = hopLimit,
                relayPath = relayPath,
                createdAtEpochMs = createdAt,
                expiresAtEpochMs = expiresAt
            ),
            encodedEnvelope = envelope
        )
    }

    private fun writeString(out: DataOutputStream, value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        require(bytes.isNotEmpty()) { "relay string must not be empty" }
        require(bytes.size <= MAX_STRING_BYTES) { "relay string too long" }
        out.writeShort(bytes.size)
        out.write(bytes)
    }

    private fun readString(input: DataInputStream): String {
        val length = input.readUnsignedShort()
        require(length in 1..MAX_STRING_BYTES) { "relay string length invalid" }
        val bytes = ByteArray(length).also(input::readFully)
        return String(bytes, StandardCharsets.UTF_8).also {
            require(it.isNotBlank()) { "relay string must not be blank" }
        }
    }
}
