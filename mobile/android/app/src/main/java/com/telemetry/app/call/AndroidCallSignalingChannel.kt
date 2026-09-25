package com.telemetry.app.call

import com.telemetry.app.crypto.DeviceIdentity
import com.telemetry.app.mesh.AndroidMeshNodeRuntime
import com.telemetry.app.mesh.MeshSendResult
import com.telemetry.app.transport.AndroidTrustedControlChannel

class AndroidCallSignalingChannel(
    private val identity: DeviceIdentity,
    private val controlChannel: AndroidTrustedControlChannel,
    private val node: AndroidMeshNodeRuntime
) : CallSignalPort {
    companion object {
        private const val SIGNAL_TTL_MS = 2 * 60_000L
        private const val SIGNAL_HOP_LIMIT = 8
    }

    override fun send(signal: NativeCallSignal, nowEpochMs: Long): Boolean =
        sendWithResult(signal, nowEpochMs).sent

    fun sendWithResult(signal: NativeCallSignal, nowEpochMs: Long = System.currentTimeMillis()): MeshSendResult {
        AndroidCallProtocol.validate(signal)
        require(signal.fromId == identity.deviceId) { "call signaling sender identity mismatch" }
        val wire = controlChannel.create(
            peerDeviceId = signal.toId,
            contentType = CALL_SIGNAL_CONTENT_TYPE,
            plaintext = AndroidCallProtocol.encode(signal),
            conversationId = "call:${signal.callId}",
            messageId = messageId(signal),
            hopLimit = SIGNAL_HOP_LIMIT
        )
        return node.sendOriginEnvelope(
            recipientId = signal.toId,
            messageId = messageId(signal),
            encodedEnvelope = wire,
            nowEpochMs = nowEpochMs,
            ttlMs = SIGNAL_TTL_MS,
            hopLimit = SIGNAL_HOP_LIMIT
        )
    }

    fun openLocal(bytes: ByteArray): NativeCallSignal {
        val opened = controlChannel.open(bytes)
        require(opened.contentType == CALL_SIGNAL_CONTENT_TYPE) { "unsupported call signaling content type" }
        val signal = AndroidCallProtocol.decode(opened.plaintext)
        require(signal.fromId == opened.senderId) { "call signaling sender binding mismatch" }
        require(signal.toId == opened.recipientId) { "call signaling recipient binding mismatch" }
        require(signal.toId == identity.deviceId) { "call signaling not addressed to local identity" }
        require(opened.conversationId == "call:${signal.callId}") { "call signaling conversation binding mismatch" }
        return signal
    }

    private fun messageId(signal: NativeCallSignal): String =
        "call:${signal.callId}:${signal.fromId}:${signal.sequence}"
}
