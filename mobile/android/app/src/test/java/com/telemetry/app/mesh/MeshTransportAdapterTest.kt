package com.telemetry.app.mesh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MeshTransportAdapterTest {
    private class FakeAdapter(
        override val id: String,
        override val maxPayloadBytes: Int = Int.MAX_VALUE,
        override val metered: Boolean = false,
        private val available: Boolean = true,
        private val quality: Int? = 0,
        private var fail: Boolean = false
    ) : MeshTransportAdapter {
        val sent = mutableListOf<String>()

        override fun describePeer(peerId: String) = MeshPeerPath(
            peerId = peerId,
            available = available,
            quality = quality,
            metered = metered
        )

        override fun sendOpaque(peerId: String, frame: OpaqueRelayFrame): Boolean {
            sent += frame.header.messageId
            if (fail) {
                fail = false
                throw IllegalStateException("injected failure")
            }
            return true
        }

        fun failNext() {
            fail = true
        }
    }

    private fun frame(size: Int = 128) = OpaqueRelayFrame(
        header = MeshRelayHeader(
            messageId = "m-transport",
            senderId = "device-a",
            recipientId = "device-c",
            hopCount = 0,
            hopLimit = 4,
            relayPath = listOf("device-a"),
            expiresAtEpochMs = 10_000L
        ),
        encodedEnvelope = ByteArray(size) { 7 }
    )

    @Test
    fun wifiDirectIsPreferredOverBleBySharedPolicy() {
        val ble = FakeAdapter("ble", quality = 20)
        val wifi = FakeAdapter("wifi-direct", quality = -20)
        val coordinator = MeshTransportCoordinator(listOf(ble, wifi))

        val result = coordinator.send("device-b", frame())

        assertTrue(result.sent)
        assertEquals("wifi-direct", result.transportId)
        assertEquals(listOf("m-transport"), wifi.sent)
        assertTrue(ble.sent.isEmpty())
    }

    @Test
    fun wifiFailureFallsBackToBleWithoutChangingFrameIdentity() {
        val wifi = FakeAdapter("wifi-direct", quality = 10).also { it.failNext() }
        val ble = FakeAdapter("ble", quality = 10)
        val coordinator = MeshTransportCoordinator(listOf(wifi, ble))
        val original = frame()

        val result = coordinator.send("device-b", original)

        assertTrue(result.sent)
        assertEquals("ble", result.transportId)
        assertEquals("fallback-transport", result.reason)
        assertEquals(listOf("wifi-direct", "ble"), result.attempts.map { it.transportId })
        assertEquals(original.header.messageId, wifi.sent.single())
        assertEquals(original.header.messageId, ble.sent.single())
    }

    @Test
    fun payloadLimitRemovesBleCandidateForLargeOpaqueFrame() {
        val ble = FakeAdapter("ble", maxPayloadBytes = 32_000, quality = 20)
        val wifi = FakeAdapter("wifi-direct", maxPayloadBytes = 2_000_000, quality = 0)
        val coordinator = MeshTransportCoordinator(listOf(ble, wifi))

        val result = coordinator.send("device-b", frame(size = 64_000))

        assertTrue(result.sent)
        assertEquals("wifi-direct", result.transportId)
        assertTrue(ble.sent.isEmpty())
    }

    @Test
    fun noAvailableTransportReturnsQueueableFailure() {
        val coordinator = MeshTransportCoordinator(
            listOf(FakeAdapter("ble", available = false), FakeAdapter("wifi-direct", available = false))
        )

        val result = coordinator.send("device-b", frame())

        assertFalse(result.sent)
        assertEquals("no-transport-available", result.reason)
        assertTrue(result.attempts.isEmpty())
    }
}
