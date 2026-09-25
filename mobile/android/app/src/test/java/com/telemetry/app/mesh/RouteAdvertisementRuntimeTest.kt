package com.telemetry.app.mesh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteAdvertisementRuntimeTest {
    private fun verified(
        advertiserId: String,
        sequence: Long,
        createdAt: Long = 1_000L,
        expiresAt: Long = 16_000L,
        capabilities: List<String> = listOf("ble", "mesh-relay"),
        routes: List<AdvertisedRoute>
    ) = VerifiedRouteAdvertisement(
        protocol = "telemetry/mesh-route/0.1",
        advertiserId = advertiserId,
        sequence = sequence,
        createdAtEpochMs = createdAt,
        expiresAtEpochMs = expiresAt,
        capabilities = capabilities,
        routes = routes
    )

    @Test
    fun splitHorizonOmitsRouteLearnedFromTargetPeer() {
        val runtime = RouteAdvertisementRuntime("device-a", listOf("ble", "mesh-relay"))
        runtime.ingestVerified(
            verified(
                advertiserId = "device-b",
                sequence = 1,
                routes = listOf(
                    AdvertisedRoute("device-b", 0, 100, "self"),
                    AdvertisedRoute("device-c", 1, 70, "ble")
                )
            ),
            fromPeerId = "device-b",
            linkQuality = 60,
            linkTransport = "ble",
            nowEpochMs = 1_000L
        )

        val towardB = runtime.createDraft(toPeerId = "device-b", nowEpochMs = 2_000L)
        val towardD = runtime.createDraft(toPeerId = "device-d", nowEpochMs = 2_000L)

        assertTrue(towardB.routes.none { it.destinationId == "device-c" })
        assertTrue(towardD.routes.any { it.destinationId == "device-c" })
    }

    @Test
    fun staleSequenceIsRejectedWithoutReplacingNewerRoute() {
        val runtime = RouteAdvertisementRuntime("device-a", listOf("ble"))
        val newer = verified(
            advertiserId = "device-b",
            sequence = 8,
            routes = listOf(AdvertisedRoute("device-c", 1, 80, "ble"))
        )
        val stale = verified(
            advertiserId = "device-b",
            sequence = 7,
            routes = listOf(AdvertisedRoute("device-c", 1, -80, "ble"))
        )

        assertEquals(1, runtime.ingestVerified(newer, "device-b", 70, "ble", 1_000L))
        assertEquals(0, runtime.ingestVerified(stale, "device-b", 70, "ble", 1_100L))
        assertEquals(70, runtime.bestRoute("device-c", 1_100L)?.quality)
    }

    @Test
    fun expiredRoutesDisappearAndDirectPeerRemovalInvalidatesDependents() {
        val runtime = RouteAdvertisementRuntime("device-a", listOf("ble"))
        runtime.ingestVerified(
            verified(
                advertiserId = "device-b",
                sequence = 1,
                createdAt = 1_000L,
                expiresAt = 2_000L,
                routes = listOf(AdvertisedRoute("device-c", 1, 50, "ble"))
            ),
            "device-b",
            40,
            "ble",
            1_000L
        )

        assertEquals("device-b", runtime.bestRoute("device-c", 1_500L)?.viaPeerId)
        assertNull(runtime.bestRoute("device-c", 2_000L))

        runtime.observeDirectPeer("device-b", 80, "ble", 3_000L)
        assertEquals(1, runtime.removeViaPeer("device-b"))
        assertNull(runtime.bestRoute("device-b", 3_000L))
    }

    @Test
    fun strongerRouteWinsAndSelfRouteUsesIngressTransport() {
        val runtime = RouteAdvertisementRuntime("device-a", listOf("ble", "wifi-direct"))
        runtime.ingestVerified(
            verified(
                advertiserId = "device-b",
                sequence = 1,
                routes = listOf(
                    AdvertisedRoute("device-b", 0, 100, "self"),
                    AdvertisedRoute("device-c", 1, 40, "ble")
                )
            ),
            "device-b",
            90,
            "wifi-direct",
            1_000L
        )
        runtime.ingestVerified(
            verified(
                advertiserId = "device-d",
                sequence = 1,
                routes = listOf(AdvertisedRoute("device-c", 1, 20, "ble"))
            ),
            "device-d",
            20,
            "ble",
            1_000L
        )

        assertEquals("device-b", runtime.bestRoute("device-c", 1_500L)?.viaPeerId)
        assertEquals("wifi-direct", runtime.bestRoute("device-b", 1_500L)?.transport)
    }

    @Test
    fun verifiedPeerCapabilitiesAreRetainedUntilAdvertisementExpiry() {
        val runtime = RouteAdvertisementRuntime("device-a", listOf("ble"))
        val wifiCapability = "wifi-local:abc123:def456"

        assertEquals(
            1,
            runtime.ingestVerified(
                verified(
                    advertiserId = "device-b",
                    sequence = 1,
                    expiresAt = 2_000L,
                    capabilities = listOf("ble", wifiCapability),
                    routes = listOf(AdvertisedRoute("device-b", 0, 100, "self"))
                ),
                "device-b",
                60,
                "ble",
                1_000L
            )
        )
        assertEquals(listOf("ble", wifiCapability).sorted(), runtime.capabilitiesForPeer("device-b", 1_500L))
        assertTrue(runtime.capabilitiesForPeer("device-b", 2_000L).isEmpty())
    }

    @Test
    fun overlongCapabilityRejectsWholeAdvertisement() {
        val runtime = RouteAdvertisementRuntime("device-a", listOf("ble"))
        val tooLong = "x".repeat(65)
        assertEquals(
            0,
            runtime.ingestVerified(
                verified(
                    advertiserId = "device-b",
                    sequence = 1,
                    capabilities = listOf(tooLong),
                    routes = listOf(AdvertisedRoute("device-b", 0, 100, "self"))
                ),
                "device-b",
                60,
                "ble",
                1_000L
            )
        )
        assertTrue(runtime.capabilitiesForPeer("device-b", 1_500L).isEmpty())
    }
}
