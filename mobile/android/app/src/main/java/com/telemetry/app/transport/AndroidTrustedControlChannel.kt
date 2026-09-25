package com.telemetry.app.transport

import com.telemetry.app.crypto.AndroidPersistentPeerKey
import com.telemetry.app.crypto.AndroidTrustStore
import com.telemetry.app.crypto.DeviceIdentity
import java.util.UUID

class AndroidTrustedControlChannel(
    private val identity: DeviceIdentity,
    private val trustStore: AndroidTrustStore,
    private val codec: AndroidControlEnvelopeCodec = AndroidControlEnvelopeCodec(identity)
) {
    fun create(
        peerDeviceId: String,
        contentType: String,
        plaintext: ByteArray,
        conversationId: String = UUID.randomUUID().toString(),
        messageId: String = UUID.randomUUID().toString(),
        hopLimit: Int = 8
    ): ByteArray {
        val peer = trustStore.get(peerDeviceId) ?: error("peer is not trusted")
        val key = AndroidPersistentPeerKey.derive(identity, peer.exchangePublicKey)
        return codec.createAndEncode(
            sessionKey = key,
            recipientId = peer.deviceId,
            contentType = contentType,
            plaintext = plaintext,
            conversationId = conversationId,
            messageId = messageId,
            hopLimit = hopLimit
        )
    }

    fun open(bytes: ByteArray): NativeOpenedControlEnvelope {
        val envelope = codec.decode(bytes)
        val peer = trustStore.get(envelope.senderId) ?: error("control sender is not trusted")
        val key = AndroidPersistentPeerKey.derive(identity, peer.exchangePublicKey)
        return codec.verifyAndOpen(
            bytes = bytes,
            sessionKey = key,
            signingPublicKey = peer.signingPublicKey,
            expectedRecipientId = identity.deviceId
        )
    }

    fun openOwnForPeer(bytes: ByteArray, peerDeviceId: String): NativeOpenedControlEnvelope {
        val envelope = codec.decode(bytes)
        require(envelope.senderId == identity.deviceId) { "outgoing control sender mismatch" }
        require(envelope.recipientId == peerDeviceId) { "outgoing control recipient mismatch" }
        val peer = trustStore.get(peerDeviceId) ?: error("outgoing control peer is not trusted")
        val key = AndroidPersistentPeerKey.derive(identity, peer.exchangePublicKey)
        return codec.verifyAndOpen(
            bytes = bytes,
            sessionKey = key,
            signingPublicKey = identity.signingPublicKey,
            expectedRecipientId = peerDeviceId
        )
    }

    fun decode(bytes: ByteArray): NativeSignedControlEnvelope = codec.decode(bytes)
}
