package com.telemetry.app.mesh

class AndroidMeshNodeRuntime(
    val localDeviceId: String,
    capabilities: List<String> = listOf("ble", "mesh-relay"),
    onEvent: (MeshCoordinatorEvent) -> Unit = {}
) {
    private val routeRuntime = RouteAdvertisementRuntime(localDeviceId, capabilities)
    private val relayStore = InMemoryOpaqueRelayStore()
    private val transportCoordinator = MeshTransportCoordinator()
    private val meshCoordinator = TelemetryMeshCoordinator(
        localDeviceId = localDeviceId,
        relayStore = relayStore,
        routeResolver = RouteRuntimeResolver(routeRuntime),
        neighborSender = TransportCoordinatorNeighborSender(transportCoordinator),
        onEvent = onEvent
    )

    fun registerTransport(adapter: MeshTransportAdapter) {
        transportCoordinator.register(adapter)
    }

    fun observeDirectPeer(
        peerId: String,
        quality: Int,
        transport: String,
        nowEpochMs: Long,
        ttlMs: Long = 15_000L
    ) {
        routeRuntime.observeDirectPeer(peerId, quality, transport, nowEpochMs, ttlMs)
        meshCoordinator.flush(nowEpochMs)
    }

    fun peerUnavailable(peerId: String): Int = routeRuntime.removeViaPeer(peerId)

    fun createRouteAdvertisementDraft(
        toPeerId: String? = null,
        nowEpochMs: Long,
        ttlMs: Long = 15_000L
    ): RouteAdvertisementDraft = routeRuntime.createDraft(toPeerId, nowEpochMs, ttlMs)

    fun ingestVerifiedRouteAdvertisement(
        advertisement: VerifiedRouteAdvertisement,
        fromPeerId: String,
        linkQuality: Int,
        linkTransport: String,
        nowEpochMs: Long
    ): Int {
        val accepted = routeRuntime.ingestVerified(
            advertisement,
            fromPeerId,
            linkQuality,
            linkTransport,
            nowEpochMs
        )
        if (accepted > 0) meshCoordinator.flush(nowEpochMs)
        return accepted
    }

    fun ingestRelayFrame(frame: OpaqueRelayFrame, nowEpochMs: Long): MeshCoordinatorResult =
        meshCoordinator.ingest(frame, nowEpochMs)

    fun flushRelayQueue(nowEpochMs: Long): List<MeshCoordinatorResult> =
        meshCoordinator.flush(nowEpochMs)

    fun pendingRelayCount(): Int = relayStore.pending().size

    fun routeSnapshot(nowEpochMs: Long): List<MobileRouteEntry> =
        routeRuntime.snapshot(nowEpochMs)
}
