package com.telemetry.app.mesh

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TelemetryMeshBridgeTest {
    private fun frame(
        messageId: String = "m-1",
        recipientId: String = "device-c",
        hopCount: Int = 0,
        hopLimit: Int = 4,
        relayPath: List<String> = listOf("device-a"),
        expiresAtEpochMs: Long = 2_000L
    ) = OpaqueRelayFrame(
        header = MeshRelayHeader(
            messageId = messageId,
            senderId = "device-a",
            recipientId = recipientId,
            hopCount = hopCount,
            hopLimit = hopLimit,
            relayPath = relayPath,
            expiresAtEpochMs = expiresAtEpochMs
        ),
        encodedEnvelope = byteArrayOf(9, 8, 7, 6)
    )

    @Test
    fun relayStoreKeepsOpaqueEnvelopeAndDedupesStableMessageId() {
        val bridge = InMemoryOpaqueRelayStore()
        val original = frame()

        assertTrue(bridge.store(original))
        assertFalse(bridge.store(original.copy(encodedEnvelope = byteArrayOf(1, 2, 3))))

        val pending = bridge.pending()
        assertEquals(1, pending.size)
        assertEquals("m-1", pending.single().header.messageId)
        assertArrayEquals(byteArrayOf(9, 8, 7, 6), pending.single().encodedEnvelope)
    }

    @Test
    fun ingressDistinguishesRecipientRelayDuplicateExpiryLoopAndHopLimit() {
        val bridge = InMemoryOpaqueRelayStore()

        assertEquals(
            MeshIngressAction.DELIVER_LOCAL,
            bridge.ingest(frame(recipientId = "device-b"), "device-b", 1_000L).action
        )
        assertEquals(
            MeshIngressAction.RELAY,
            bridge.ingest(frame(), "device-b", 1_000L).action
        )

        bridge.store(frame())
        assertEquals(
            "duplicate",
            bridge.ingest(frame(), "device-b", 1_000L).reason
        )
        assertEquals(
            "expired",
            bridge.ingest(frame(messageId = "expired", expiresAtEpochMs = 900L), "device-b", 1_000L).reason
        )
        assertEquals(
            "relay-loop",
            bridge.ingest(frame(messageId = "loop", relayPath = listOf("device-a", "device-b")), "device-b", 1_000L).reason
        )
        assertEquals(
            "hop-limit",
            bridge.ingest(frame(messageId = "limit", hopCount = 4, hopLimit = 4), "device-b", 1_000L).reason
        )
    }

    @Test
    fun forwardingAdvancesOnlyHopMetadata() {
        val original = frame()
        val advanced = original.header.advancedBy("device-b")

        assertEquals(original.header.messageId, advanced.messageId)
        assertEquals(original.header.senderId, advanced.senderId)
        assertEquals(original.header.recipientId, advanced.recipientId)
        assertEquals(1, advanced.hopCount)
        assertEquals(listOf("device-a", "device-b"), advanced.relayPath)
    }

    @Test(expected = IllegalArgumentException::class)
    fun forwardingRejectsRelayLoop() {
        frame(relayPath = listOf("device-a", "device-b")).header.advancedBy("device-b")
    }
}
