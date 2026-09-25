package com.telemetry.app.transport

import com.telemetry.app.mesh.BleMeshRelayPort
import com.telemetry.app.mesh.NativePeerLinkState

class TelemetryGattMeshRelayPort(
    private val transport: TelemetryGattTransport
) : BleMeshRelayPort {
    override fun peerState(peerId: String): NativePeerLinkState = NativePeerLinkState(
        available = transport.isMeshPeerAvailable(peerId),
        quality = 0,
        metered = false
    )

    override fun sendRelayWire(peerId: String, bytes: ByteArray): Boolean =
        transport.sendMeshRelayWire(peerId, bytes)

    fun sendRouteWire(peerId: String, bytes: ByteArray): Boolean =
        transport.sendMeshRouteWire(peerId, bytes)
}
