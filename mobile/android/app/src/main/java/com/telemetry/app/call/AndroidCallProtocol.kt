package com.telemetry.app.call

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.UUID

const val CALL_SIGNAL_VERSION = "telemetry/call-signal/0.1"
const val CALL_SIGNAL_CONTENT_TYPE = "application/telemetry+call-signal"
const val CALL_MEDIA_AUDIO = "audio"
const val CALL_DEFAULT_TIMEOUT_MS = 45_000L
const val CALL_MAX_SDP_CHARS = 48 * 1024
const val CALL_MAX_ICE_CHARS = 8 * 1024

val REALTIME_DIRECT_TRANSPORTS: List<String> = listOf("wifi-direct", "wifi-aware", "wifi-local")

private val SIGNAL_KINDS = setOf(
    "invite", "ringing", "accept", "offer", "answer", "ice-candidate", "connected",
    "decline", "busy", "cancel", "end"
)

data class NativeCallSignal(
    val version: String = CALL_SIGNAL_VERSION,
    val kind: String,
    val callId: String,
    val callerId: String,
    val calleeId: String,
    val fromId: String,
    val toId: String,
    val sequence: Long,
    val media: String = CALL_MEDIA_AUDIO,
    val createdAt: String,
    val expiresAt: String? = null,
    val directTransports: List<String> = emptyList(),
    val transport: String? = null,
    val sessionDescription: String? = null,
    val iceCandidate: String? = null,
    val sdpMid: String? = null,
    val sdpMLineIndex: Int? = null,
    val reason: String? = null
)

object AndroidCallProtocol {
    fun selectRealtimeTransport(capabilities: List<String>): String? {
        val set = capabilities.toSet()
        return REALTIME_DIRECT_TRANSPORTS.firstOrNull(set::contains)
    }

    fun createSignal(
        kind: String,
        callId: String = UUID.randomUUID().toString(),
        callerId: String,
        calleeId: String,
        fromId: String,
        sequence: Long,
        nowEpochMs: Long = System.currentTimeMillis(),
        timeoutMs: Long = CALL_DEFAULT_TIMEOUT_MS,
        directTransports: List<String> = emptyList(),
        transport: String? = null,
        sessionDescription: String? = null,
        iceCandidate: String? = null,
        sdpMid: String? = null,
        sdpMLineIndex: Int? = null,
        reason: String? = null
    ): NativeCallSignal {
        val createdAt = Instant.ofEpochMilli(nowEpochMs).toString()
        val toId = if (fromId == callerId) calleeId else callerId
        val signal = NativeCallSignal(
            kind = kind,
            callId = callId,
            callerId = callerId,
            calleeId = calleeId,
            fromId = fromId,
            toId = toId,
            sequence = sequence,
            createdAt = createdAt,
            expiresAt = if (kind == "invite") Instant.ofEpochMilli(nowEpochMs + timeoutMs).toString() else null,
            directTransports = if (kind == "invite") directTransports.distinct() else emptyList(),
            transport = transport,
            sessionDescription = sessionDescription,
            iceCandidate = iceCandidate,
            sdpMid = sdpMid,
            sdpMLineIndex = sdpMLineIndex,
            reason = reason
        )
        return validate(signal)
    }

    fun validate(signal: NativeCallSignal): NativeCallSignal {
        require(signal.version == CALL_SIGNAL_VERSION) { "unsupported call signal version" }
        require(signal.kind in SIGNAL_KINDS) { "unsupported call signal kind" }
        require(signal.callId.length in 8..128) { "invalid callId" }
        validateDeviceId(signal.callerId, "callerId")
        validateDeviceId(signal.calleeId, "calleeId")
        validateDeviceId(signal.fromId, "fromId")
        validateDeviceId(signal.toId, "toId")
        require(signal.callerId != signal.calleeId) { "call participants must differ" }
        require(signal.fromId in setOf(signal.callerId, signal.calleeId)) { "call participant mismatch" }
        require(signal.toId in setOf(signal.callerId, signal.calleeId)) { "call participant mismatch" }
        require(signal.fromId != signal.toId) { "self call signal rejected" }
        require(
            (signal.fromId == signal.callerId && signal.toId == signal.calleeId) ||
                (signal.fromId == signal.calleeId && signal.toId == signal.callerId)
        ) { "wrong call signal recipient" }
        require(signal.sequence >= 0) { "invalid call signal sequence" }
        parseInstant(signal.createdAt)
        require(signal.media == CALL_MEDIA_AUDIO) { "unsupported call media kind" }

        if (signal.kind == "invite") {
            require(signal.fromId == signal.callerId) { "only caller may invite" }
            require(signal.directTransports.isNotEmpty()) { "call invite requires direct transport capabilities" }
            signal.directTransports.forEach(::validateDirectTransport)
            val created = parseInstant(signal.createdAt)
            val expires = parseInstant(signal.expiresAt ?: error("call invite expiry required"))
            val ttl = expires - created
            require(ttl in 1..120_000L) { "call invite expiry invalid" }
        }
        if (signal.kind in setOf("accept", "offer", "answer", "ice-candidate", "connected")) {
            validateDirectTransport(signal.transport ?: error("call direct transport required"))
        }
        if (signal.kind in setOf("offer", "answer")) {
            require(signal.sessionDescription?.length in 8..CALL_MAX_SDP_CHARS) { "invalid WebRTC session description" }
        }
        if (signal.kind == "ice-candidate") {
            require(signal.iceCandidate?.length in 1..CALL_MAX_ICE_CHARS) { "invalid ICE candidate" }
            require(signal.sdpMid == null || signal.sdpMid.length <= 128) { "invalid ICE sdpMid" }
            require(signal.sdpMLineIndex != null && signal.sdpMLineIndex in 0..255) { "invalid ICE sdpMLineIndex" }
        }
        require(signal.reason == null || signal.reason.length <= 160) { "invalid call reason" }
        return signal
    }

    fun encode(signal: NativeCallSignal): ByteArray {
        validate(signal)
        val json = JSONObject()
            .put("version", signal.version)
            .put("kind", signal.kind)
            .put("callId", signal.callId)
            .put("callerId", signal.callerId)
            .put("calleeId", signal.calleeId)
            .put("fromId", signal.fromId)
            .put("toId", signal.toId)
            .put("sequence", signal.sequence)
            .put("media", signal.media)
            .put("createdAt", signal.createdAt)
        signal.expiresAt?.let { json.put("expiresAt", it) }
        if (signal.directTransports.isNotEmpty()) json.put("directTransports", JSONArray(signal.directTransports))
        signal.transport?.let { json.put("transport", it) }
        signal.sessionDescription?.let { json.put("sessionDescription", it) }
        signal.iceCandidate?.let { json.put("iceCandidate", it) }
        signal.sdpMid?.let { json.put("sdpMid", it) }
        signal.sdpMLineIndex?.let { json.put("sdpMLineIndex", it) }
        signal.reason?.let { json.put("reason", it) }
        return json.toString().toByteArray(Charsets.UTF_8)
    }

    fun decode(bytes: ByteArray): NativeCallSignal {
        val json = JSONObject(String(bytes, Charsets.UTF_8))
        val transports = if (json.has("directTransports")) {
            val array = json.getJSONArray("directTransports")
            List(array.length()) { array.getString(it) }
        } else emptyList()
        return validate(
            NativeCallSignal(
                version = json.getString("version"),
                kind = json.getString("kind"),
                callId = json.getString("callId"),
                callerId = json.getString("callerId"),
                calleeId = json.getString("calleeId"),
                fromId = json.getString("fromId"),
                toId = json.getString("toId"),
                sequence = json.getLong("sequence"),
                media = json.getString("media"),
                createdAt = json.getString("createdAt"),
                expiresAt = json.optString("expiresAt").takeIf(String::isNotEmpty),
                directTransports = transports,
                transport = json.optString("transport").takeIf(String::isNotEmpty),
                sessionDescription = json.optString("sessionDescription").takeIf(String::isNotEmpty),
                iceCandidate = json.optString("iceCandidate").takeIf(String::isNotEmpty),
                sdpMid = json.optString("sdpMid").takeIf(String::isNotEmpty),
                sdpMLineIndex = if (json.has("sdpMLineIndex")) json.getInt("sdpMLineIndex") else null,
                reason = json.optString("reason").takeIf(String::isNotEmpty)
            )
        )
    }

    private fun validateDeviceId(value: String, label: String) {
        require(value.length in 8..160) { "invalid $label" }
    }

    private fun validateDirectTransport(value: String) {
        require(value in REALTIME_DIRECT_TRANSPORTS) { "live call transport must be a direct Wi-Fi-class path" }
    }

    private fun parseInstant(value: String): Long = Instant.parse(value).toEpochMilli()
}
