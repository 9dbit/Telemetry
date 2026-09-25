package com.telemetry.app.mesh

enum class MeshIngressAction {
    DELIVER_LOCAL,
    RELAY,
    DROP
}

data class MeshRelayHeader(
    val messageId: String,
    val senderId: String,
    val recipientId: String,
    val hopCount: Int,
    val hopLimit: Int,
    val relayPath: List<String>,
    val expiresAtEpochMs: Long
) {
    init {
        require(messageId.isNotBlank()) { "messageId is required" }
        require(senderId.isNotBlank()) { "senderId is required" }
        require(recipientId.isNotBlank()) { "recipientId is required" }
        require(hopLimit in 1..32) { "hopLimit must be between 1 and 32" }
        require(hopCount in 0..hopLimit) { "hopCount must be between 0 and hopLimit" }
    }

    fun advancedBy(relayDeviceId: String): MeshRelayHeader {
        require(relayDeviceId.isNotBlank()) { "relayDeviceId is required" }
        require(relayDeviceId !in relayPath) { "relay loop detected" }
        require(hopCount < hopLimit) { "hop limit reached" }
        return copy(
            hopCount = hopCount + 1,
            relayPath = relayPath + relayDeviceId
        )
    }
}

data class OpaqueRelayFrame(
    val header: MeshRelayHeader,
    val encodedEnvelope: ByteArray
) {
    init {
        require(encodedEnvelope.isNotEmpty()) { "encoded envelope is required" }
    }

    fun copyForStore(): OpaqueRelayFrame = copy(encodedEnvelope = encodedEnvelope.copyOf())
}

data class MeshRouteEvidence(
    val destinationId: String,
    val viaPeerId: String,
    val hops: Int,
    val quality: Int,
    val transport: String
)

data class MeshIngressDecision(
    val action: MeshIngressAction,
    val reason: String
)

interface TelemetryMeshBridge {
    fun ingest(frame: OpaqueRelayFrame, localDeviceId: String, nowEpochMs: Long): MeshIngressDecision
    fun store(frame: OpaqueRelayFrame): Boolean
    fun remove(messageId: String): Boolean
    fun pending(): List<OpaqueRelayFrame>
}

class InMemoryOpaqueRelayStore : TelemetryMeshBridge {
    private val stored = LinkedHashMap<String, OpaqueRelayFrame>()

    override fun ingest(
        frame: OpaqueRelayFrame,
        localDeviceId: String,
        nowEpochMs: Long
    ): MeshIngressDecision {
        if (frame.header.expiresAtEpochMs <= nowEpochMs) {
            return MeshIngressDecision(MeshIngressAction.DROP, "expired")
        }
        if (localDeviceId in frame.header.relayPath) {
            return MeshIngressDecision(MeshIngressAction.DROP, "relay-loop")
        }
        if (frame.header.recipientId == localDeviceId) {
            return MeshIngressDecision(MeshIngressAction.DELIVER_LOCAL, "recipient-local")
        }
        if (frame.header.hopCount >= frame.header.hopLimit) {
            return MeshIngressDecision(MeshIngressAction.DROP, "hop-limit")
        }
        if (stored.containsKey(frame.header.messageId)) {
            return MeshIngressDecision(MeshIngressAction.DROP, "duplicate")
        }
        return MeshIngressDecision(MeshIngressAction.RELAY, "relay-eligible")
    }

    override fun store(frame: OpaqueRelayFrame): Boolean {
        if (stored.containsKey(frame.header.messageId)) return false
        stored[frame.header.messageId] = frame.copyForStore()
        return true
    }

    override fun remove(messageId: String): Boolean = stored.remove(messageId) != null

    override fun pending(): List<OpaqueRelayFrame> = stored.values.map { it.copyForStore() }
}
