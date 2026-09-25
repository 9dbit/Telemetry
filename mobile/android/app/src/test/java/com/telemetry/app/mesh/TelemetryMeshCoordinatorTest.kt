package com.telemetry.app.mesh

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TelemetryMeshCoordinatorTest {
    private class MutableResolver : MeshRouteResolver {
        var route: MeshRouteEvidence? = null
        override fun resolve(
            destinationId: String,
            excludePeerIds: Set<String>,
            nowEpochMs: Long
        ): MeshRouteEvidence? =
            route?.takeIf { it.destinationId == destinationId && it.viaPeerId !in excludePeerIds }
    }

    private class RecordingSender : MeshNeighborSender {
        var accept = true
        var transportId = "wifi-direct"
        val sent = mutableListOf<Pair<String, OpaqueRelayFrame>>()

        override fun send(nextHopPeerId: String, frame: OpaqueRelayFrame): MeshSendResult {
            sent += nextHopPeerId to frame
            return if (accept) {
                MeshSendResult(
                    sent = true,
                    transportId = transportId,
                    attempts = listOf(MeshSendAttempt(transportId, true)),
                    reason = "selected-transport"
                )
            } else {
                MeshSendResult(
                    sent = false,
                    attempts = listOf(MeshSendAttempt(transportId, false)),
                    reason = "all-transports-failed"
                )
            }
        }
    }

    private fun frame(
        messageId: String = "mesh-1",
        expiresAtEpochMs: Long = 10_000L
    ) = OpaqueRelayFrame(
        MeshRelayHeader(
            messageId = messageId,
            senderId = "device-a",
            recipientId = "device-c",
            hopCount = 0,
            hopLimit = 4,
            relayPath = listOf("device-a"),
            expiresAtEpochMs = expiresAtEpochMs
        ),
        byteArrayOf(4, 3, 2, 1)
    )

    @Test
    fun storesWithoutRouteThenForwardsSameOpaqueEnvelopeWhenRouteAppears() {
        val store = InMemoryOpaqueRelayStore()
        val resolver = MutableResolver()
        val sender = RecordingSender()
        val events = mutableListOf<MeshCoordinatorEvent>()
        val coordinator = TelemetryMeshCoordinator("device-b", store, resolver, sender, events::add)
        val original = frame()

        val first = coordinator.ingest(original, 1_000L)
        assertEquals("stored", first.state)
        assertEquals("no-route", first.reason)
        assertEquals(1, store.pending().size)

        resolver.route = MeshRouteEvidence("device-c", "peer-c", 1, 80, "ble")
        val flushed = coordinator.flush(2_000L).single()

        assertEquals("forwarded", flushed.state)
        assertEquals("wifi-direct", flushed.transportId)
        assertEquals(0, store.pending().size)
        assertEquals(1, sender.sent.size)
        val (_, forwarded) = sender.sent.single()
        assertEquals(original.header.messageId, forwarded.header.messageId)
        assertEquals(original.header.senderId, forwarded.header.senderId)
        assertEquals(original.header.recipientId, forwarded.header.recipientId)
        assertEquals(1, forwarded.header.hopCount)
        assertEquals(listOf("device-a", "device-b"), forwarded.header.relayPath)
        assertArrayEquals(original.encodedEnvelope, forwarded.encodedEnvelope)
        assertEquals("wifi-direct", events.last { it.type == "forwarded" }.transport)
        assertTrue(events.none { it.toString().contains("plaintext", ignoreCase = true) })
    }

    @Test
    fun failedNextHopKeepsFrameStoredAndLaterRetryCanSucceed() {
        val store = InMemoryOpaqueRelayStore()
        val resolver = MutableResolver().apply {
            route = MeshRouteEvidence("device-c", "peer-c", 1, 60, "ble")
        }
        val sender = RecordingSender().apply { accept = false }
        val coordinator = TelemetryMeshCoordinator("device-b", store, resolver, sender)

        val first = coordinator.ingest(frame(), 1_000L)
        assertEquals("stored", first.state)
        assertEquals("all-transports-failed", first.reason)
        assertEquals(1, store.pending().size)

        sender.accept = true
        sender.transportId = "ble"
        val retry = coordinator.flush(2_000L).single()
        assertEquals("forwarded", retry.state)
        assertEquals("ble", retry.transportId)
        assertEquals(0, store.pending().size)
        assertEquals(2, sender.sent.size)
    }

    @Test
    fun duplicateRelayFrameIsDroppedEvenAfterOriginalWasForwarded() {
        val store = InMemoryOpaqueRelayStore()
        val resolver = MutableResolver().apply {
            route = MeshRouteEvidence("device-c", "peer-c", 1, 70, "wifi-direct")
        }
        val sender = RecordingSender()
        val coordinator = TelemetryMeshCoordinator("device-b", store, resolver, sender)
        val original = frame()

        assertEquals("forwarded", coordinator.ingest(original, 1_000L).state)
        val duplicate = coordinator.ingest(original, 2_000L)
        assertEquals("dropped", duplicate.state)
        assertEquals("duplicate", duplicate.reason)
        assertEquals(1, sender.sent.size)
    }

    @Test
    fun expiredStoredFrameIsDroppedWithoutTransmission() {
        val store = InMemoryOpaqueRelayStore()
        val resolver = MutableResolver()
        val sender = RecordingSender()
        val coordinator = TelemetryMeshCoordinator("device-b", store, resolver, sender)

        assertEquals("stored", coordinator.ingest(frame(expiresAtEpochMs = 1_500L), 1_000L).state)
        val result = coordinator.flush(2_000L).single()
        assertEquals("dropped", result.state)
        assertEquals("expired", result.reason)
        assertEquals(0, sender.sent.size)
    }
}
