package com.telemetry.app.media

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets

private const val MAX_ASSET_ID_BYTES = 128
private const val MAX_CHUNK_BYTES = 1024 * 1024
private const val MAX_CHUNK_COUNT = 8192

data class NativeEncryptedMediaChunk(
    val assetId: String,
    val index: Int,
    val count: Int,
    val plainBytes: Int,
    val nonce: ByteArray,
    val ciphertext: ByteArray,
    val tag: ByteArray
) {
    init {
        val assetBytes = assetId.toByteArray(StandardCharsets.UTF_8)
        require(assetBytes.isNotEmpty() && assetBytes.size <= MAX_ASSET_ID_BYTES) { "invalid media assetId" }
        require(count in 1..MAX_CHUNK_COUNT) { "invalid media chunk count" }
        require(index in 0 until count) { "invalid media chunk index" }
        require(plainBytes in 1..MAX_CHUNK_BYTES) { "invalid media plainBytes" }
        require(nonce.size == 12) { "media nonce must be 12 bytes" }
        require(tag.size == 16) { "media tag must be 16 bytes" }
        require(ciphertext.size == plainBytes) { "media ciphertext length mismatch" }
    }

    fun deepCopy(): NativeEncryptedMediaChunk = copy(
        nonce = nonce.copyOf(),
        ciphertext = ciphertext.copyOf(),
        tag = tag.copyOf()
    )
}

object AndroidMediaChunkWireCodec {
    private val magic = byteArrayOf('T'.code.toByte(), 'M'.code.toByte(), 'C'.code.toByte(), '1'.code.toByte())

    fun encode(chunk: NativeEncryptedMediaChunk): ByteArray {
        val assetBytes = chunk.assetId.toByteArray(StandardCharsets.UTF_8)
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { out ->
            out.write(magic)
            out.writeShort(assetBytes.size)
            out.write(assetBytes)
            out.writeInt(chunk.index)
            out.writeInt(chunk.count)
            out.writeInt(chunk.plainBytes)
            out.write(chunk.nonce)
            out.write(chunk.tag)
            out.writeInt(chunk.ciphertext.size)
            out.write(chunk.ciphertext)
        }
        return output.toByteArray()
    }

    fun decode(bytes: ByteArray): NativeEncryptedMediaChunk {
        require(bytes.size >= 4 + 2 + 4 + 4 + 4 + 12 + 16 + 4 + 1) { "media wire too short" }
        val raw = ByteArrayInputStream(bytes)
        val input = DataInputStream(raw)
        val actualMagic = ByteArray(4).also(input::readFully)
        require(actualMagic.contentEquals(magic)) { "unsupported media wire magic" }

        val assetLength = input.readUnsignedShort()
        require(assetLength in 1..MAX_ASSET_ID_BYTES && assetLength <= raw.available()) { "invalid media assetId length" }
        val assetBytes = ByteArray(assetLength).also(input::readFully)
        val assetId = String(assetBytes, StandardCharsets.UTF_8)

        require(raw.available() >= 4 + 4 + 4 + 12 + 16 + 4 + 1) { "truncated media wire header" }
        val index = input.readInt()
        val count = input.readInt()
        val plainBytes = input.readInt()
        val nonce = ByteArray(12).also(input::readFully)
        val tag = ByteArray(16).also(input::readFully)
        val ciphertextLength = input.readInt()
        require(ciphertextLength in 1..MAX_CHUNK_BYTES) { "invalid media ciphertext length" }
        require(ciphertextLength <= raw.available()) { "truncated media ciphertext" }
        val ciphertext = ByteArray(ciphertextLength).also(input::readFully)
        require(raw.available() == 0) { "unexpected trailing media wire bytes" }

        return NativeEncryptedMediaChunk(
            assetId = assetId,
            index = index,
            count = count,
            plainBytes = plainBytes,
            nonce = nonce,
            ciphertext = ciphertext,
            tag = tag
        )
    }
}
