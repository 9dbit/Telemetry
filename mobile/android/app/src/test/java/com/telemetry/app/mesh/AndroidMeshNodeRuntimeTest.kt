package com.telemetry.app.mesh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidMeshNodeRuntimeTest {
    private class RecordingAdapter(
        override val id: String,
        override val maxPayloadBytes: Int = 1_000_000,
        override val metered: Boolean = false
    ) : MeshTransportAdapter {
        val availablePeers = mutableSetOf<String>()
        val sent = mutableListOf<Pair<String, OpaqueRelayFrame>>()

        override fun describePeer(peerId: String) = MeshPeerPath(
            peerId = peerId,
            available = peerId in availablePeers,
            quality = 10,
            metered = false
        )

        override fun sendOpaque(peerId: String, frame: OpaqueRelayFrame): Boolean {
            if (peerId !in availablePeers) return false
            sent += peerId to frame
            return true
        }
    }

    private fun relayFrame() = OpaqueRelayFrame(
        MeshRelayHeader(
            messageId = "runtime-1",
            senderId = "device-a",
            recipientId = "device-c",
            hopCount = 0,
            hopLimit = 4,
            relayPath = listOf("device-a"),
            expiresAtEpochMs = 20_000L
        ),
        byteArrayOf(1, 2, 3, 4)
    )

    @Test
    fun storedRelayFlushesWhenVerifiedRouteAndTransportBecomeAvailable() {
        val events = mutableListOf<MeshCoordinatorEvent>()
        val runtime = AndroidMeshNodeRuntime("device-b", onEvent = events::add)
        val wifi = RecordingAdapter("wifi-direct")
        runtime.registerTransport(wifi)

        val initial = runtime.ingestRelayFrame(relayFrame(), 1_000L)
        assertEquals("stored", initial.state)
        assertEquals(1, runtime.pendingRelayCount())

        wifi.availablePeers += "device-c"
        val advertisement = VerifiedRouteAdvertisement(
            protocol = "telemetry/mesh-route/0.1",
            advertiserId = "device-c",
            sequence = 1,
            createdAtEpochMs = 2_000L,
            expiresAtEpochMs = 17_000L,
            capabilities = listOf("wifi-direct", "mesh-relay"),
            routes = listOf(AdvertisedRoute("device-c", 0, 100, "self"))
        )
        assertEquals(
            1,
            runtime.ingestVerifiedRouteAdvertisement(
                advertisement,
                fromPeerId = "device-c",
                linkQuality = 80,
                linkTransport = "wifi-direct",
                nowEpochMs = 2_000L
            )
        )

        assertEquals(0, runtime.pendingRelayCount())
        assertEquals(1, wifi.sent.size)
        assertEquals("runtime-1", wifi.sent.single().second.header.messageId)
        assertEquals(1, wifi.sent.single().second.header.hopCount)
        assertEquals(listOf("device-a", "device-b"), wifi.sent.single().second.header.relayPath)
        assertEquals("wifi-direct", events.last { it.type == "forwarded" }.transport)
    }

    @Test
    fun directPeerObservationCreatesRouteWithoutLeakingLocation() {
        val runtime = AndroidMeshNodeRuntime("device-b")
        runtime.observeDirectPeer(
            peerId = "device-c",
            quality = 65,
            transport = "ble",
            nowEpochMs = 1_000L
        )

        val routes = runtime.routeSnapshot(1_000L)
        assertEquals(1, routes.size)
        assertEquals("device-c", routes.single().destinationId)
        assertEquals("ble", routes.single().transport)
        assertTrue(routes.single().quality == 65)
    }
}
