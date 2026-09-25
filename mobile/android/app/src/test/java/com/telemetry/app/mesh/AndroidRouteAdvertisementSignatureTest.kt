package com.telemetry.app.mesh

import com.telemetry.app.crypto.DeviceIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class AndroidRouteAdvertisementSignatureTest {
    private val seed = ByteArray(32) { it.toByte() }
    private val identity = DeviceIdentity(seed, ByteArray(32) { (it + 32).toByte() })
    private val createdAt = Instant.parse("2026-09-25T06:30:00.000Z").toEpochMilli()
    private val expiresAt = Instant.parse("2026-09-25T06:30:15.000Z").toEpochMilli()

    private fun draft() = RouteAdvertisementDraft(
        advertiserId = identity.deviceId,
        sequence = 7,
        createdAtEpochMs = createdAt,
        expiresAtEpochMs = expiresAt,
        capabilities = listOf("ble", "mesh-relay", "wifi-direct"),
        routes = listOf(
            AdvertisedRoute(identity.deviceId, 0, 100, "self"),
            AdvertisedRoute(
                "tlm:device:03396219237f75a64f12aeb7f39723ab",
                1,
                72,
                "wifi-direct"
            )
        )
    )

    @Test
    fun canonicalJsonAndSignatureMatchSharedInteropVector() {
        assertEquals("tlm:device:56475aa75463474c0285df5dbf2bcab7", identity.deviceId)
        val draft = draft()
        val canonical = AndroidRouteAdvertisementSignature.canonicalJson(draft)
        assertEquals(
            "{\"protocol\":\"telemetry/mesh-route/0.1\",\"advertiserId\":\"tlm:device:56475aa75463474c0285df5dbf2bcab7\",\"sequence\":7,\"createdAt\":\"2026-09-25T06:30:00.000Z\",\"expiresAt\":\"2026-09-25T06:30:15.000Z\",\"capabilities\":[\"ble\",\"mesh-relay\",\"wifi-direct\"],\"routes\":[{\"destinationId\":\"tlm:device:56475aa75463474c0285df5dbf2bcab7\",\"hops\":0,\"quality\":100,\"transport\":\"self\"},{\"destinationId\":\"tlm:device:03396219237f75a64f12aeb7f39723ab\",\"hops\":1,\"quality\":72,\"transport\":\"wifi-direct\"}]}",
            canonical
        )

        val signed = AndroidRouteAdvertisementSignature.sign(identity, draft)
        assertEquals(
            "t545ILreDattu7j8kV2Gydyqqo5rIR94w-4JcPaWYfCDM5RLYhqei1KxSQHDn-HuUAFXlPj-B1pa9iP-_CqgDw",
            AndroidRouteAdvertisementSignature.signatureBase64Url(signed)
        )
    }

    @Test
    fun signedAdvertisementVerifiesAndTamperingFails() {
        val signed = AndroidRouteAdvertisementSignature.sign(identity, draft())
        val verified = AndroidRouteAdvertisementSignature.verify(
            signed,
            identity.signingPublicKey,
            createdAt + 1_000L
        )
        assertNotNull(verified)
        assertEquals(7L, verified!!.sequence)
        assertEquals(2, verified.routes.size)

        val tampered = signed.copy(
            routes = signed.routes.mapIndexed { index, route ->
                if (index == 1) route.copy(quality = -72) else route
            }
        )
        assertNull(
            AndroidRouteAdvertisementSignature.verify(
                tampered,
                identity.signingPublicKey,
                createdAt + 1_000L
            )
        )
    }
}
