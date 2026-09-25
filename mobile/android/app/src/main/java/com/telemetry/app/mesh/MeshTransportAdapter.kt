package com.telemetry.app.mesh

data class MeshPeerPath(
    val peerId: String,
    val available: Boolean,
    val quality: Int? = null,
    val metered: Boolean = false
)

data class MeshSendAttempt(
    val transportId: String,
    val accepted: Boolean,
    val error: String? = null
)

data class MeshSendResult(
    val sent: Boolean,
    val transportId: String? = null,
    val attempts: List<MeshSendAttempt> = emptyList(),
    val reason: String
)

interface MeshTransportAdapter {
    val id: String
    val maxPayloadBytes: Int
    val metered: Boolean

    fun describePeer(peerId: String): MeshPeerPath
    fun estimatedWireBytes(frame: OpaqueRelayFrame): Int = frame.encodedEnvelope.size
    fun sendOpaque(peerId: String, frame: OpaqueRelayFrame): Boolean
}

private val TRANSPORT_PRIORITY = listOf(
    "wifi-direct",
    "wifi-local",
    "wifi-aware",
    "ble",
    "internet",
    "lora",
    "satellite-gateway"
)

private fun transportScore(
    adapter: MeshTransportAdapter,
    path: MeshPeerPath,
    payloadBytes: Int
): Int {
    val priorityIndex = TRANSPORT_PRIORITY.indexOf(adapter.id)
    var score = if (priorityIndex >= 0) 100 - priorityIndex * 10 else 20

    if (adapter.id == "ble" && payloadBytes > 32_000) score -= 45
    if (adapter.id == "lora" && payloadBytes > 2_000) score -= 55
    if (adapter.id == "satellite-gateway") score -= 20
    if (adapter.metered || path.metered) score -= 15
    path.quality?.let { score += it.coerceIn(-20, 20) }
    return score
}

class MeshTransportCoordinator(
    adapters: List<MeshTransportAdapter> = emptyList()
) {
    private val adapters = LinkedHashMap<String, MeshTransportAdapter>()

    init {
        adapters.forEach(::register)
    }

    fun register(adapter: MeshTransportAdapter) {
        require(adapter.id.isNotBlank()) { "adapter id is required" }
        require(adapter.maxPayloadBytes > 0) { "maxPayloadBytes must be positive" }
        require(adapter.id !in adapters) { "duplicate transport adapter: ${adapter.id}" }
        adapters[adapter.id] = adapter
    }

    fun send(peerId: String, frame: OpaqueRelayFrame): MeshSendResult {
        require(peerId.isNotBlank()) { "peerId is required" }
        val policyPayloadBytes = frame.encodedEnvelope.size
        val candidates = adapters.values
            .map { adapter ->
                val path = adapter.describePeer(peerId)
                val wireBytes = adapter.estimatedWireBytes(frame)
                Candidate(
                    adapter = adapter,
                    path = path,
                    wireBytes = wireBytes,
                    score = transportScore(adapter, path, policyPayloadBytes)
                )
            }
            .filter { it.path.available && it.wireBytes <= it.adapter.maxPayloadBytes }
            .sortedByDescending { it.score }

        if (candidates.isEmpty()) {
            return MeshSendResult(false, reason = "no-transport-available")
        }

        val attempts = mutableListOf<MeshSendAttempt>()
        for (candidate in candidates) {
            val adapter = candidate.adapter
            try {
                val accepted = adapter.sendOpaque(peerId, frame)
                attempts += MeshSendAttempt(adapter.id, accepted)
                if (accepted) {
                    return MeshSendResult(
                        sent = true,
                        transportId = adapter.id,
                        attempts = attempts.toList(),
                        reason = if (attempts.size == 1) "selected-transport" else "fallback-transport"
                    )
                }
            } catch (error: Throwable) {
                attempts += MeshSendAttempt(adapter.id, false, error.message ?: error::class.java.simpleName)
            }
        }

        return MeshSendResult(false, attempts = attempts.toList(), reason = "all-transports-failed")
    }

    private data class Candidate(
        val adapter: MeshTransportAdapter,
        val path: MeshPeerPath,
        val wireBytes: Int,
        val score: Int
    )
}
