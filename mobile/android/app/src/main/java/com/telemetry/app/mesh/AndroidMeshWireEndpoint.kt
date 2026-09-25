package com.telemetry.app.mesh

import com.telemetry.app.crypto.DeviceIdentity

class AndroidMeshWireEndpoint(
    identity: DeviceIdentity,
    private val node: AndroidMeshNodeRuntime
) {
    private val routeControl = AndroidMeshRouteControlPlane(
        node = node,
        codec = AndroidRouteAdvertisementCodec(identity)
    )

    fun createRouteWire(
        toPeerId: String? = null,
        nowEpochMs: Long,
        ttlMs: Long = 15_000L
    ): ByteArray = routeControl.createAdvertisementWire(toPeerId, nowEpochMs, ttlMs)

    fun ingestRouteWire(
        bytes: ByteArray,
        fromPeerId: String,
        signingPublicKey: ByteArray,
        linkQuality: Int,
        linkTransport: String,
        nowEpochMs: Long
    ): Int = routeControl.ingestAdvertisementWire(
        bytes = bytes,
        fromPeerId = fromPeerId,
        signingPublicKey = signingPublicKey,
        linkQuality = linkQuality,
        linkTransport = linkTransport,
        nowEpochMs = nowEpochMs
    )

    fun ingestRelayWire(bytes: ByteArray, nowEpochMs: Long): MeshCoordinatorResult? =
        runCatching {
            node.ingestRelayFrame(MeshRelayWireCodec.decode(bytes), nowEpochMs)
        }.getOrNull()

    fun flush(nowEpochMs: Long): List<MeshCoordinatorResult> =
        node.flushRelayQueue(nowEpochMs)
}
