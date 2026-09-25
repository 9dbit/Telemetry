package com.telemetry.app.mesh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidMeshTransportAdaptersTest {
    private class BlePort : BleMeshRelayPort {
        var available = true
        var accepted = true
        var lastWire: ByteArray? = null
        override fun peerState(peerId: String) = NativePeerLinkState(available, quality = 5)
        override fun sendRelayWire(peerId: String, bytes: ByteArray): Boolean {
            lastWire = bytes
            return accepted
        }
    }

    private class WifiPort : WifiDirectMeshRelayPort {
        var available = true
        var accepted = true
        var lastWire: ByteArray? = null
        override fun peerState(peerId: String) = NativePeerLinkState(available, quality = 5)
        override fun sendRelayWire(peerId: String, bytes: ByteArray): Boolean {
            lastWire = bytes
            return accepted
        }
    }

    private fun frame(envelopeSize: Int = 64) = OpaqueRelayFrame(
        MeshRelayHeader(
            messageId = "adapter-1",
            senderId = "device-a",
            recipientId = "device-c",
            hopCount = 0,
            hopLimit = 4,
            relayPath = listOf("device-a"),
            createdAtEpochMs = 1_000L,
            expiresAtEpochMs = 20_000L
        ),
        ByteArray(envelopeSize) { 3 }
    )

    @Test
    fun adaptersSendSameDeterministicWireFormat() {
        val blePort = BlePort()
        val wifiPort = WifiPort()
        val ble = BleMeshTransportAdapter(blePort)
        val wifi = WifiDirectMeshTransportAdapter(wifiPort)
        val original = frame()

        assertTrue(ble.sendOpaque("device-c", original))
        assertTrue(wifi.sendOpaque("device-c", original))
        assertTrue(blePort.lastWire!!.contentEquals(wifiPort.lastWire!!))
        assertEquals(original.header, MeshRelayWireCodec.decode(blePort.lastWire!!).header)
    }

    @Test
    fun BLERejectsOversizeWireWithoutCallingNativePort() {
        val port = BlePort()
        val adapter = BleMeshTransportAdapter(port)

        assertFalse(adapter.sendOpaque("device-c", frame(envelopeSize = 600)))
        assertEquals(null, port.lastWire)
    }

    @Test
    fun coordinatorFallsBackToWifiWhenBleCannotCarryWireFrame() {
        val blePort = BlePort()
        val wifiPort = WifiPort()
        val coordinator = MeshTransportCoordinator(
            listOf(BleMeshTransportAdapter(blePort), WifiDirectMeshTransportAdapter(wifiPort))
        )

        val result = coordinator.send("device-c", frame(envelopeSize = 600))

        assertTrue(result.sent)
        assertEquals("wifi-direct", result.transportId)
        assertEquals(null, blePort.lastWire)
        assertTrue(wifiPort.lastWire != null)
    }
}
