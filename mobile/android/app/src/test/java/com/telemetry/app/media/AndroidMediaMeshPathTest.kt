package com.telemetry.app.media

import com.telemetry.app.mesh.AdvertisedRoute
import com.telemetry.app.mesh.AndroidMeshNodeRuntime
import com.telemetry.app.mesh.MeshPeerPath
import com.telemetry.app.mesh.MeshTransportAdapter
import com.telemetry.app.mesh.OpaqueRelayFrame
import com.telemetry.app.mesh.VerifiedRouteAdvertisement
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidMediaMeshPathTest {
    private class RuntimeLinkAdapter(
        override val id: String = "wifi-local",
        override val maxPayloadBytes: Int = 2 * 1024 * 1024,
        override val metered: Boolean = false,
        private val nowEpochMs: Long
    ) : MeshTransportAdapter {
        val peers = linkedMapOf<String, AndroidMeshNodeRuntime>()
        val forwarded = mutableListOf<Pair<String, OpaqueRelayFrame>>()

        override fun describePeer(peerId: String): MeshPeerPath = MeshPeerPath(
            peerId = peerId,
            available = peerId in peers,
            quality = 15,
            metered = false
        )

        override fun sendOpaque(peerId: String, frame: OpaqueRelayFrame): Boolean {
            val target = peers[peerId] ?: return false
            forwarded += peerId to frame.copyForStore()
            target.ingestRelayFrame(frame.copyForStore(), nowEpochMs)
            return true
        }
    }

    @Test
    fun encryptedTmc1ChunkTraversesAtoBtoCWithoutChangingPayloadBytes() {
        val now = 5_000L
        val receivedAtC = mutableListOf<ByteArray>()
        val eventsAtB = mutableListOf<String>()

        val nodeC = AndroidMeshNodeRuntime(
            localDeviceId = "device-c",
            onLocalDelivery = { receivedAtC += it.encodedEnvelope.copyOf() }
        )
        val nodeB = AndroidMeshNodeRuntime(
            localDeviceId = "device-b",
            onEvent = { eventsAtB += it.type }
        )
        val nodeA = AndroidMeshNodeRuntime(localDeviceId = "device-a")

        val linkA = RuntimeLinkAdapter(nowEpochMs = now)
        val linkB = RuntimeLinkAdapter(nowEpochMs = now)
        linkA.peers["device-b"] = nodeB
        linkB.peers["device-c"] = nodeC
        nodeA.registerTransport(linkA)
        nodeB.registerTransport(linkB)

        nodeB.observeDirectPeer(
            peerId = "device-c",
            quality = 90,
            transport = "wifi-local",
            nowEpochMs = now,
            ttlMs = 15_000L
        )

        val fromB = VerifiedRouteAdvertisement(
            protocol = "telemetry/mesh-route/0.1",
            advertiserId = "device-b",
            sequence = 1,
            createdAtEpochMs = now,
            expiresAtEpochMs = now + 15_000L,
            capabilities = listOf("mesh-relay", "wifi-local"),
            routes = listOf(
                AdvertisedRoute(
                    destinationId = "device-c",
                    hops = 1,
                    quality = 90,
                    transport = "wifi-local"
                )
            )
        )
        assertEquals(
            1,
            nodeA.ingestVerifiedRouteAdvertisement(
                advertisement = fromB,
                fromPeerId = "device-b",
                linkQuality = 80,
                linkTransport = "wifi-local",
                nowEpochMs = now
            )
        )

        val chunk = NativeEncryptedMediaChunk(
            assetId = "asset-multihop-0001",
            index = 0,
            count = 1,
            plainBytes = 64 * 1024,
            nonce = ByteArray(12) { (it + 1).toByte() },
            ciphertext = ByteArray(64 * 1024) { (it % 251).toByte() },
            tag = ByteArray(16) { (0x40 + it).toByte() }
        )
        val tmc1 = AndroidMediaChunkWireCodec.encode(chunk)

        val result = nodeA.sendOriginEnvelope(
            recipientId = "device-c",
            messageId = "media:${chunk.assetId}:${chunk.index}",
            encodedEnvelope = tmc1,
            nowEpochMs = now,
            ttlMs = 10 * 60_000L,
            hopLimit = 8
        )

        assertTrue(result.sent)
        assertEquals("wifi-local", result.transportId)
        assertEquals(1, linkA.forwarded.size)
        assertEquals(1, linkB.forwarded.size)
        assertEquals(1, receivedAtC.size)
        assertArrayEquals(tmc1, linkA.forwarded.single().second.encodedEnvelope)
        assertArrayEquals(tmc1, linkB.forwarded.single().second.encodedEnvelope)
        assertArrayEquals(tmc1, receivedAtC.single())
        assertEquals(1, linkB.forwarded.single().second.header.hopCount)
        assertTrue("forwarded" in eventsAtB)
    }
}
