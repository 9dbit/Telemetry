package com.telemetry.app.media

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidMediaChunkWireCodecTest {
    private fun hex(value: String): ByteArray = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun fixedVectorMatchesSharedTmc1Contract() {
        val chunk = NativeEncryptedMediaChunk(
            assetId = "asset-demo-0001",
            index = 1,
            count = 3,
            plainBytes = 4,
            nonce = hex("000102030405060708090a0b"),
            ciphertext = hex("deadbeef"),
            tag = hex("101112131415161718191a1b1c1d1e1f")
        )
        val expected = hex(
            "544d4331000f61737365742d64656d6f2d30303031" +
                "000000010000000300000004" +
                "000102030405060708090a0b" +
                "101112131415161718191a1b1c1d1e1f" +
                "00000004deadbeef"
        )

        val encoded = AndroidMediaChunkWireCodec.encode(chunk)
        assertArrayEquals(expected, encoded)
        val decoded = AndroidMediaChunkWireCodec.decode(encoded)
        assertEquals(chunk.assetId, decoded.assetId)
        assertEquals(chunk.index, decoded.index)
        assertEquals(chunk.count, decoded.count)
        assertEquals(chunk.plainBytes, decoded.plainBytes)
        assertArrayEquals(chunk.nonce, decoded.nonce)
        assertArrayEquals(chunk.ciphertext, decoded.ciphertext)
        assertArrayEquals(chunk.tag, decoded.tag)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsTrailingBytes() {
        val wire = AndroidMediaChunkWireCodec.encode(
            NativeEncryptedMediaChunk(
                assetId = "asset-demo-0001",
                index = 0,
                count = 1,
                plainBytes = 1,
                nonce = ByteArray(12),
                ciphertext = byteArrayOf(1),
                tag = ByteArray(16)
            )
        )
        AndroidMediaChunkWireCodec.decode(wire + byteArrayOf(0))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsWrongMagic() {
        val bad = hex(
            "004d4331000f61737365742d64656d6f2d30303031" +
                "000000010000000300000004" +
                "000102030405060708090a0b" +
                "101112131415161718191a1b1c1d1e1f" +
                "00000004deadbeef"
        )
        AndroidMediaChunkWireCodec.decode(bad)
    }
}
