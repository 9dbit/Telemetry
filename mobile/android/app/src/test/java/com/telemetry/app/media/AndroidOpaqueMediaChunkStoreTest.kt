package com.telemetry.app.media

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class AndroidOpaqueMediaChunkStoreTest {
    private fun chunk(assetId: String = "asset-demo-0001", index: Int = 0, count: Int = 2): NativeEncryptedMediaChunk =
        NativeEncryptedMediaChunk(
            assetId = assetId,
            index = index,
            count = count,
            plainBytes = 4,
            nonce = ByteArray(12) { (it + index).toByte() },
            ciphertext = byteArrayOf(1, 2, 3, (4 + index).toByte()),
            tag = ByteArray(16) { (16 + it + index).toByte() }
        )

    @Test
    fun persistsOpaqueChunkAcrossStoreReopenAndDedupes() {
        val root = Files.createTempDirectory("telemetry-media-store").toFile()
        try {
            val wire = AndroidMediaChunkWireCodec.encode(chunk())
            val first = AndroidOpaqueMediaChunkStore(root)
            assertTrue(first.put(wire, 10_000L).accepted)
            assertEquals("duplicate", first.put(wire, 10_000L).reason)

            val reopened = AndroidOpaqueMediaChunkStore(root)
            val records = reopened.records(1_000L)
            assertEquals(1, records.size)
            assertEquals("asset-demo-0001", records.single().assetId)
            assertEquals(0, records.single().index)
            assertArrayEquals(wire, records.single().wire)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun sameAssetAndIndexWithDifferentCiphertextIsConflict() {
        val root = Files.createTempDirectory("telemetry-media-store").toFile()
        try {
            val store = AndroidOpaqueMediaChunkStore(root)
            val original = chunk(assetId = "asset-conflict-0001", index = 0, count = 1)
            val conflicting = original.copy(
                nonce = ByteArray(12) { (100 + it).toByte() },
                ciphertext = byteArrayOf(9, 8, 7, 6),
                tag = ByteArray(16) { (80 + it).toByte() }
            )
            val originalWire = AndroidMediaChunkWireCodec.encode(original)
            assertTrue(store.put(originalWire, 10_000L).accepted)
            val result = store.put(AndroidMediaChunkWireCodec.encode(conflicting), 10_000L)
            assertFalse(result.accepted)
            assertEquals("conflict", result.reason)
            assertArrayEquals(originalWire, store.records(1_000L).single().wire)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun reportsMissingIndicesAndRemovesStoredChunk() {
        val root = Files.createTempDirectory("telemetry-media-store").toFile()
        try {
            val store = AndroidOpaqueMediaChunkStore(root)
            store.put(AndroidMediaChunkWireCodec.encode(chunk(index = 0, count = 3)), 10_000L)
            store.put(AndroidMediaChunkWireCodec.encode(chunk(index = 2, count = 3)), 10_000L)
            assertEquals(listOf(1), store.missingIndices("asset-demo-0001", 3, 1_000L))
            assertTrue(store.remove("asset-demo-0001", 0))
            assertEquals(listOf(0, 1), store.missingIndices("asset-demo-0001", 3, 1_000L))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun expiredOrCorruptChunkIsPruned() {
        val root = Files.createTempDirectory("telemetry-media-store").toFile()
        try {
            val store = AndroidOpaqueMediaChunkStore(root)
            store.put(AndroidMediaChunkWireCodec.encode(chunk()), 2_000L)
            assertEquals(1, store.records(1_000L).size)
            assertEquals(1, store.prune(2_000L))
            assertTrue(store.records(2_000L).isEmpty())

            store.put(AndroidMediaChunkWireCodec.encode(chunk(assetId = "asset-other", index = 0, count = 1)), 10_000L)
            val dataFile = root.walkTopDown().first { it.isFile && it.name.endsWith(".tmc1") }
            dataFile.writeBytes(byteArrayOf(1, 2, 3))
            assertEquals(1, store.prune(3_000L))
            assertTrue(store.records(3_000L).isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun filesystemPathDoesNotExposeAssetId() {
        val root = Files.createTempDirectory("telemetry-media-store").toFile()
        try {
            val assetId = "asset-private-random-id"
            val store = AndroidOpaqueMediaChunkStore(root)
            store.put(AndroidMediaChunkWireCodec.encode(chunk(assetId = assetId, index = 0, count = 1)), 10_000L)
            val childDir = root.listFiles()?.singleOrNull()
            assertTrue(childDir?.isDirectory == true)
            assertFalse(childDir!!.name.contains(assetId))
            assertEquals(64, childDir.name.length)
        } finally {
            root.deleteRecursively()
        }
    }
}
