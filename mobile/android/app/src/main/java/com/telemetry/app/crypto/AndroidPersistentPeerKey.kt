package com.telemetry.app.crypto

import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters

object AndroidPersistentPeerKey {
    const val DEFAULT_CONTEXT = "telemetry/v0.1/session"

    fun derive(
        identity: DeviceIdentity,
        peerExchangePublicKey: ByteArray,
        context: String = DEFAULT_CONTEXT
    ): ByteArray {
        require(identity.x25519Seed.size == 32) { "local X25519 seed must be 32 bytes" }
        require(peerExchangePublicKey.size == 32) { "peer X25519 public key must be 32 bytes" }
        require(context.isNotBlank()) { "pairwise key context is required" }

        val privateKey = X25519PrivateKeyParameters(identity.x25519Seed, 0)
        val peerPublic = X25519PublicKeyParameters(peerExchangePublicKey, 0)
        val sharedSecret = ByteArray(32)
        privateKey.generateSecret(peerPublic, sharedSecret, 0)

        val hkdf = HKDFBytesGenerator(SHA256Digest())
        hkdf.init(
            HKDFParameters(
                sharedSecret,
                byteArrayOf(),
                context.toByteArray(Charsets.UTF_8)
            )
        )
        return ByteArray(32).also { hkdf.generateBytes(it, 0, it.size) }
    }
}
