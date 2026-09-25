package com.telemetry.app.mesh

class AndroidMeshNodeRuntime(
    val localDeviceId: String,
    capabilities: List<String> = listOf("ble", "mesh-relay"),
    onEvent: (MeshCoordinatorEvent) -> Unit = {},
    onLocalDelivery: (OpaqueRelayFrame) -> Unit = {}
) {
    private val routeRuntime = RouteAdvertisementRuntime(localDeviceId, capabilities)
    private val relayStore = InMemoryOpaqueRelayStore()
    private val transportCoordinator = MeshTransportCoordinator()
    private val meshCoordinator = TelemetryMeshCoordinator(
        localDeviceId = localDeviceId,
        relayStore = relayStore,
        routeResolver = RouteRuntimeResolver(routeRuntime),
        neighborSender = TransportCoordinatorNeighborSender(transportCoordinator),
        onEvent = onEvent,
        onLocalDelivery = onLocalDelivery
    )

    fun registerTransport(adapter: MeshTransportAdapter) {
        transportCoordinator.register(adapter)
    }

    fun sendOpaqueToNextHop(peerId: String, frame: OpaqueRelayFrame): MeshSendResult =
        transportCoordinator.send(peerId, frame)

    fun sendOriginEnvelope(
        recipientId: String,
        messageId: String,
        encodedEnvelope: ByteArray,
        nowEpochMs: Long = System.currentTimeMillis(),
        ttlMs: Long = 10 * 60_000L,
        hopLimit: Int = 8
    ): MeshSendResult {
        require(recipientId.isNotBlank()) { "recipientId is required" }
        require(messageId.isNotBlank()) { "messageId is required" }
        require(encodedEnvelope.isNotEmpty()) { "encodedEnvelope is required" }
        require(ttlMs in 1..(24 * 60 * 60_000L)) { "origin ttlMs invalid" }
        require(hopLimit in 1..32) { "origin hopLimit invalid" }

        val route = routeRuntime.snapshot(nowEpochMs)
            .filter { it.destinationId == recipientId && it.viaPeerId != localDeviceId }
            .sortedWith(
                compareByDescending<MobileRouteEntry> { 100 - it.hops * 15 + it.quality }
                    .thenBy { it.hops }
            )
            .firstOrNull()
            ?: return MeshSendResult(false, reason = "no-route")

        val frame = OpaqueRelayFrame(
            header = MeshRelayHeader(
                messageId = messageId,
                senderId = localDeviceId,
                recipientId = recipientId,
                hopCount = 0,
                hopLimit = hopLimit,
                relayPath = listOf(localDeviceId),
                createdAtEpochMs = nowEpochMs,
                expiresAtEpochMs = nowEpochMs + ttlMs
            ),
            encodedEnvelope = encodedEnvelope.copyOf()
        )
        return transportCoordinator.send(route.viaPeerId, frame)
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

    fun peerCapabilities(peerId: String, nowEpochMs: Long): List<String> =
        routeRuntime.capabilitiesForPeer(peerId, nowEpochMs)

    fun ingestRelayFrame(frame: OpaqueRelayFrame, nowEpochMs: Long): MeshCoordinatorResult =
        meshCoordinator.ingest(frame, nowEpochMs)

    fun flushRelayQueue(nowEpochMs: Long): List<MeshCoordinatorResult> =
        meshCoordinator.flush(nowEpochMs)

    fun pendingRelayCount(): Int = relayStore.pending().size

    fun routeSnapshot(nowEpochMs: Long): List<MobileRouteEntry> =
        routeRuntime.snapshot(nowEpochMs)
}
