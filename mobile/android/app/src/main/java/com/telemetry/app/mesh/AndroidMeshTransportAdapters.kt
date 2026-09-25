package com.telemetry.app.mesh

data class NativePeerLinkState(
    val available: Boolean,
    val quality: Int? = null,
    val metered: Boolean = false
)

interface BleMeshRelayPort {
    fun peerState(peerId: String): NativePeerLinkState
    fun sendRelayWire(peerId: String, bytes: ByteArray): Boolean
}

interface WifiDirectMeshRelayPort {
    fun peerState(peerId: String): NativePeerLinkState
    fun sendRelayWire(peerId: String, bytes: ByteArray): Boolean
}

class BleMeshTransportAdapter(
    private val port: BleMeshRelayPort
) : MeshTransportAdapter {
    override val id: String = "ble"
    override val maxPayloadBytes: Int = 480
    override val metered: Boolean = false

    override fun describePeer(peerId: String): MeshPeerPath {
        val state = port.peerState(peerId)
        return MeshPeerPath(peerId, state.available, state.quality, state.metered)
    }

    override fun estimatedWireBytes(frame: OpaqueRelayFrame): Int =
        MeshRelayWireCodec.encode(frame).size

    override fun sendOpaque(peerId: String, frame: OpaqueRelayFrame): Boolean {
        val wire = MeshRelayWireCodec.encode(frame)
        if (wire.size > maxPayloadBytes) return false
        return port.sendRelayWire(peerId, wire)
    }
}

class WifiDirectMeshTransportAdapter(
    private val port: WifiDirectMeshRelayPort
) : MeshTransportAdapter {
    override val id: String = "wifi-direct"
    override val maxPayloadBytes: Int = 2 * 1024 * 1024
    override val metered: Boolean = false

    override fun describePeer(peerId: String): MeshPeerPath {
        val state = port.peerState(peerId)
        return MeshPeerPath(peerId, state.available, state.quality, state.metered)
    }

    override fun estimatedWireBytes(frame: OpaqueRelayFrame): Int =
        MeshRelayWireCodec.encode(frame).size

    override fun sendOpaque(peerId: String, frame: OpaqueRelayFrame): Boolean {
        val wire = MeshRelayWireCodec.encode(frame)
        if (wire.size > maxPayloadBytes) return false
        return port.sendRelayWire(peerId, wire)
    }
}
