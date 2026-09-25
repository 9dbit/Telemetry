package com.telemetry.app.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class AndroidMediaTransferSchedulerTest {
    private fun chunk(assetId: String, index: Int, count: Int): NativeEncryptedMediaChunk =
        NativeEncryptedMediaChunk(
            assetId = assetId,
            index = index,
            count = count,
            plainBytes = 4,
            nonce = ByteArray(12) { (it + index).toByte() },
            ciphertext = byteArrayOf(1, 2, 3, (10 + index).toByte()),
            tag = ByteArray(16) { (20 + it + index).toByte() }
        )

    private fun storeWithChunks(assetId: String, count: Int): Pair<java.io.File, AndroidOpaqueMediaChunkStore> {
        val root = Files.createTempDirectory("telemetry-media-scheduler").toFile()
        val store = AndroidOpaqueMediaChunkStore(root)
        repeat(count) { index ->
            store.put(
                AndroidMediaChunkWireCodec.encode(chunk(assetId, index, count)),
                expiresAtEpochMs = 100_000L
            )
        }
        return root to store
    }

    @Test
    fun selectsPendingChunksAndRetriesExactPersistedWire() {
        val assetId = "asset-native-scheduler-01"
        val (root, store) = storeWithChunks(assetId, 3)
        try {
            val scheduler = AndroidMediaTransferScheduler(assetId, 3, store, retryAfterMs = 5_000L)
            val first = scheduler.nextBatch(nowEpochMs = 1_000L, maxChunks = 2)
            assertEquals(listOf(0, 1), first.map { it.index })
            val firstWire = first.first().wire.copyOf()

            scheduler.markSent(first.map { it.index }, nowEpochMs = 1_000L)
            assertEquals(listOf(2), scheduler.nextBatch(nowEpochMs = 2_000L, maxChunks = 4).map { it.index })

            val retry = scheduler.nextBatch(nowEpochMs = 6_000L, maxChunks = 1).single()
            assertEquals(0, retry.index)
            assertTrue(firstWire.contentEquals(retry.wire))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun acknowledgedRangesSkipChunksAndCompleteTransfer() {
        val assetId = "asset-native-scheduler-02"
        val (root, store) = storeWithChunks(assetId, 4)
        try {
            val scheduler = AndroidMediaTransferScheduler(assetId, 4, store)
            assertTrue(
                scheduler.applyAck(
                    NativeMediaChunkAck(
                        assetId = assetId,
                        chunkCount = 4,
                        receivedRanges = listOf(NativeMediaAckRange(0, 1), NativeMediaAckRange(3, 3)),
                        complete = false
                    )
                )
            )
            assertEquals(listOf(2), scheduler.nextBatch(1_000L, 4).map { it.index })
            assertFalse(scheduler.status().complete)

            assertTrue(
                scheduler.applyAck(
                    NativeMediaChunkAck(
                        assetId = assetId,
                        chunkCount = 4,
                        receivedRanges = listOf(NativeMediaAckRange(0, 3)),
                        complete = true
                    )
                )
            )
            assertTrue(scheduler.status().complete)
            assertTrue(scheduler.nextBatch(2_000L, 4).isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun snapshotRestoresResumeStateWithoutChangingStoredCiphertext() {
        val assetId = "asset-native-scheduler-03"
        val (root, store) = storeWithChunks(assetId, 3)
        try {
            val first = AndroidMediaTransferScheduler(assetId, 3, store, retryAfterMs = 1_000L)
            val originalWire = first.nextBatch(1_000L, 1).single().wire.copyOf()
            first.markSent(listOf(0), 1_000L)
            first.applyAck(
                NativeMediaChunkAck(
                    assetId = assetId,
                    chunkCount = 3,
                    receivedRanges = listOf(NativeMediaAckRange(1, 1)),
                    complete = false
                )
            )

            val resumed = AndroidMediaTransferScheduler(
                assetId = assetId,
                chunkCount = 3,
                store = AndroidOpaqueMediaChunkStore(root),
                retryAfterMs = 1_000L,
                snapshot = first.snapshot()
            )
            assertEquals(listOf(0, 2), resumed.nextBatch(2_000L, 4).map { it.index })
            val retryWire = resumed.nextBatch(2_000L, 1).single().wire
            assertTrue(originalWire.contentEquals(retryWire))
            assertEquals(1, resumed.status().acknowledgedChunks)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun mismatchedAckIsRejectedAndAttemptsCanExhaust() {
        val assetId = "asset-native-scheduler-04"
        val (root, store) = storeWithChunks(assetId, 1)
        try {
            val scheduler = AndroidMediaTransferScheduler(
                assetId = assetId,
                chunkCount = 1,
                store = store,
                retryAfterMs = 1L,
                maxAttempts = 2
            )
            assertFalse(
                scheduler.applyAck(
                    NativeMediaChunkAck(
                        assetId = "asset-other",
                        chunkCount = 1,
                        receivedRanges = listOf(NativeMediaAckRange(0, 0)),
                        complete = true
                    )
                )
            )
            scheduler.markSent(listOf(0), 1L)
            scheduler.markSent(listOf(0), 2L)
            assertTrue(scheduler.nextBatch(10L, 1).isEmpty())
            assertEquals(listOf(0), scheduler.status().exhaustedIndices)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun ackRangeHelpersAreCompactAndStrict() {
        assertEquals(
            listOf(NativeMediaAckRange(0, 2), NativeMediaAckRange(5, 6)),
            compactNativeMediaAckRanges(listOf(0, 1, 2, 5, 6, 6), 8)
        )
        assertEquals(
            listOf(0, 1, 2, 5, 6),
            expandNativeMediaAckRanges(
                listOf(NativeMediaAckRange(0, 2), NativeMediaAckRange(5, 6)),
                8
            )
        )
    }
}
