package com.telemetry.app.media

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.file.Files

class AndroidMediaPackageBuilderTest {
    @Test
    fun deterministicSingleChunkMatchesSharedAesGcmVector() {
        val root = Files.createTempDirectory("telemetry-media-builder").toFile()
        try {
            val bytes = "Telemetry media interop".toByteArray(Charsets.UTF_8)
            val key = ByteArray(32) { it.toByte() }
            val nonce = ByteArray(12) { it.toByte() }
            val store = AndroidOpaqueMediaChunkStore(root)
            val builder = AndroidMediaPackageBuilder()

            val result = builder.buildIntoStore(
                openInput = { ByteArrayInputStream(bytes) },
                byteLength = bytes.size.toLong(),
                kind = "file",
                mimeType = "application/octet-stream",
                fileName = "interop.bin",
                store = store,
                expiresAtEpochMs = 100_000L,
                chunkBytes = 16 * 1024,
                assetId = "asset-interop-0001",
                contentKey = key,
                nowEpochMs = 1_000L,
                nonceProvider = { nonce.copyOf() }
            )

            assertEquals("telemetry/media-manifest/0.1", result.manifest.version)
            assertEquals("417ff2cc3c7f411b82945e7d93c0d0eef70d776c94fc4f56ced805f5b214a858", result.manifest.sha256)
            assertEquals("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8", result.manifest.contentKey)
            assertEquals("1970-01-01T00:00:01.000Z", result.manifest.createdAt)
            assertEquals(1, result.manifest.chunkCount)

            val record = store.records(2_000L).single()
            val chunk = AndroidMediaChunkWireCodec.decode(record.wire)
            assertEquals("1367ba7ea880b669f461faeed580194deab8f35182142f", chunk.ciphertext.toHex())
            assertEquals("c45579190c5c31264b61f63c8515a88a", chunk.tag.toHex())
            assertArrayEquals(bytes, builder.decryptChunk(result.manifest, chunk))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun multiChunkStreamingBuildCanBeReassembledWithoutLoadingWholeSourceAtOnce() {
        val root = Files.createTempDirectory("telemetry-media-builder").toFile()
        try {
            val bytes = ByteArray(40_000) { (it % 251).toByte() }
            val key = ByteArray(32) { (255 - it).toByte() }
            val store = AndroidOpaqueMediaChunkStore(root)
            val builder = AndroidMediaPackageBuilder()
            val result = builder.buildIntoStore(
                openInput = { ByteArrayInputStream(bytes) },
                byteLength = bytes.size.toLong(),
                kind = "photo",
                mimeType = "image/jpeg",
                fileName = "photo.jpg",
                store = store,
                expiresAtEpochMs = 100_000L,
                chunkBytes = 16 * 1024,
                assetId = "asset-stream-0001",
                contentKey = key,
                nowEpochMs = 1_000L,
                nonceProvider = { index -> ByteArray(12) { (index * 16 + it).toByte() } }
            )

            assertEquals(3, result.manifest.chunkCount)
            val reassembled = store.records(2_000L)
                .sortedBy { it.index }
                .flatMap { record ->
                    builder.decryptChunk(result.manifest, AndroidMediaChunkWireCodec.decode(record.wire)).toList()
                }
                .toByteArray()
            assertArrayEquals(bytes, reassembled)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun declaredLengthMismatchFailsBeforeProducingACompletePackage() {
        val root = Files.createTempDirectory("telemetry-media-builder").toFile()
        try {
            val bytes = ByteArray(100) { 1 }
            val store = AndroidOpaqueMediaChunkStore(root)
            val builder = AndroidMediaPackageBuilder()
            val failure = runCatching {
                builder.buildIntoStore(
                    openInput = { ByteArrayInputStream(bytes) },
                    byteLength = 101,
                    kind = "file",
                    mimeType = "application/octet-stream",
                    fileName = "bad.bin",
                    store = store,
                    expiresAtEpochMs = 100_000L,
                    chunkBytes = 16 * 1024,
                    assetId = "asset-length-0001",
                    contentKey = ByteArray(32),
                    nowEpochMs = 1_000L,
                    nonceProvider = { ByteArray(12) }
                )
            }
            assertTrue(failure.isFailure)
            assertTrue(store.records(2_000L).isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
