package com.telemetry.app.mesh

import com.telemetry.app.crypto.DeviceIdentity
import com.telemetry.app.crypto.TelemetryCrypto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class AndroidRouteAdvertisementCodecTest {
    private val seed = ByteArray(32) { it.toByte() }
    private val identity = DeviceIdentity(seed, ByteArray(32) { (it + 32).toByte() })
    private val createdAt = Instant.parse("2026-09-25T06:30:00.000Z").toEpochMilli()
    private val expiresAt = Instant.parse("2026-09-25T06:30:15.000Z").toEpochMilli()
    private val expectedHex = "544d4131002b746c6d3a6465766963653a35363437356161373534363334373463303238356466356462663262636162370000000000000007000001a0d741aa40000001a0d741e4d8030003626c65000a6d6573682d72656c6179000b776966692d64697265637402002b746c6d3a6465766963653a3536343735616137353436333437346330323835646635646266326263616237000064000473656c66002b746c6d3a6465766963653a3033333936323139323337663735613634663132616562376633393732336162010048000b776966692d646972656374b79e3920bade0dab6dbbb8fc915d86c9dcaaaa8e6b211f78c3ee0970f69661f08333944b621a9e8b52b14901c39fe1ee50015794f8fe075a5af623fefc2aa00f"

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
    fun encodedWireMatchesCrossPlatformTma1Vector() {
        val codec = AndroidRouteAdvertisementCodec(identity)
        val wire = codec.encodeAndSign(draft())
        val hex = wire.joinToString("") { "%02x".format(it) }
        assertEquals(expectedHex, hex)
    }

    @Test
    fun verifiedDecodeRoundTripsOnlyAfterSignatureValidation() {
        val codec = AndroidRouteAdvertisementCodec(identity)
        val wire = codec.encodeAndSign(draft())
        val verified = codec.verifyAndDecode(wire, identity.signingPublicKey, createdAt + 1_000L)

        assertNotNull(verified)
        assertEquals(identity.deviceId, verified!!.advertiserId)
        assertEquals(7L, verified.sequence)
        assertEquals(listOf("ble", "mesh-relay", "wifi-direct"), verified.capabilities)
        assertEquals(2, verified.routes.size)
    }

    @Test
    fun tamperingTrailingBytesAndWrongIdentityAreRejected() {
        val codec = AndroidRouteAdvertisementCodec(identity)
        val wire = codec.encodeAndSign(draft())

        val tampered = wire.copyOf().also { bytes ->
            val target = "wifi-direct".toByteArray()
            val start = bytes.indices.first { index ->
                index + target.size <= bytes.size && target.indices.all { offset -> bytes[index + offset] == target[offset] }
            }
            bytes[start] = 'x'.code.toByte()
        }
        assertNull(codec.verifyAndDecode(tampered, identity.signingPublicKey, createdAt + 1_000L))
        assertNull(codec.verifyAndDecode(wire + byteArrayOf(0), identity.signingPublicKey, createdAt + 1_000L))

        val other = TelemetryCrypto.newIdentity()
        assertNull(codec.verifyAndDecode(wire, other.signingPublicKey, createdAt + 1_000L))
    }

    @Test
    fun expiredAdvertisementIsRejected() {
        val codec = AndroidRouteAdvertisementCodec(identity)
        val wire = codec.encodeAndSign(draft())
        assertNull(codec.verifyAndDecode(wire, identity.signingPublicKey, expiresAt))
    }
}
