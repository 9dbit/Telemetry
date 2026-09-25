package com.telemetry.app.call

interface CallSignalPort {
    fun send(signal: NativeCallSignal, nowEpochMs: Long = System.currentTimeMillis()): Boolean
}

interface CallDirectCapabilityProvider {
    fun availableTransports(peerId: String): List<String>
}

data class RealtimeCallPreparation(
    val endpointToken: String
) {
    init {
        require(endpointToken.length in 8..512) { "invalid realtime endpoint token" }
    }
}

interface RealtimeCallMediaEngine {
    fun prepare(
        callId: String,
        peerId: String,
        transport: String,
        isCaller: Boolean
    ): RealtimeCallPreparation

    fun applyRemoteCandidate(callId: String, endpointToken: String): Boolean

    fun connect(callId: String): Boolean

    fun setMuted(callId: String, muted: Boolean): Boolean

    fun setSpeakerEnabled(callId: String, enabled: Boolean): Boolean

    fun stop(callId: String)
}

sealed interface NativeCallRuntimeEvent {
    data class OutgoingRinging(val callId: String, val peerId: String) : NativeCallRuntimeEvent
    data class IncomingRinging(val callId: String, val peerId: String) : NativeCallRuntimeEvent
    data class Negotiating(val callId: String, val peerId: String, val transport: String) : NativeCallRuntimeEvent
    data class Active(val callId: String, val peerId: String, val transport: String) : NativeCallRuntimeEvent
    data class Ended(val callId: String, val peerId: String, val reason: String) : NativeCallRuntimeEvent
    data class Unavailable(val peerId: String, val reason: String, val offerVoiceMessage: Boolean = true) : NativeCallRuntimeEvent
    data class Error(val callId: String?, val message: String) : NativeCallRuntimeEvent
}
