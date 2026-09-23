package com.telemetry.app.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class M1BInteropVectorTest {
    @Test
    fun androidMatchesCrossPlatformM1BVector() {
        val issuedAt = 1770000000000L
        val aIdentity = DeviceIdentity(
            ed25519Seed = hex("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"),
            x25519Seed = hex("202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f")
        )
        val bIdentity = DeviceIdentity(
            ed25519Seed = hex("404142434445464748494a4b4c4d4e4f505152535455565758595a5b5c5d5e5f"),
            x25519Seed = hex("606162636465666768696a6b6c6d6e6f707172737475767778797a7b7c7d7e7f")
        )
        val aEphemeral = EphemeralKeyPair(
            privateSeed = hex("808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9f"),
            publicKey = hex("493e82fc74464a59268817623d2053c5eb8e2cc4a988b4fee179ec6b010d531d")
        )
        val bEphemeral = EphemeralKeyPair(
            privateSeed = hex("a0a1a2a3a4a5a6a7a8a9aaabacadaeafb0b1b2b3b4b5b6b7b8b9babbbcbdbebf"),
            publicKey = hex("605a725d2a4adfeeb1a29e17edd621c1b7593ee8cdbc44ac6c4ab6e2f805d23c")
        )

        assertEquals("tlm:device:56475aa75463474c0285df5dbf2bcab7", aIdentity.deviceId)
        assertEquals("tlm:device:03396219237f75a64f12aeb7f39723ab", bIdentity.deviceId)
        assertArrayEquals(
            hex("03a107bff3ce10be1d70dd18e74bc09967e4d6309ba50d5f1ddc8664125531b8"),
            aIdentity.signingPublicKey
        )
        assertArrayEquals(
            hex("2543b92ff1095511476adc8369db6ddc933665a11978dda1404ee1066ca9559d"),
            bIdentity.signingPublicKey
        )

        val aHello = TelemetryCrypto.createHello(
            identity = aIdentity,
            ephemeral = aEphemeral,
            issuedAt = issuedAt,
            nonce = hex("a0a1a2a3a4a5a6a7a8a9aaabacadaeaf")
        )
        val bHello = TelemetryCrypto.createHello(
            identity = bIdentity,
            ephemeral = bEphemeral,
            issuedAt = issuedAt,
            nonce = hex("b0b1b2b3b4b5b6b7b8b9babbbcbdbebf")
        )

        assertArrayEquals(
            hex("3da54bf0bb0fc88e30e7086e064f84a06927ccb09abe3c69a2e2e459e3711d14d54f47129daf8892a690c4ed89b388ce5cf4915bbc51caeec07e0aaaa32f4a07"),
            aHello.signature
        )
        assertArrayEquals(
            hex("81ef660db42bb996515a59a91f28f4eea49e42a9928932858a5390dbd15fd58b21e19ae0584b34203834511d73c6d511ec61034c2becabc0d624241b40c37809"),
            bHello.signature
        )
        assertTrue(TelemetryCrypto.verifyHello(aHello, now = issuedAt))
        assertTrue(TelemetryCrypto.verifyHello(bHello, now = issuedAt))

        val aKey = TelemetryCrypto.deriveSessionKey(aEphemeral, aHello, bHello, now = issuedAt)
        val bKey = TelemetryCrypto.deriveSessionKey(bEphemeral, bHello, aHello, now = issuedAt)
        val expectedKey = hex("55d8ea80763832c890c0e1695c09bb554ab13ca96b6aacc8976e2cbebea04faf")
        assertArrayEquals(expectedKey, aKey)
        assertArrayEquals(expectedKey, bKey)
        assertEquals("822 483", TelemetryCrypto.safetyCode(aHello, bHello))

        val message = TelemetryCrypto.encryptText(
            sessionKey = aKey,
            senderId = aIdentity.deviceId,
            recipientId = bIdentity.deviceId,
            text = "Hello Telemetry",
            createdAt = 1770000001234L,
            messageId = "11111111-2222-3333-4444-555555555555",
            nonce = hex("c0c1c2c3c4c5c6c7c8c9cacb")
        )
        assertArrayEquals(
            hex("3096040f019c9495a9e613d2101e86c6becc824ade9aea364c5a4dddf660fc"),
            message.ciphertext
        )
        assertEquals("Hello Telemetry", TelemetryCrypto.decryptText(bKey, message))

        val receipt = TelemetryCrypto.createReceipt(
            identity = bIdentity,
            message = message,
            receivedAt = 1770000002345L
        )
        assertArrayEquals(
            hex("7541704782943ff245f5712a7bb7d9fb330d1e2875fe46592299c9cf654861f845145144f4578a98d635310f02a9f5ded739a9c8cc134feb5e5c446c25662807"),
            receipt.signature
        )
        assertTrue(TelemetryCrypto.verifyReceipt(receipt, bIdentity.signingPublicKey))
    }

    private fun hex(value: String): ByteArray = value.chunked(2)
        .map { it.toInt(16).toByte() }
        .toByteArray()
}
