package com.telemetry.app.call

interface CallSignalPort {
    fun send(signal: NativeCallSignal, nowEpochMs: Long = System.currentTimeMillis()): Boolean
}

interface CallDirectCapabilityProvider {
    fun availableTransports(peerId: String): List<String>
}

data class NativeIceCandidate(
    val candidate: String,
    val sdpMid: String?,
    val sdpMLineIndex: Int
) {
    init {
        require(candidate.isNotEmpty() && candidate.length <= CALL_MAX_ICE_CHARS) { "invalid ICE candidate" }
        require(sdpMid == null || sdpMid.length <= 128) { "invalid ICE sdpMid" }
        require(sdpMLineIndex in 0..255) { "invalid ICE sdpMLineIndex" }
    }
}

sealed interface RealtimeCallMediaEvent {
    data class LocalOffer(val callId: String, val sessionDescription: String) : RealtimeCallMediaEvent
    data class LocalAnswer(val callId: String, val sessionDescription: String) : RealtimeCallMediaEvent
    data class LocalIceCandidate(val callId: String, val candidate: NativeIceCandidate) : RealtimeCallMediaEvent
    data class Connected(val callId: String) : RealtimeCallMediaEvent
    data class Disconnected(val callId: String, val reason: String) : RealtimeCallMediaEvent
    data class Failed(val callId: String, val reason: String) : RealtimeCallMediaEvent
}

interface RealtimeCallMediaEngine {
    fun setListener(listener: (RealtimeCallMediaEvent) -> Unit)

    fun prepare(
        callId: String,
        peerId: String,
        transport: String,
        isCaller: Boolean
    ): Boolean

    fun createOffer(callId: String): Boolean

    fun applyRemoteOffer(callId: String, sessionDescription: String): Boolean

    fun applyRemoteAnswer(callId: String, sessionDescription: String): Boolean

    fun addRemoteIceCandidate(callId: String, candidate: NativeIceCandidate): Boolean

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
