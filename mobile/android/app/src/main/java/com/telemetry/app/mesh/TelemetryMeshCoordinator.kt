package com.telemetry.app.mesh

interface MeshRouteResolver {
    fun resolve(
        destinationId: String,
        excludePeerIds: Set<String> = emptySet(),
        nowEpochMs: Long
    ): MeshRouteEvidence?
}

interface MeshNeighborSender {
    fun send(nextHopPeerId: String, frame: OpaqueRelayFrame): MeshSendResult
}

data class MeshCoordinatorResult(
    val messageId: String,
    val state: String,
    val reason: String,
    val route: MeshRouteEvidence? = null,
    val transportId: String? = null
)

data class MeshCoordinatorEvent(
    val type: String,
    val messageId: String,
    val recipientId: String,
    val hopCount: Int,
    val viaPeerId: String? = null,
    val transport: String? = null,
    val reason: String? = null
)

class TelemetryMeshCoordinator(
    private val localDeviceId: String,
    private val relayStore: TelemetryMeshBridge,
    private val routeResolver: MeshRouteResolver,
    private val neighborSender: MeshNeighborSender,
    private val onEvent: (MeshCoordinatorEvent) -> Unit = {}
) {
    init {
        require(localDeviceId.isNotBlank()) { "localDeviceId is required" }
    }

    fun ingest(frame: OpaqueRelayFrame, nowEpochMs: Long): MeshCoordinatorResult {
        val decision = relayStore.ingest(frame, localDeviceId, nowEpochMs)
        if (decision.action == MeshIngressAction.DELIVER_LOCAL) {
            emit("deliver-local", frame, reason = decision.reason)
            return MeshCoordinatorResult(frame.header.messageId, "deliver-local", decision.reason)
        }
        if (decision.action == MeshIngressAction.DROP) {
            emit("drop", frame, reason = decision.reason)
            return MeshCoordinatorResult(frame.header.messageId, "dropped", decision.reason)
        }

        relayStore.store(frame)
        emit("stored", frame, reason = "relay-eligible")
        return forwardOne(frame.header.messageId, nowEpochMs)
    }

    fun flush(nowEpochMs: Long): List<MeshCoordinatorResult> =
        relayStore.pending().map { forwardOne(it.header.messageId, nowEpochMs) }

    private fun forwardOne(messageId: String, nowEpochMs: Long): MeshCoordinatorResult {
        val frame = relayStore.pending().firstOrNull { it.header.messageId == messageId }
            ?: return MeshCoordinatorResult(messageId, "missing", "relay-frame-not-found")

        if (frame.header.expiresAtEpochMs <= nowEpochMs) {
            relayStore.remove(messageId)
            emit("drop", frame, reason = "expired")
            return MeshCoordinatorResult(messageId, "dropped", "expired")
        }

        val excluded = frame.header.relayPath.toMutableSet().apply { add(localDeviceId) }
        val route = routeResolver.resolve(frame.header.recipientId, excluded, nowEpochMs)
            ?: return MeshCoordinatorResult(messageId, "stored", "no-route")

        if (route.viaPeerId in excluded) {
            emit("drop", frame, route, reason = "route-loop")
            return MeshCoordinatorResult(messageId, "stored", "route-loop", route)
        }

        val forwardedHeader = runCatching { frame.header.advancedBy(localDeviceId) }.getOrElse {
            emit("drop", frame, route, reason = it.message ?: "forward-rejected")
            return MeshCoordinatorResult(messageId, "dropped", it.message ?: "forward-rejected", route)
        }
        val forwarded = OpaqueRelayFrame(forwardedHeader, frame.encodedEnvelope.copyOf())
        emit("route-selected", forwarded, route, reason = route.transport)

        val sendResult = neighborSender.send(route.viaPeerId, forwarded)
        if (!sendResult.sent) {
            emit("retry", forwarded, route, sendResult.transportId, sendResult.reason)
            return MeshCoordinatorResult(
                messageId,
                "stored",
                sendResult.reason,
                route,
                sendResult.transportId
            )
        }

        relayStore.remove(messageId)
        emit("forwarded", forwarded, route, sendResult.transportId, sendResult.reason)
        return MeshCoordinatorResult(
            messageId,
            "forwarded",
            "next-hop-accepted",
            route,
            sendResult.transportId
        )
    }

    private fun emit(
        type: String,
        frame: OpaqueRelayFrame,
        route: MeshRouteEvidence? = null,
        transportId: String? = null,
        reason: String? = null
    ) {
        onEvent(
            MeshCoordinatorEvent(
                type = type,
                messageId = frame.header.messageId,
                recipientId = frame.header.recipientId,
                hopCount = frame.header.hopCount,
                viaPeerId = route?.viaPeerId,
                transport = transportId,
                reason = reason
            )
        )
    }
}

class RouteRuntimeResolver(
    private val runtime: RouteAdvertisementRuntime
) : MeshRouteResolver {
    override fun resolve(
        destinationId: String,
        excludePeerIds: Set<String>,
        nowEpochMs: Long
    ): MeshRouteEvidence? {
        return runtime.snapshot(nowEpochMs)
            .filter { it.destinationId == destinationId && it.viaPeerId !in excludePeerIds }
            .sortedWith(
                compareByDescending<MobileRouteEntry> { 100 - it.hops * 15 + it.quality }
                    .thenBy { it.hops }
            )
            .firstOrNull()
            ?.let {
                MeshRouteEvidence(
                    destinationId = it.destinationId,
                    viaPeerId = it.viaPeerId,
                    hops = it.hops,
                    quality = it.quality,
                    transport = it.transport
                )
            }
    }
}

class TransportCoordinatorNeighborSender(
    private val coordinator: MeshTransportCoordinator
) : MeshNeighborSender {
    override fun send(nextHopPeerId: String, frame: OpaqueRelayFrame): MeshSendResult =
        coordinator.send(nextHopPeerId, frame)
}
