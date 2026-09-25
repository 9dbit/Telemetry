package com.telemetry.app.mesh

interface MeshRouteResolver {
    fun resolve(destinationId: String, excludePeerIds: Set<String> = emptySet()): MeshRouteEvidence?
}

interface MeshNeighborSender {
    fun send(nextHopPeerId: String, frame: OpaqueRelayFrame, transport: String): Boolean
}

data class MeshCoordinatorResult(
    val messageId: String,
    val state: String,
    val reason: String,
    val route: MeshRouteEvidence? = null
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
        val route = routeResolver.resolve(frame.header.recipientId, excluded)
            ?: return MeshCoordinatorResult(messageId, "stored", "no-route")

        if (route.viaPeerId in excluded) {
            emit("drop", frame, route, "route-loop")
            return MeshCoordinatorResult(messageId, "stored", "route-loop", route)
        }

        val forwardedHeader = runCatching { frame.header.advancedBy(localDeviceId) }.getOrElse {
            emit("drop", frame, route, it.message ?: "forward-rejected")
            return MeshCoordinatorResult(messageId, "dropped", it.message ?: "forward-rejected", route)
        }
        val forwarded = OpaqueRelayFrame(forwardedHeader, frame.encodedEnvelope.copyOf())
        emit("route-selected", forwarded, route)

        val sent = neighborSender.send(route.viaPeerId, forwarded, route.transport)
        if (!sent) {
            emit("retry", forwarded, route, "next-hop-send-failed")
            return MeshCoordinatorResult(messageId, "stored", "next-hop-send-failed", route)
        }

        relayStore.remove(messageId)
        emit("forwarded", forwarded, route)
        return MeshCoordinatorResult(messageId, "forwarded", "next-hop-accepted", route)
    }

    private fun emit(
        type: String,
        frame: OpaqueRelayFrame,
        route: MeshRouteEvidence? = null,
        reason: String? = null
    ) {
        onEvent(
            MeshCoordinatorEvent(
                type = type,
                messageId = frame.header.messageId,
                recipientId = frame.header.recipientId,
                hopCount = frame.header.hopCount,
                viaPeerId = route?.viaPeerId,
                transport = route?.transport,
                reason = reason
            )
        )
    }
}
