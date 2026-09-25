package com.telemetry.app.call

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.UUID

const val CALL_SIGNAL_VERSION = "telemetry/call-signal/0.1"
const val CALL_SIGNAL_CONTENT_TYPE = "application/telemetry+call-signal"
const val CALL_MEDIA_AUDIO = "audio"
const val CALL_DEFAULT_TIMEOUT_MS = 45_000L

val REALTIME_DIRECT_TRANSPORTS: List<String> = listOf("wifi-direct", "wifi-aware", "wifi-local")

private val SIGNAL_KINDS = setOf(
    "invite", "ringing", "accept", "candidate", "connected",
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
    val endpointToken: String? = null,
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
        endpointToken: String? = null,
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
            endpointToken = endpointToken,
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
        if (signal.kind in setOf("accept", "candidate", "connected")) {
            validateDirectTransport(signal.transport ?: error("call direct transport required"))
        }
        if (signal.kind == "candidate") {
            require(signal.endpointToken?.length in 8..512) { "invalid direct candidate token" }
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
        if (signal.directTransports.isNotEmpty()) {
            json.put("directTransports", JSONArray(signal.directTransports))
        }
        signal.transport?.let { json.put("transport", it) }
        signal.endpointToken?.let { json.put("endpointToken", it) }
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
                endpointToken = json.optString("endpointToken").takeIf(String::isNotEmpty),
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
