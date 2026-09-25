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
    fun sendOpaque(peerId: String, frame: OpaqueRelayFrame): Boolean
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
        require(adapter.id !in adapters) { "duplicate transport adapter: ${adapter.id}" }
        adapters[adapter.id] = adapter
    }

    fun send(peerId: String, frame: OpaqueRelayFrame): MeshSendResult {
        val payloadBytes = frame.encodedEnvelope.size
        val candidates = adapters.values
            .map { adapter -> adapter to adapter.describePeer(peerId) }
            .filter { (adapter, path) -> path.available && payloadBytes <= adapter.maxPayloadBytes }
            .sortedWith(
                compareByDescending<Pair<MeshTransportAdapter, MeshPeerPath>> { (_, path) -> path.quality ?: Int.MIN_VALUE }
                    .thenBy { (adapter, path) -> adapter.metered || path.metered }
                    .thenBy { (adapter, _) -> adapter.id }
            )

        if (candidates.isEmpty()) {
            return MeshSendResult(false, reason = "no-transport-available")
        }

        val attempts = mutableListOf<MeshSendAttempt>()
        for ((adapter, _) in candidates) {
            try {
                val accepted = adapter.sendOpaque(peerId, frame)
                attempts += MeshSendAttempt(adapter.id, accepted)
                if (accepted) {
                    return MeshSendResult(true, adapter.id, attempts.toList(), "selected-transport")
                }
            } catch (error: Throwable) {
                attempts += MeshSendAttempt(adapter.id, false, error.message ?: error::class.java.simpleName)
            }
        }

        return MeshSendResult(false, attempts = attempts.toList(), reason = "all-transports-failed")
    }
}
