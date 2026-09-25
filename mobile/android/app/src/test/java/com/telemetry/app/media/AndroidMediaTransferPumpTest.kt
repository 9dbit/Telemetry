package com.telemetry.app.media

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class AndroidMediaTransferPumpTest {
    private fun chunk(assetId: String, index: Int, count: Int): NativeEncryptedMediaChunk =
        NativeEncryptedMediaChunk(
            assetId = assetId,
            index = index,
            count = count,
            plainBytes = 4,
            nonce = ByteArray(12) { (index + it).toByte() },
            ciphertext = byteArrayOf(1, 2, 3, (40 + index).toByte()),
            tag = ByteArray(16) { (70 + index + it).toByte() }
        )

    @Test
    fun successfulPumpMarksOnlyAcceptedChunksAndPreservesWireBytes() {
        val root = Files.createTempDirectory("telemetry-media-pump").toFile()
        try {
            val assetId = "asset-pump-0001"
            val store = AndroidOpaqueMediaChunkStore(root)
            repeat(2) { index ->
                store.put(AndroidMediaChunkWireCodec.encode(chunk(assetId, index, 2)), 100_000L)
            }
            val scheduler = AndroidMediaTransferScheduler(assetId, 2, store, retryAfterMs = 1_000L)
            val pump = AndroidMediaTransferPump(scheduler)
            val original = store.records(1_000L).associate { it.index to it.wire.copyOf() }
            val sentWire = mutableListOf<ByteArray>()

            val result = pump.pump(1_000L, 2) { wire ->
                sentWire += wire.copyOf()
                true
            }

            assertEquals(listOf(0, 1), result.sentIndices)
            assertEquals("batch-sent", result.reason)
            assertArrayEquals(original.getValue(0), sentWire[0])
            assertArrayEquals(original.getValue(1), sentWire[1])
            assertEquals(0, scheduler.status().acknowledgedChunks)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectedTransportDoesNotConsumeRetryAttempt() {
        val root = Files.createTempDirectory("telemetry-media-pump").toFile()
        try {
            val assetId = "asset-pump-0002"
            val store = AndroidOpaqueMediaChunkStore(root)
            store.put(AndroidMediaChunkWireCodec.encode(chunk(assetId, 0, 1)), 100_000L)
            val scheduler = AndroidMediaTransferScheduler(assetId, 1, store, retryAfterMs = 10_000L)
            val pump = AndroidMediaTransferPump(scheduler)

            val rejected = pump.pump(1_000L, 1) { false }
            assertEquals("transport-rejected", rejected.reason)
            assertEquals(listOf(0), scheduler.nextBatch(1_001L, 1).map { it.index })

            val accepted = pump.pump(1_001L, 1) { true }
            assertEquals(listOf(0), accepted.sentIndices)
            assertTrue(scheduler.nextBatch(1_002L, 1).isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun retrySendsExactSamePersistedTmc1Wire() {
        val root = Files.createTempDirectory("telemetry-media-pump").toFile()
        try {
            val assetId = "asset-pump-0003"
            val store = AndroidOpaqueMediaChunkStore(root)
            val wire = AndroidMediaChunkWireCodec.encode(chunk(assetId, 0, 1))
            store.put(wire, 100_000L)
            val scheduler = AndroidMediaTransferScheduler(assetId, 1, store, retryAfterMs = 10L)
            val pump = AndroidMediaTransferPump(scheduler)
            val attempts = mutableListOf<ByteArray>()

            pump.pump(1_000L, 1) { sent ->
                attempts += sent.copyOf()
                true
            }
            pump.pump(1_011L, 1) { sent ->
                attempts += sent.copyOf()
                true
            }

            assertEquals(2, attempts.size)
            assertArrayEquals(wire, attempts[0])
            assertArrayEquals(wire, attempts[1])
        } finally {
            root.deleteRecursively()
        }
    }
}
