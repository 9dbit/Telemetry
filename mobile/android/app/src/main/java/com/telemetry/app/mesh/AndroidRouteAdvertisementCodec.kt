package com.telemetry.app.mesh

import com.telemetry.app.crypto.DeviceIdentity
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets

class AndroidRouteAdvertisementCodec(
    private val identity: DeviceIdentity
) : RouteAdvertisementCodec {
    companion object {
        private val MAGIC = byteArrayOf('T'.code.toByte(), 'M'.code.toByte(), 'A'.code.toByte(), '1'.code.toByte())
        private const val MAX_STRING_BYTES = 512
        private const val MAX_CAPABILITIES = 16
        private const val MAX_ROUTES = 32
        private const val SIGNATURE_BYTES = 64
    }

    override fun encodeAndSign(draft: RouteAdvertisementDraft): ByteArray {
        val signed = AndroidRouteAdvertisementSignature.sign(identity, draft)
        return encodeSigned(signed)
    }

    override fun verifyAndDecode(
        bytes: ByteArray,
        signingPublicKey: ByteArray,
        nowEpochMs: Long
    ): VerifiedRouteAdvertisement? = runCatching {
        val signed = decodeSigned(bytes)
        AndroidRouteAdvertisementSignature.verify(signed, signingPublicKey, nowEpochMs)
            ?: error("route advertisement signature rejected")
    }.getOrNull()

    fun encodeSigned(advertisement: SignedMobileRouteAdvertisement): ByteArray {
        require(advertisement.capabilities.size <= MAX_CAPABILITIES) { "too many capabilities" }
        require(advertisement.routes.size <= MAX_ROUTES) { "too many routes" }
        require(advertisement.signature.size == SIGNATURE_BYTES) { "invalid route signature size" }

        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use { out ->
            out.write(MAGIC)
            writeString(out, advertisement.advertiserId)
            out.writeLong(advertisement.sequence)
            out.writeLong(advertisement.createdAtEpochMs)
            out.writeLong(advertisement.expiresAtEpochMs)
            out.writeByte(advertisement.capabilities.size)
            advertisement.capabilities.forEach { writeString(out, it) }
            out.writeByte(advertisement.routes.size)
            advertisement.routes.forEach { route ->
                writeString(out, route.destinationId)
                require(route.hops in 0..31) { "route hops out of range" }
                require(route.quality in -100..100) { "route quality out of range" }
                out.writeByte(route.hops)
                out.writeShort(route.quality)
                writeString(out, route.transport)
            }
            out.write(advertisement.signature)
        }
        return buffer.toByteArray()
    }

    fun decodeSigned(bytes: ByteArray): SignedMobileRouteAdvertisement {
        require(bytes.size > MAGIC.size + SIGNATURE_BYTES) { "route advertisement too short" }
        val source = ByteArrayInputStream(bytes)
        val input = DataInputStream(source)

        val magic = ByteArray(MAGIC.size).also(input::readFully)
        require(magic.contentEquals(MAGIC)) { "unsupported route wire magic" }

        val advertiserId = readString(input)
        val sequence = input.readLong()
        val createdAt = input.readLong()
        val expiresAt = input.readLong()
        val capabilityCount = input.readUnsignedByte()
        require(capabilityCount <= MAX_CAPABILITIES) { "too many capabilities" }
        val capabilities = List(capabilityCount) { readString(input) }
        val routeCount = input.readUnsignedByte()
        require(routeCount <= MAX_ROUTES) { "too many routes" }
        val routes = List(routeCount) {
            val destinationId = readString(input)
            val hops = input.readUnsignedByte()
            require(hops in 0..31) { "route hops out of range" }
            val quality = input.readShort().toInt()
            require(quality in -100..100) { "route quality out of range" }
            val transport = readString(input)
            AdvertisedRoute(destinationId, hops, quality, transport)
        }

        require(source.available() == SIGNATURE_BYTES) { "invalid route signature/trailing bytes" }
        val signature = ByteArray(SIGNATURE_BYTES).also(input::readFully)
        require(source.available() == 0) { "unexpected trailing route bytes" }

        return SignedMobileRouteAdvertisement(
            protocol = "telemetry/mesh-route/0.1",
            advertiserId = advertiserId,
            sequence = sequence,
            createdAtEpochMs = createdAt,
            expiresAtEpochMs = expiresAt,
            capabilities = capabilities,
            routes = routes,
            signature = signature
        )
    }

    private fun writeString(out: DataOutputStream, value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        require(bytes.isNotEmpty()) { "route string must not be empty" }
        require(bytes.size <= MAX_STRING_BYTES) { "route string too long" }
        out.writeShort(bytes.size)
        out.write(bytes)
    }

    private fun readString(input: DataInputStream): String {
        val length = input.readUnsignedShort()
        require(length in 1..MAX_STRING_BYTES) { "route string length invalid" }
        val bytes = ByteArray(length).also(input::readFully)
        return String(bytes, StandardCharsets.UTF_8).also {
            require(it.isNotBlank()) { "route string must not be blank" }
        }
    }
}
