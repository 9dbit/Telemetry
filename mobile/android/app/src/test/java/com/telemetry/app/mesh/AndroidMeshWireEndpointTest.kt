package com.telemetry.app.mesh

import com.telemetry.app.crypto.TelemetryCrypto
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidMeshWireEndpointTest {
    private class WifiPort : WifiDirectMeshRelayPort {
        val availablePeers = mutableSetOf<String>()
        val sent = mutableListOf<Pair<String, ByteArray>>()

        override fun peerState(peerId: String) = NativePeerLinkState(
            available = peerId in availablePeers,
            quality = 20
        )

        override fun sendRelayWire(peerId: String, bytes: ByteArray): Boolean {
            if (peerId !in availablePeers) return false
            sent += peerId to bytes.copyOf()
            return true
        }
    }

    private fun originFrame(recipientId: String) = OpaqueRelayFrame(
        header = MeshRelayHeader(
            messageId = "wire-a-b-c-1",
            senderId = "device-a",
            recipientId = recipientId,
            hopCount = 0,
            hopLimit = 4,
            relayPath = listOf("device-a"),
            createdAtEpochMs = 1_000L,
            expiresAtEpochMs = 30_000L
        ),
        encodedEnvelope = byteArrayOf(9, 8, 7, 6, 5, 4)
    )

    @Test
    fun signedRouteWireReleasesStoredOpaqueRelayFromBToC() {
        val bIdentity = TelemetryCrypto.newIdentity()
        val cIdentity = TelemetryCrypto.newIdentity()
        val nodeB = AndroidMeshNodeRuntime(bIdentity.deviceId)
        val nodeC = AndroidMeshNodeRuntime(cIdentity.deviceId)
        val endpointB = AndroidMeshWireEndpoint(bIdentity, nodeB)
        val endpointC = AndroidMeshWireEndpoint(cIdentity, nodeC)
        val wifi = WifiPort().apply { availablePeers += cIdentity.deviceId }
        nodeB.registerTransport(WifiDirectMeshTransportAdapter(wifi))

        val original = originFrame(cIdentity.deviceId)
        val first = endpointB.ingestRelayWire(MeshRelayWireCodec.encode(original), 1_000L)
        assertNotNull(first)
        assertEquals("stored", first!!.state)
        assertEquals("no-route", first.reason)
        assertEquals(1, nodeB.pendingRelayCount())

        val routeWire = endpointC.createRouteWire(toPeerId = bIdentity.deviceId, nowEpochMs = 2_000L)
        val routesAccepted = endpointB.ingestRouteWire(
            bytes = routeWire,
            fromPeerId = cIdentity.deviceId,
            signingPublicKey = cIdentity.signingPublicKey,
            linkQuality = 80,
            linkTransport = "wifi-direct",
            nowEpochMs = 2_500L
        )

        assertTrue(routesAccepted >= 1)
        assertEquals(0, nodeB.pendingRelayCount())
        assertEquals(1, wifi.sent.size)
        assertEquals(cIdentity.deviceId, wifi.sent.single().first)

        val forwarded = MeshRelayWireCodec.decode(wifi.sent.single().second)
        assertEquals(original.header.messageId, forwarded.header.messageId)
        assertEquals(original.header.senderId, forwarded.header.senderId)
        assertEquals(original.header.recipientId, forwarded.header.recipientId)
        assertEquals(1, forwarded.header.hopCount)
        assertEquals(listOf("device-a", bIdentity.deviceId), forwarded.header.relayPath)
        assertArrayEquals(original.encodedEnvelope, forwarded.encodedEnvelope)

        val delivered = endpointC.ingestRelayWire(wifi.sent.single().second, 3_000L)
        assertNotNull(delivered)
        assertEquals("deliver-local", delivered!!.state)
    }

    @Test
    fun tamperedRouteWireCannotReleaseStoredRelay() {
        val bIdentity = TelemetryCrypto.newIdentity()
        val cIdentity = TelemetryCrypto.newIdentity()
        val nodeB = AndroidMeshNodeRuntime(bIdentity.deviceId)
        val nodeC = AndroidMeshNodeRuntime(cIdentity.deviceId)
        val endpointB = AndroidMeshWireEndpoint(bIdentity, nodeB)
        val endpointC = AndroidMeshWireEndpoint(cIdentity, nodeC)
        val wifi = WifiPort().apply { availablePeers += cIdentity.deviceId }
        nodeB.registerTransport(WifiDirectMeshTransportAdapter(wifi))

        endpointB.ingestRelayWire(MeshRelayWireCodec.encode(originFrame(cIdentity.deviceId)), 1_000L)
        val tampered = endpointC.createRouteWire(nowEpochMs = 2_000L).copyOf().also {
            it[it.lastIndex] = (it.last().toInt() xor 1).toByte()
        }

        assertEquals(
            0,
            endpointB.ingestRouteWire(
                tampered,
                cIdentity.deviceId,
                cIdentity.signingPublicKey,
                80,
                "wifi-direct",
                2_500L
            )
        )
        assertEquals(1, nodeB.pendingRelayCount())
        assertTrue(wifi.sent.isEmpty())
    }
}
