package com.telemetry.app.mesh

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MeshRelayWireCodecTest {
    private fun frame() = OpaqueRelayFrame(
        header = MeshRelayHeader(
            messageId = "m-1",
            senderId = "device-a",
            recipientId = "device-c",
            hopCount = 1,
            hopLimit = 4,
            relayPath = listOf("device-a", "device-b"),
            createdAtEpochMs = 1_000L,
            expiresAtEpochMs = 2_000L
        ),
        encodedEnvelope = byteArrayOf(1, 2, 3, 4)
    )

    @Test
    fun deterministicVectorMatchesCrossPlatformContract() {
        val encoded = MeshRelayWireCodec.encode(frame())
        val hex = encoded.joinToString("") { "%02x".format(it) }

        assertEquals(
            "544d523100036d2d3100086465766963652d6100086465766963652d6301040200086465766963652d6100086465766963652d6200000000000003e800000000000007d00000000401020304",
            hex
        )
    }

    @Test
    fun roundTripPreservesOpaqueEnvelopeAndRelayMetadata() {
        val original = frame()
        val decoded = MeshRelayWireCodec.decode(MeshRelayWireCodec.encode(original))

        assertEquals(original.header, decoded.header)
        assertArrayEquals(original.encodedEnvelope, decoded.encodedEnvelope)
    }

    @Test
    fun malformedMagicAndTrailingBytesAreRejected() {
        val encoded = MeshRelayWireCodec.encode(frame())
        val badMagic = encoded.copyOf().also { it[0] = 'X'.code.toByte() }
        val trailing = encoded + byteArrayOf(0)

        assertTrue(runCatching { MeshRelayWireCodec.decode(badMagic) }.isFailure)
        assertTrue(runCatching { MeshRelayWireCodec.decode(trailing) }.isFailure)
    }
}
