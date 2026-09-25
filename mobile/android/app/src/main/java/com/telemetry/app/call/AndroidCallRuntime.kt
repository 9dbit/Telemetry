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
        var mediaPrepared: Boolean = false,
        var remoteConnected: Boolean = false
    )

    private var session: Session? = null

    init {
        mediaEngine.setListener(::handleMediaEvent)
    }

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
            if (signal.kind == "invite") return@runCatching ingestInvite(signal, nowEpochMs)
            if (current == null) return@runCatching false
            if (signal.callId != current.callId || signal.fromId != current.peerId) return@runCatching false
            if (signal.sequence <= current.lastRemoteSequence) return@runCatching false
            if (kotlin.math.abs(nowEpochMs - Instant.parse(signal.createdAt).toEpochMilli()) > 5 * 60_000L) {
                return@runCatching false
            }

            val accepted = when (signal.kind) {
                "ringing" -> handleRinging(current)
                "accept" -> handleAccept(current, signal)
                "offer" -> handleOffer(current, signal)
                "answer" -> handleAnswer(current, signal)
                "ice-candidate" -> handleIceCandidate(current, signal)
                "connected" -> handleRemoteConnected(current, signal)
                "decline", "busy", "cancel", "end" -> {
                    finish(current, signal.reason ?: signal.kind)
                    true
                }
                else -> false
            }
            if (accepted && session === current) current.lastRemoteSequence = signal.sequence
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
            require(mediaEngine.prepare(current.callId, current.peerId, selected, isCaller = false)) {
                "call media prepare failed"
            }
            current.selectedTransport = selected
            current.mediaPrepared = true
            current.state = "negotiating"
            require(sendSignal(current, "accept", nowEpochMs, transport = selected)) {
                "call accept signaling failed"
            }
            onEvent(NativeCallRuntimeEvent.Negotiating(current.callId, current.peerId, selected))
            true
        }.getOrElse {
            finish(current, "call-accept-failed")
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

    private fun handleRinging(current: Session): Boolean {
        if (!current.isCaller || current.state !in setOf("outgoing-ringing", "outgoing")) return false
        current.state = "outgoing-ringing"
        onEvent(NativeCallRuntimeEvent.OutgoingRinging(current.callId, current.peerId))
        return true
    }

    private fun handleAccept(current: Session, signal: NativeCallSignal): Boolean {
        if (!current.isCaller || current.state !in setOf("outgoing-ringing", "outgoing")) return false
        val transport = signal.transport ?: return false
        if (transport !in current.offeredTransports || transport !in directCapabilities(current.peerId)) return false
        if (!mediaEngine.prepare(current.callId, current.peerId, transport, isCaller = true)) return false
        current.selectedTransport = transport
        current.mediaPrepared = true
        current.state = "negotiating"
        if (!mediaEngine.createOffer(current.callId)) {
            finish(current, "offer-create-failed")
            return false
        }
        onEvent(NativeCallRuntimeEvent.Negotiating(current.callId, current.peerId, transport))
        return true
    }

    private fun handleOffer(current: Session, signal: NativeCallSignal): Boolean {
        if (current.isCaller || current.state != "negotiating" || !current.mediaPrepared) return false
        val transport = signal.transport ?: return false
        if (current.selectedTransport != transport) return false
        return mediaEngine.applyRemoteOffer(current.callId, signal.sessionDescription ?: return false)
    }

    private fun handleAnswer(current: Session, signal: NativeCallSignal): Boolean {
        if (!current.isCaller || current.state != "negotiating" || !current.mediaPrepared) return false
        val transport = signal.transport ?: return false
        if (current.selectedTransport != transport) return false
        return mediaEngine.applyRemoteAnswer(current.callId, signal.sessionDescription ?: return false)
    }

    private fun handleIceCandidate(current: Session, signal: NativeCallSignal): Boolean {
        if (current.state !in setOf("negotiating", "active") || !current.mediaPrepared) return false
        val transport = signal.transport ?: return false
        if (current.selectedTransport != transport) return false
        return mediaEngine.addRemoteIceCandidate(
            current.callId,
            NativeIceCandidate(
                candidate = signal.iceCandidate ?: return false,
                sdpMid = signal.sdpMid,
                sdpMLineIndex = signal.sdpMLineIndex ?: return false
            )
        )
    }

    private fun handleRemoteConnected(current: Session, signal: NativeCallSignal): Boolean {
        val transport = signal.transport ?: return false
        if (current.selectedTransport != transport || !current.mediaPrepared) return false
        current.remoteConnected = true
        return current.state in setOf("negotiating", "active")
    }

    private fun handleMediaEvent(event: RealtimeCallMediaEvent) {
        val current = session ?: return
        if (event.callId != current.callId || !current.mediaPrepared) return
        val transport = current.selectedTransport ?: return
        val now = System.currentTimeMillis()
        when (event) {
            is RealtimeCallMediaEvent.LocalOffer -> {
                if (!current.isCaller || current.state != "negotiating") return
                if (!sendSignal(current, "offer", now, transport = transport, sessionDescription = event.sessionDescription)) {
                    finish(current, "offer-signaling-failed")
                }
            }
            is RealtimeCallMediaEvent.LocalAnswer -> {
                if (current.isCaller || current.state != "negotiating") return
                if (!sendSignal(current, "answer", now, transport = transport, sessionDescription = event.sessionDescription)) {
                    finish(current, "answer-signaling-failed")
                }
            }
            is RealtimeCallMediaEvent.LocalIceCandidate -> {
                if (current.state !in setOf("negotiating", "active")) return
                val candidate = event.candidate
                if (!sendSignal(
                        current,
                        "ice-candidate",
                        now,
                        transport = transport,
                        iceCandidate = candidate.candidate,
                        sdpMid = candidate.sdpMid,
                        sdpMLineIndex = candidate.sdpMLineIndex
                    )
                ) {
                    onEvent(NativeCallRuntimeEvent.Error(current.callId, "ice-candidate-signaling-failed"))
                }
            }
            is RealtimeCallMediaEvent.Connected -> {
                if (current.state == "active") return
                current.state = "active"
                sendSignal(current, "connected", now, transport = transport)
                onEvent(NativeCallRuntimeEvent.Active(current.callId, current.peerId, transport))
            }
            is RealtimeCallMediaEvent.Disconnected -> {
                sendTerminal(current, "end", event.reason, now)
                finish(current, event.reason)
            }
            is RealtimeCallMediaEvent.Failed -> {
                sendTerminal(current, "end", event.reason, now)
                finish(current, event.reason)
            }
        }
    }

    private fun sendTerminal(current: Session, kind: String, reason: String, nowEpochMs: Long) {
        runCatching { sendSignal(current, kind, nowEpochMs, reason = reason) }
    }

    private fun sendSignal(
        current: Session,
        kind: String,
        nowEpochMs: Long,
        transport: String? = null,
        sessionDescription: String? = null,
        iceCandidate: String? = null,
        sdpMid: String? = null,
        sdpMLineIndex: Int? = null,
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
            sessionDescription = sessionDescription,
            iceCandidate = iceCandidate,
            sdpMid = sdpMid,
            sdpMLineIndex = sdpMLineIndex,
            reason = reason
        )
        return signalPort.send(signal, nowEpochMs)
    }

    private fun finish(current: Session, reason: String) {
        if (session !== current) return
        val wasPrepared = current.mediaPrepared
        session = null
        if (wasPrepared) mediaEngine.stop(current.callId)
        onEvent(NativeCallRuntimeEvent.Ended(current.callId, current.peerId, reason))
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
