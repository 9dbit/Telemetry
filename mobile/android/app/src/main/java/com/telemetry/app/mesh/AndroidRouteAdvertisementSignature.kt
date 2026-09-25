package com.telemetry.app.mesh

import com.telemetry.app.crypto.DeviceIdentity
import com.telemetry.app.crypto.TelemetryCrypto
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.format.DateTimeFormatterBuilder
import java.util.Base64

data class SignedMobileRouteAdvertisement(
    val protocol: String,
    val advertiserId: String,
    val sequence: Long,
    val createdAtEpochMs: Long,
    val expiresAtEpochMs: Long,
    val capabilities: List<String>,
    val routes: List<AdvertisedRoute>,
    val signature: ByteArray
)

object AndroidRouteAdvertisementSignature {
    private val instantFormatter = DateTimeFormatterBuilder().appendInstant(3).toFormatter()

    fun sign(identity: DeviceIdentity, draft: RouteAdvertisementDraft): SignedMobileRouteAdvertisement {
        require(draft.advertiserId == identity.deviceId) { "advertiser identity mismatch" }
        val normalized = normalize(draft)
        val canonical = canonicalJson(normalized).toByteArray(StandardCharsets.UTF_8)
        val signer = Ed25519Signer()
        signer.init(true, Ed25519PrivateKeyParameters(identity.ed25519Seed, 0))
        signer.update(canonical, 0, canonical.size)
        return SignedMobileRouteAdvertisement(
            protocol = normalized.protocol,
            advertiserId = normalized.advertiserId,
            sequence = normalized.sequence,
            createdAtEpochMs = normalized.createdAtEpochMs,
            expiresAtEpochMs = normalized.expiresAtEpochMs,
            capabilities = normalized.capabilities,
            routes = normalized.routes,
            signature = signer.generateSignature()
        )
    }

    fun verify(
        advertisement: SignedMobileRouteAdvertisement,
        signingPublicKey: ByteArray,
        nowEpochMs: Long
    ): VerifiedRouteAdvertisement? = runCatching {
        require(signingPublicKey.size == 32) { "invalid Ed25519 public key" }
        require(advertisement.signature.size == 64) { "invalid Ed25519 signature" }
        require(TelemetryCrypto.deviceId(signingPublicKey) == advertisement.advertiserId) { "identity mismatch" }
        require(advertisement.sequence >= 0) { "invalid sequence" }
        require(advertisement.expiresAtEpochMs > nowEpochMs) { "advertisement expired" }
        require(advertisement.expiresAtEpochMs - advertisement.createdAtEpochMs <= 30_000L) { "ttl too large" }
        require(advertisement.routes.size <= 32) { "too many routes" }

        val normalized = normalize(
            RouteAdvertisementDraft(
                protocol = advertisement.protocol,
                advertiserId = advertisement.advertiserId,
                sequence = advertisement.sequence,
                createdAtEpochMs = advertisement.createdAtEpochMs,
                expiresAtEpochMs = advertisement.expiresAtEpochMs,
                capabilities = advertisement.capabilities,
                routes = advertisement.routes
            )
        )
        val canonical = canonicalJson(normalized).toByteArray(StandardCharsets.UTF_8)
        val verifier = Ed25519Signer()
        verifier.init(false, Ed25519PublicKeyParameters(signingPublicKey, 0))
        verifier.update(canonical, 0, canonical.size)
        require(verifier.verifySignature(advertisement.signature)) { "bad signature" }

        VerifiedRouteAdvertisement(
            protocol = normalized.protocol,
            advertiserId = normalized.advertiserId,
            sequence = normalized.sequence,
            createdAtEpochMs = normalized.createdAtEpochMs,
            expiresAtEpochMs = normalized.expiresAtEpochMs,
            capabilities = normalized.capabilities,
            routes = normalized.routes
        )
    }.getOrNull()

    fun canonicalJson(draft: RouteAdvertisementDraft): String {
        val capabilities = draft.capabilities.joinToString(",") { jsonString(it) }
        val routes = draft.routes.joinToString(",") { route ->
            "{" +
                "\"destinationId\":" + jsonString(route.destinationId) + "," +
                "\"hops\":" + route.hops + "," +
                "\"quality\":" + route.quality + "," +
                "\"transport\":" + jsonString(route.transport) +
                "}"
        }
        return "{" +
            "\"protocol\":" + jsonString(draft.protocol) + "," +
            "\"advertiserId\":" + jsonString(draft.advertiserId) + "," +
            "\"sequence\":" + draft.sequence + "," +
            "\"createdAt\":" + jsonString(formatInstant(draft.createdAtEpochMs)) + "," +
            "\"expiresAt\":" + jsonString(formatInstant(draft.expiresAtEpochMs)) + "," +
            "\"capabilities\":[" + capabilities + "]," +
            "\"routes\":[" + routes + "]" +
            "}"
    }

    fun signatureBase64Url(advertisement: SignedMobileRouteAdvertisement): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(advertisement.signature)

    private fun normalize(draft: RouteAdvertisementDraft): RouteAdvertisementDraft {
        require(draft.protocol == "telemetry/mesh-route/0.1") { "unsupported protocol" }
        require(draft.sequence >= 0) { "sequence must be non-negative" }
        require(draft.expiresAtEpochMs > draft.createdAtEpochMs) { "expiry must follow creation" }
        require(draft.expiresAtEpochMs - draft.createdAtEpochMs <= 30_000L) { "ttl too large" }
        require(draft.routes.size <= 32) { "too many routes" }

        val capabilities = draft.capabilities
            .filter { it.isNotEmpty() && it.length <= 64 }
            .distinct()
            .sorted()
            .take(16)
        val routes = draft.routes.map { route ->
            require(route.destinationId.isNotBlank()) { "route destination is required" }
            require(route.hops in 0..31) { "route hops out of range" }
            AdvertisedRoute(
                destinationId = route.destinationId,
                hops = route.hops,
                quality = route.quality.coerceIn(-100, 100),
                transport = route.transport.ifBlank { "ble" }
            )
        }
        return draft.copy(capabilities = capabilities, routes = routes)
    }

    private fun formatInstant(epochMs: Long): String =
        instantFormatter.format(Instant.ofEpochMilli(epochMs))

    private fun jsonString(value: String): String {
        val out = StringBuilder(value.length + 2).append('"')
        for (ch in value) {
            when (ch) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> if (ch.code < 0x20) out.append(String.format("\\u%04x", ch.code)) else out.append(ch)
            }
        }
        return out.append('"').toString()
    }
}
