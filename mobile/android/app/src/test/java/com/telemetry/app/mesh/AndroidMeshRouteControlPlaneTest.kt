package com.telemetry.app.mesh

import com.telemetry.app.crypto.DeviceIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidMeshRouteControlPlaneTest {
    private val a = DeviceIdentity(ByteArray(32) { it.toByte() }, ByteArray(32) { (it + 32).toByte() })
    private val b = DeviceIdentity(ByteArray(32) { (it + 64).toByte() }, ByteArray(32) { (it + 96).toByte() })

    @Test
    fun signedWireFromPeerCreatesVerifiedRoute() {
        val nodeA = AndroidMeshNodeRuntime(a.deviceId)
        val nodeB = AndroidMeshNodeRuntime(b.deviceId)
        nodeB.observeDirectPeer("device-c", 70, "ble", 1_000L)

        val controlA = AndroidMeshRouteControlPlane(nodeA, AndroidRouteAdvertisementCodec(a))
        val controlB = AndroidMeshRouteControlPlane(nodeB, AndroidRouteAdvertisementCodec(b))
        val wire = controlB.createAdvertisementWire(toPeerId = a.deviceId, nowEpochMs = 2_000L)

        val accepted = controlA.ingestAdvertisementWire(
            bytes = wire,
            fromPeerId = b.deviceId,
            signingPublicKey = b.signingPublicKey,
            linkQuality = 80,
            linkTransport = "wifi-direct",
            nowEpochMs = 2_500L
        )

        assertTrue(accepted >= 1)
        assertEquals(b.deviceId, nodeA.routeSnapshot(2_500L).first { it.destinationId == b.deviceId }.viaPeerId)
    }

    @Test
    fun tamperedWireAndPeerIdentityMismatchNeverReachRouteRuntime() {
        val nodeA = AndroidMeshNodeRuntime(a.deviceId)
        val nodeB = AndroidMeshNodeRuntime(b.deviceId)
        val controlA = AndroidMeshRouteControlPlane(nodeA, AndroidRouteAdvertisementCodec(a))
        val controlB = AndroidMeshRouteControlPlane(nodeB, AndroidRouteAdvertisementCodec(b))
        val wire = controlB.createAdvertisementWire(nowEpochMs = 2_000L)

        val tampered = wire.copyOf().also { it[it.lastIndex - 10] = (it[it.lastIndex - 10].toInt() xor 1).toByte() }
        assertEquals(
            0,
            controlA.ingestAdvertisementWire(
                tampered,
                b.deviceId,
                b.signingPublicKey,
                80,
                "ble",
                2_500L
            )
        )
        assertTrue(nodeA.routeSnapshot(2_500L).isEmpty())

        assertEquals(
            0,
            controlA.ingestAdvertisementWire(
                wire,
                "tlm:device:not-b",
                b.signingPublicKey,
                80,
                "ble",
                2_500L
            )
        )
        assertTrue(nodeA.routeSnapshot(2_500L).isEmpty())
    }
}
