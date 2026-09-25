package com.telemetry.app.crypto

import android.content.Context
import java.security.MessageDigest
import java.util.Base64

data class TrustedPeer(
    val deviceId: String,
    val signingPublicKey: ByteArray,
    val exchangePublicKey: ByteArray,
    val verifiedAt: Long
)

class AndroidTrustStore(context: Context) {
    private val prefs = context.getSharedPreferences("telemetry_trust_v1", Context.MODE_PRIVATE)
    private val b64e = Base64.getUrlEncoder().withoutPadding()
    private val b64d = Base64.getUrlDecoder()

    fun verify(hello: SessionHello, verifiedAt: Long = System.currentTimeMillis()) {
        val record = listOf(
            b64e.encodeToString(hello.signingPublicKey),
            b64e.encodeToString(hello.exchangePublicKey),
            verifiedAt.toString()
        ).joinToString("|")
        prefs.edit().putString(key(hello.deviceId), record).apply()
    }

    fun get(deviceId: String): TrustedPeer? {
        val raw = prefs.getString(key(deviceId), null) ?: return null
        return decodeRecord(deviceId, raw)
    }

    fun trustedPeers(): List<TrustedPeer> = prefs.all.entries
        .asSequence()
        .filter { it.key.startsWith("peer.") }
        .mapNotNull { entry ->
            val raw = entry.value as? String ?: return@mapNotNull null
            val deviceId = entry.key.removePrefix("peer.")
            decodeRecord(deviceId, raw)
        }
        .sortedByDescending { it.verifiedAt }
        .toList()

    fun isVerified(hello: SessionHello): Boolean {
        val trusted = get(hello.deviceId) ?: return false
        return MessageDigest.isEqual(trusted.signingPublicKey, hello.signingPublicKey) &&
            MessageDigest.isEqual(trusted.exchangePublicKey, hello.exchangePublicKey)
    }

    fun revoke(deviceId: String) {
        prefs.edit().remove(key(deviceId)).apply()
    }

    private fun decodeRecord(deviceId: String, raw: String): TrustedPeer? {
        val parts = raw.split('|')
        if (parts.size != 3) return null
        return runCatching {
            TrustedPeer(deviceId, b64d.decode(parts[0]), b64d.decode(parts[1]), parts[2].toLong())
        }.getOrNull()
    }

    private fun key(deviceId: String) = "peer.$deviceId"
}
