package com.telemetry.app.call

import java.time.Instant
import java.util.UUID

class AndroidCallRuntime(
    private val localDeviceId: String,
    private val signalPort: CallSignalPort,
    private val capabilityProvider: CallDirectCapabilityProvider,
    private val mediaEngine: RealtimeCallMediaEngine,
    private val onEvent: (NativeCallRuntimeEvent) -> Unit = {}
) {
    private data class Session(
        val callId: String,
        val callerId: String,
        val calleeId: String,
        val peerId: String,
        val isCaller: Boolean,
        val offeredTransports: List<String>,
        var state: String,
        var localSequence: Long,
        var lastRemoteSequence: Long,
        var expiresAtEpochMs: Long,
        var selectedTransport: String? = null,
        var mediaPrepared: Boolean = false
    )

    private var session: Session? = null

    fun startOutgoing(
        peerId: String,
        nowEpochMs: Long = System.currentTimeMillis(),
        timeoutMs: Long = CALL_DEFAULT_TIMEOUT_MS
    ): String? {
        if (session != null) {
            onEvent(NativeCallRuntimeEvent.Unavailable(peerId, "another-call-active"))
            return null
        }
        val direct = directCapabilities(peerId)
        if (direct.isEmpty()) {
            onEvent(NativeCallRuntimeEvent.Unavailable(peerId, "no-direct-realtime-path"))
            return null
        }
        val callId = UUID.randomUUID().toString()
        val invite = AndroidCallProtocol.createSignal(
            kind = "invite",
            callId = callId,
            callerId = localDeviceId,
            calleeId = peerId,
            fromId = localDeviceId,
            sequence = 1,
            nowEpochMs = nowEpochMs,
            timeoutMs = timeoutMs,
            directTransports = direct
        )
        if (!signalPort.send(invite, nowEpochMs)) {
            onEvent(NativeCallRuntimeEvent.Unavailable(peerId, "call-signaling-route-unavailable"))
            return null
        }
        session = Session(
            callId = callId,
            callerId = localDeviceId,
            calleeId = peerId,
            peerId = peerId,
            isCaller = true,
            offeredTransports = direct,
            state = "outgoing-ringing",
            localSequence = 1,
            lastRemoteSequence = -1,
            expiresAtEpochMs = nowEpochMs + timeoutMs
        )
        onEvent(NativeCallRuntimeEvent.OutgoingRinging(callId, peerId))
        return callId
    }

    fun ingest(signal: NativeCallSignal, nowEpochMs: Long = System.currentTimeMillis()): Boolean {
        return runCatching {
            AndroidCallProtocol.validate(signal)
            require(signal.toId == localDeviceId) { "call signal is not addressed to local device" }

            val current = session
            if (signal.kind == "invite") {
                return@runCatching ingestInvite(signal, nowEpochMs)
            }
            if (current == null) return@runCatching false
            if (signal.callId != current.callId || signal.fromId != current.peerId) return@runCatching false
            if (signal.sequence <= current.lastRemoteSequence) return@runCatching false
            if (kotlin.math.abs(nowEpochMs - Instant.parse(signal.createdAt).toEpochMilli()) > 5 * 60_000L) {
                return@runCatching false
            }

            val accepted = when (signal.kind) {
                "ringing" -> {
                    if (!current.isCaller || current.state !in setOf("outgoing-ringing", "outgoing")) false
                    else {
                        current.state = "outgoing-ringing"
                        onEvent(NativeCallRuntimeEvent.OutgoingRinging(current.callId, current.peerId))
                        true
                    }
                }
                "accept" -> handleAccept(current, signal, nowEpochMs)
                "candidate" -> handleCandidate(current, signal, nowEpochMs)
                "connected" -> handleConnected(current, signal)
                "decline", "busy", "cancel", "end" -> {
                    finish(current, signal.reason ?: signal.kind)
                    true
                }
                else -> false
            }
            if (accepted) current.lastRemoteSequence = signal.sequence
            accepted
        }.getOrElse {
            onEvent(NativeCallRuntimeEvent.Error(session?.callId, it.message ?: "call-signal-rejected"))
            false
        }
    }

    fun acceptIncoming(nowEpochMs: Long = System.currentTimeMillis()): Boolean {
        val current = session ?: return false
        if (current.isCaller || current.state != "incoming-ringing") return false
        val selected = selectCommonTransport(current.peerId, current.offeredTransports)
        if (selected == null) {
            sendTerminal(current, "decline", "no-direct-realtime-path", nowEpochMs)
            finish(current, "no-direct-realtime-path")
            onEvent(NativeCallRuntimeEvent.Unavailable(current.peerId, "no-direct-realtime-path"))
            return false
        }
        return runCatching {
            val prepared = mediaEngine.prepare(
                callId = current.callId,
                peerId = current.peerId,
                transport = selected,
                isCaller = false
            )
            current.selectedTransport = selected
            current.mediaPrepared = true
            current.state = "negotiating"
            if (!sendSignal(current, "accept", nowEpochMs, transport = selected)) error("call accept signaling failed")
            if (!sendSignal(
                    current,
                    "candidate",
                    nowEpochMs,
                    transport = selected,
                    endpointToken = prepared.endpointToken
                )
            ) error("call candidate signaling failed")
            onEvent(NativeCallRuntimeEvent.Negotiating(current.callId, current.peerId, selected))
            true
        }.getOrElse {
            if (current.mediaPrepared) mediaEngine.stop(current.callId)
            current.mediaPrepared = false
            onEvent(NativeCallRuntimeEvent.Error(current.callId, it.message ?: "call-accept-failed"))
            false
        }
    }

    fun declineIncoming(reason: String = "declined", nowEpochMs: Long = System.currentTimeMillis()): Boolean {
        val current = session ?: return false
        if (current.isCaller || current.state != "incoming-ringing") return false
        sendTerminal(current, "decline", reason, nowEpochMs)
        finish(current, reason)
        return true
    }

    fun cancelOutgoing(nowEpochMs: Long = System.currentTimeMillis()): Boolean {
        val current = session ?: return false
        if (!current.isCaller || current.state == "active") return false
        sendTerminal(current, "cancel", "caller-cancelled", nowEpochMs)
        finish(current, "caller-cancelled")
        return true
    }

    fun hangup(reason: String = "local-hangup", nowEpochMs: Long = System.currentTimeMillis()): Boolean {
        val current = session ?: return false
        sendTerminal(current, "end", reason, nowEpochMs)
        finish(current, reason)
        return true
    }

    fun tick(nowEpochMs: Long = System.currentTimeMillis()): Boolean {
        val current = session ?: return false
        if (current.state == "active" || nowEpochMs < current.expiresAtEpochMs) return false
        val kind = if (current.isCaller) "cancel" else "decline"
        sendTerminal(current, kind, "invite-timeout", nowEpochMs)
        finish(current, "invite-timeout")
        return true
    }

    fun setMuted(muted: Boolean): Boolean {
        val current = session ?: return false
        if (current.state != "active") return false
        return mediaEngine.setMuted(current.callId, muted)
    }

    fun setSpeakerEnabled(enabled: Boolean): Boolean {
        val current = session ?: return false
        if (current.state != "active") return false
        return mediaEngine.setSpeakerEnabled(current.callId, enabled)
    }

    fun activeCallId(): String? = session?.callId
    fun state(): String = session?.state ?: "idle"
    fun selectedTransport(): String? = session?.selectedTransport

    private fun ingestInvite(signal: NativeCallSignal, nowEpochMs: Long): Boolean {
        val active = session
        if (active != null) {
            val busy = AndroidCallProtocol.createSignal(
                kind = "busy",
                callId = signal.callId,
                callerId = signal.callerId,
                calleeId = signal.calleeId,
                fromId = localDeviceId,
                sequence = 1,
                nowEpochMs = nowEpochMs,
                reason = "another-call-active"
            )
            signalPort.send(busy, nowEpochMs)
            return false
        }
        if (signal.calleeId != localDeviceId || signal.fromId != signal.callerId) return false
        val expiresAt = Instant.parse(signal.expiresAt ?: return false).toEpochMilli()
        if (expiresAt <= nowEpochMs) return false
        val common = selectCommonTransport(signal.callerId, signal.directTransports)
        if (common == null) {
            val decline = AndroidCallProtocol.createSignal(
                kind = "decline",
                callId = signal.callId,
                callerId = signal.callerId,
                calleeId = signal.calleeId,
                fromId = localDeviceId,
                sequence = 1,
                nowEpochMs = nowEpochMs,
                reason = "no-direct-realtime-path"
            )
            signalPort.send(decline, nowEpochMs)
            onEvent(NativeCallRuntimeEvent.Unavailable(signal.callerId, "no-direct-realtime-path"))
            return false
        }
        val created = Session(
            callId = signal.callId,
            callerId = signal.callerId,
            calleeId = signal.calleeId,
            peerId = signal.callerId,
            isCaller = false,
            offeredTransports = signal.directTransports,
            state = "incoming-ringing",
            localSequence = 0,
            lastRemoteSequence = signal.sequence,
            expiresAtEpochMs = expiresAt
        )
        session = created
        sendSignal(created, "ringing", nowEpochMs)
        onEvent(NativeCallRuntimeEvent.IncomingRinging(created.callId, created.peerId))
        return true
    }

    private fun handleAccept(current: Session, signal: NativeCallSignal, nowEpochMs: Long): Boolean {
        if (!current.isCaller || current.state !in setOf("outgoing-ringing", "outgoing")) return false
        val transport = signal.transport ?: return false
        if (transport !in current.offeredTransports || transport !in directCapabilities(current.peerId)) return false
        val prepared = mediaEngine.prepare(
            callId = current.callId,
            peerId = current.peerId,
            transport = transport,
            isCaller = true
        )
        current.selectedTransport = transport
        current.mediaPrepared = true
        current.state = "negotiating"
        if (!sendSignal(
                current,
                "candidate",
                nowEpochMs,
                transport = transport,
                endpointToken = prepared.endpointToken
            )
        ) {
            mediaEngine.stop(current.callId)
            current.mediaPrepared = false
            return false
        }
        onEvent(NativeCallRuntimeEvent.Negotiating(current.callId, current.peerId, transport))
        return true
    }

    private fun handleCandidate(current: Session, signal: NativeCallSignal, nowEpochMs: Long): Boolean {
        val transport = signal.transport ?: return false
        if (transport !in directCapabilities(current.peerId)) return false
        if (current.selectedTransport != null && current.selectedTransport != transport) return false
        if (!current.mediaPrepared) {
            val prepared = mediaEngine.prepare(
                callId = current.callId,
                peerId = current.peerId,
                transport = transport,
                isCaller = current.isCaller
            )
            current.mediaPrepared = true
            current.selectedTransport = transport
            current.state = "negotiating"
            if (!sendSignal(
                    current,
                    "candidate",
                    nowEpochMs,
                    transport = transport,
                    endpointToken = prepared.endpointToken
                )
            ) return false
        }
        if (!mediaEngine.applyRemoteCandidate(current.callId, signal.endpointToken ?: return false)) return false
        if (!mediaEngine.connect(current.callId)) return false
        current.state = "active"
        current.selectedTransport = transport
        sendSignal(current, "connected", nowEpochMs, transport = transport)
        onEvent(NativeCallRuntimeEvent.Active(current.callId, current.peerId, transport))
        return true
    }

    private fun handleConnected(current: Session, signal: NativeCallSignal): Boolean {
        if (!current.mediaPrepared || current.state !in setOf("negotiating", "active")) return false
        val transport = signal.transport ?: return false
        if (current.selectedTransport != transport) return false
        current.state = "active"
        onEvent(NativeCallRuntimeEvent.Active(current.callId, current.peerId, transport))
        return true
    }

    private fun sendTerminal(current: Session, kind: String, reason: String, nowEpochMs: Long) {
        runCatching { sendSignal(current, kind, nowEpochMs, reason = reason) }
    }

    private fun sendSignal(
        current: Session,
        kind: String,
        nowEpochMs: Long,
        transport: String? = null,
        endpointToken: String? = null,
        reason: String? = null
    ): Boolean {
        current.localSequence += 1
        val signal = AndroidCallProtocol.createSignal(
            kind = kind,
            callId = current.callId,
            callerId = current.callerId,
            calleeId = current.calleeId,
            fromId = localDeviceId,
            sequence = current.localSequence,
            nowEpochMs = nowEpochMs,
            transport = transport,
            endpointToken = endpointToken,
            reason = reason
        )
        return signalPort.send(signal, nowEpochMs)
    }

    private fun finish(current: Session, reason: String) {
        if (current.mediaPrepared) mediaEngine.stop(current.callId)
        onEvent(NativeCallRuntimeEvent.Ended(current.callId, current.peerId, reason))
        session = null
    }

    private fun directCapabilities(peerId: String): List<String> {
        val available = capabilityProvider.availableTransports(peerId).toSet()
        return REALTIME_DIRECT_TRANSPORTS.filter(available::contains)
    }

    private fun selectCommonTransport(peerId: String, remote: List<String>): String? {
        val local = directCapabilities(peerId).toSet()
        val remoteSet = remote.toSet()
        return REALTIME_DIRECT_TRANSPORTS.firstOrNull { it in local && it in remoteSet }
    }
}
