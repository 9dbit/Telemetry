package com.telemetry.app.mesh

class AndroidMeshRouteControlPlane(
    private val node: AndroidMeshNodeRuntime,
    private val codec: RouteAdvertisementCodec
) {
    fun createAdvertisementWire(
        toPeerId: String? = null,
        nowEpochMs: Long,
        ttlMs: Long = 15_000L
    ): ByteArray {
        val draft = node.createRouteAdvertisementDraft(toPeerId, nowEpochMs, ttlMs)
        return codec.encodeAndSign(draft)
    }

    fun ingestAdvertisementWire(
        bytes: ByteArray,
        fromPeerId: String,
        signingPublicKey: ByteArray,
        linkQuality: Int,
        linkTransport: String,
        nowEpochMs: Long
    ): Int {
        val verified = codec.verifyAndDecode(bytes, signingPublicKey, nowEpochMs) ?: return 0
        if (verified.advertiserId != fromPeerId) return 0
        return node.ingestVerifiedRouteAdvertisement(
            advertisement = verified,
            fromPeerId = fromPeerId,
            linkQuality = linkQuality,
            linkTransport = linkTransport,
            nowEpochMs = nowEpochMs
        )
    }
}
