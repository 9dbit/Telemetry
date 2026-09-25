package com.telemetry.app.mesh

private const val MAX_ROUTE_TTL_MS = 30_000L
private const val MAX_ADVERTISED_ROUTES = 32
private const val MAX_CAPABILITIES = 16

data class MobileRouteEntry(
    val destinationId: String,
    val viaPeerId: String,
    val hops: Int,
    val quality: Int,
    val transport: String,
    val expiresAtEpochMs: Long
)

data class AdvertisedRoute(
    val destinationId: String,
    val hops: Int,
    val quality: Int,
    val transport: String
)

data class RouteAdvertisementDraft(
    val protocol: String = "telemetry/mesh-route/0.1",
    val advertiserId: String,
    val sequence: Long,
    val createdAtEpochMs: Long,
    val expiresAtEpochMs: Long,
    val capabilities: List<String>,
    val routes: List<AdvertisedRoute>
)

data class VerifiedRouteAdvertisement(
    val protocol: String,
    val advertiserId: String,
    val sequence: Long,
    val createdAtEpochMs: Long,
    val expiresAtEpochMs: Long,
    val capabilities: List<String>,
    val routes: List<AdvertisedRoute>
)

interface RouteAdvertisementCodec {
    fun encodeAndSign(draft: RouteAdvertisementDraft): ByteArray
    fun verifyAndDecode(
        bytes: ByteArray,
        signingPublicKey: ByteArray,
        nowEpochMs: Long
    ): VerifiedRouteAdvertisement?
}

class RouteAdvertisementRuntime(
    private val localDeviceId: String,
    private val capabilities: List<String>
) {
    private val routes = LinkedHashMap<String, MobileRouteEntry>()
    private val lastSequence = LinkedHashMap<String, Long>()
    private var sequence = 0L

    init {
        require(localDeviceId.isNotBlank()) { "localDeviceId is required" }
        require(capabilities.size <= MAX_CAPABILITIES) { "too many capabilities" }
    }

    fun observeDirectPeer(
        peerId: String,
        quality: Int,
        transport: String,
        nowEpochMs: Long,
        ttlMs: Long = 15_000L
    ) {
        require(peerId.isNotBlank()) { "peerId is required" }
        require(transport.isNotBlank()) { "transport is required" }
        putRoute(
            MobileRouteEntry(
                destinationId = peerId,
                viaPeerId = peerId,
                hops = 1,
                quality = quality.coerceIn(-100, 100),
                transport = transport,
                expiresAtEpochMs = nowEpochMs + ttlMs.coerceIn(1L, MAX_ROUTE_TTL_MS)
            )
        )
    }

    fun removeViaPeer(peerId: String): Int {
        val keys = routes.filterValues { it.viaPeerId == peerId }.keys.toList()
        keys.forEach(routes::remove)
        return keys.size
    }

    fun createDraft(
        toPeerId: String? = null,
        nowEpochMs: Long,
        ttlMs: Long = 15_000L
    ): RouteAdvertisementDraft {
        prune(nowEpochMs)
        sequence += 1
        val boundedTtl = ttlMs.coerceIn(1_000L, MAX_ROUTE_TTL_MS)
        val learned = routes.values
            .filter { it.destinationId != localDeviceId }
            .filter { toPeerId == null || it.viaPeerId != toPeerId }
            .sortedWith(compareByDescending<MobileRouteEntry> { routeScore(it) }.thenBy { it.hops })
            .take(MAX_ADVERTISED_ROUTES - 1)
            .map {
                AdvertisedRoute(
                    destinationId = it.destinationId,
                    hops = it.hops,
                    quality = it.quality,
                    transport = it.transport
                )
            }

        return RouteAdvertisementDraft(
            advertiserId = localDeviceId,
            sequence = sequence,
            createdAtEpochMs = nowEpochMs,
            expiresAtEpochMs = nowEpochMs + boundedTtl,
            capabilities = capabilities.distinct().sorted().take(MAX_CAPABILITIES),
            routes = listOf(
                AdvertisedRoute(localDeviceId, 0, 100, "self")
            ) + learned
        )
    }

    fun ingestVerified(
        advertisement: VerifiedRouteAdvertisement,
        fromPeerId: String,
        linkQuality: Int,
        linkTransport: String,
        nowEpochMs: Long
    ): Int {
        require(fromPeerId.isNotBlank()) { "fromPeerId is required" }
        if (advertisement.protocol != "telemetry/mesh-route/0.1") return 0
        if (advertisement.advertiserId != fromPeerId) return 0
        if (advertisement.expiresAtEpochMs <= nowEpochMs) return 0
        if (advertisement.expiresAtEpochMs - advertisement.createdAtEpochMs > MAX_ROUTE_TTL_MS) return 0
        if (advertisement.routes.size > MAX_ADVERTISED_ROUTES) return 0

        val previous = lastSequence[fromPeerId]
        if (previous != null && advertisement.sequence <= previous) return 0
        lastSequence[fromPeerId] = advertisement.sequence

        val remainingTtl = (advertisement.expiresAtEpochMs - nowEpochMs).coerceAtMost(MAX_ROUTE_TTL_MS)
        var accepted = 0
        for (route in advertisement.routes) {
            if (route.destinationId == localDeviceId) continue
            if (route.hops !in 0..31) continue
            val hops = route.hops + 1
            val quality = minOf(route.quality.coerceIn(-100, 100), linkQuality.coerceIn(-100, 100))
            putRoute(
                MobileRouteEntry(
                    destinationId = route.destinationId,
                    viaPeerId = fromPeerId,
                    hops = hops,
                    quality = quality,
                    transport = if (route.transport == "self") linkTransport else route.transport,
                    expiresAtEpochMs = nowEpochMs + remainingTtl
                )
            )
            accepted += 1
        }
        return accepted
    }

    fun bestRoute(destinationId: String, nowEpochMs: Long): MobileRouteEntry? {
        prune(nowEpochMs)
        return routes.values
            .filter { it.destinationId == destinationId }
            .sortedWith(compareByDescending<MobileRouteEntry> { routeScore(it) }.thenBy { it.hops })
            .firstOrNull()
    }

    fun snapshot(nowEpochMs: Long): List<MobileRouteEntry> {
        prune(nowEpochMs)
        return routes.values.toList()
    }

    private fun putRoute(route: MobileRouteEntry) {
        val key = "${route.destinationId}|${route.viaPeerId}|${route.transport}"
        routes[key] = route
    }

    private fun prune(nowEpochMs: Long) {
        routes.entries.removeIf { it.value.expiresAtEpochMs <= nowEpochMs }
    }

    private fun routeScore(route: MobileRouteEntry): Int = 100 - route.hops * 15 + route.quality
}
