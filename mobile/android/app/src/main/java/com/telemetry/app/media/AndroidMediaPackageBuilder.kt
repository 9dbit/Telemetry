package com.telemetry.app.media

import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

private const val NATIVE_MEDIA_MANIFEST_VERSION = "telemetry/media-manifest/0.1"
private const val NATIVE_MEDIA_CHUNK_VERSION = "telemetry/media-chunk/0.1"
private const val MIN_MEDIA_CHUNK_BYTES = 16 * 1024
private const val MAX_MEDIA_CHUNK_BYTES_NATIVE = 1024 * 1024
private const val MAX_MEDIA_ASSET_BYTES_NATIVE = 512L * 1024 * 1024
private const val MAX_MEDIA_CHUNKS_NATIVE = 8192
private const val MAX_MEDIA_FILENAME_CHARS = 255
private const val MAX_MEDIA_MIME_CHARS = 127
private val MEDIA_KINDS_NATIVE = setOf("photo", "video", "file")
private val ISO_MILLIS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
    .withZone(ZoneOffset.UTC)

data class NativeMediaManifest(
    val version: String = NATIVE_MEDIA_MANIFEST_VERSION,
    val assetId: String,
    val kind: String,
    val mimeType: String,
    val fileName: String,
    val byteLength: Long,
    val chunkBytes: Int,
    val chunkCount: Int,
    val sha256: String,
    val contentKey: String,
    val createdAt: String
)

data class NativeMediaPackageResult(
    val manifest: NativeMediaManifest,
    val storedChunks: Int
)

class AndroidMediaPackageBuilder(
    private val random: SecureRandom = SecureRandom()
) {
    fun buildIntoStore(
        openInput: () -> InputStream,
        byteLength: Long,
        kind: String,
        mimeType: String,
        fileName: String,
        store: AndroidOpaqueMediaChunkStore,
        expiresAtEpochMs: Long,
        chunkBytes: Int = 256 * 1024,
        assetId: String = UUID.randomUUID().toString(),
        contentKey: ByteArray = ByteArray(32).also(random::nextBytes),
        nowEpochMs: Long = System.currentTimeMillis(),
        nonceProvider: (Int) -> ByteArray = { ByteArray(12).also(random::nextBytes) }
    ): NativeMediaPackageResult {
        validateMetadata(byteLength, kind, mimeType, fileName, chunkBytes, assetId, contentKey)
        require(expiresAtEpochMs > nowEpochMs) { "media chunk expiry must be in the future" }

        val count = ((byteLength + chunkBytes - 1) / chunkBytes).toInt()
        require(count in 1..MAX_MEDIA_CHUNKS_NATIVE) { "media asset requires too many chunks" }
        val hash = hashStream(openInput, byteLength)
        val manifest = NativeMediaManifest(
            assetId = assetId,
            kind = kind,
            mimeType = mimeType,
            fileName = fileName,
            byteLength = byteLength,
            chunkBytes = chunkBytes,
            chunkCount = count,
            sha256 = hash,
            contentKey = Base64.getUrlEncoder().withoutPadding().encodeToString(contentKey),
            createdAt = ISO_MILLIS.format(Instant.ofEpochMilli(nowEpochMs))
        )

        var stored = 0
        openInput().use { input ->
            for (index in 0 until count) {
                val expected = if (index == count - 1) {
                    (byteLength - index.toLong() * chunkBytes).toInt()
                } else {
                    chunkBytes
                }
                val plain = readExactly(input, expected)
                val nonce = nonceProvider(index).copyOf()
                require(nonce.size == 12) { "media nonce must be 12 bytes" }
                val encrypted = encryptChunk(
                    key = contentKey,
                    assetId = assetId,
                    index = index,
                    count = count,
                    plain = plain,
                    nonce = nonce
                )
                val wire = AndroidMediaChunkWireCodec.encode(encrypted)
                val result = store.put(wire, expiresAtEpochMs)
                if (result.accepted || result.reason == "duplicate") stored += 1
            }
            require(input.read() == -1) { "media input exceeds declared byteLength" }
        }

        return NativeMediaPackageResult(manifest, stored)
    }

    fun decryptChunk(manifest: NativeMediaManifest, chunk: NativeEncryptedMediaChunk): ByteArray {
        require(manifest.version == NATIVE_MEDIA_MANIFEST_VERSION) { "unsupported media manifest version" }
        require(chunk.assetId == manifest.assetId) { "media chunk asset mismatch" }
        require(chunk.count == manifest.chunkCount) { "media chunk count mismatch" }
        val expected = if (chunk.index == manifest.chunkCount - 1) {
            (manifest.byteLength - chunk.index.toLong() * manifest.chunkBytes).toInt()
        } else {
            manifest.chunkBytes
        }
        require(chunk.plainBytes == expected) { "media chunk size does not match manifest" }
        val key = Base64.getUrlDecoder().decode(manifest.contentKey)
        require(key.size == 32) { "invalid media content key" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, chunk.nonce))
        cipher.updateAAD(chunkAad(chunk.assetId, chunk.index, chunk.count, chunk.plainBytes))
        return cipher.doFinal(chunk.ciphertext + chunk.tag)
    }

    private fun encryptChunk(
        key: ByteArray,
        assetId: String,
        index: Int,
        count: Int,
        plain: ByteArray,
        nonce: ByteArray
    ): NativeEncryptedMediaChunk {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(chunkAad(assetId, index, count, plain.size))
        val sealed = cipher.doFinal(plain)
        require(sealed.size == plain.size + 16) { "unexpected media AES-GCM output length" }
        return NativeEncryptedMediaChunk(
            assetId = assetId,
            index = index,
            count = count,
            plainBytes = plain.size,
            nonce = nonce,
            ciphertext = sealed.copyOfRange(0, plain.size),
            tag = sealed.copyOfRange(plain.size, sealed.size)
        )
    }

    private fun chunkAad(assetId: String, index: Int, count: Int, plainBytes: Int): ByteArray {
        val json = buildString {
            append("{\"version\":\"")
            append(NATIVE_MEDIA_CHUNK_VERSION)
            append("\",\"assetId\":\"")
            append(jsonEscape(assetId))
            append("\",\"index\":")
            append(index)
            append(",\"count\":")
            append(count)
            append(",\"plainBytes\":")
            append(plainBytes)
            append('}')
        }
        return json.toByteArray(StandardCharsets.UTF_8)
    }

    private fun hashStream(openInput: () -> InputStream, expectedBytes: Long): String {
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        val buffer = ByteArray(128 * 1024)
        openInput().use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                total += read
                require(total <= expectedBytes) { "media input exceeds declared byteLength" }
                digest.update(buffer, 0, read)
            }
        }
        require(total == expectedBytes) { "media input shorter than declared byteLength" }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun readExactly(input: InputStream, expected: Int): ByteArray {
        require(expected > 0) { "invalid media chunk read size" }
        val output = ByteArray(expected)
        var offset = 0
        while (offset < expected) {
            val read = input.read(output, offset, expected - offset)
            require(read >= 0) { "media input shorter than declared byteLength" }
            if (read > 0) offset += read
        }
        return output
    }

    private fun validateMetadata(
        byteLength: Long,
        kind: String,
        mimeType: String,
        fileName: String,
        chunkBytes: Int,
        assetId: String,
        contentKey: ByteArray
    ) {
        require(byteLength in 1..MAX_MEDIA_ASSET_BYTES_NATIVE) { "media asset size invalid" }
        require(kind in MEDIA_KINDS_NATIVE) { "unsupported media kind" }
        require(mimeType.isNotEmpty() && mimeType.length <= MAX_MEDIA_MIME_CHARS) { "invalid media mimeType" }
        require(fileName.isNotEmpty() && fileName.length <= MAX_MEDIA_FILENAME_CHARS) { "invalid media fileName" }
        require(chunkBytes in MIN_MEDIA_CHUNK_BYTES..MAX_MEDIA_CHUNK_BYTES_NATIVE) { "invalid media chunkBytes" }
        require(assetId.length in 8..128) { "invalid media assetId" }
        require(contentKey.size == 32) { "contentKey must be 32 bytes" }
    }

    private fun jsonEscape(value: String): String = buildString {
        value.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\b' -> append("\\b")
                '\u000c' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (char.code < 0x20) {
                    append("\\u")
                    append(char.code.toString(16).padStart(4, '0'))
                } else {
                    append(char)
                }
            }
        }
    }
}
