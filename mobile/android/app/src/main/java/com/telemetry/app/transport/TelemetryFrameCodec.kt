package com.telemetry.app.transport

import com.telemetry.app.crypto.DeliveryReceipt
import com.telemetry.app.crypto.EncryptedMessage
import com.telemetry.app.crypto.SessionHello
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.util.Base64

object TelemetryFrameCodec {
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    fun encodeHello(hello: SessionHello): ByteArray = JSONObject()
        .put("v", "m1b")
        .put("d", hello.deviceId)
        .put("s", b64(hello.signingPublicKey))
        .put("x", b64(hello.exchangePublicKey))
        .put("e", b64(hello.ephemeralPublicKey))
        .put("n", b64(hello.nonce))
        .put("t", hello.issuedAt)
        .put("g", b64(hello.signature))
        .toString().toByteArray(StandardCharsets.UTF_8)

    fun decodeHello(bytes: ByteArray): SessionHello {
        val json = JSONObject(String(bytes, StandardCharsets.UTF_8))
        require(json.getString("v") == "m1b")
        return SessionHello(
            deviceId = json.getString("d"),
            signingPublicKey = unb64(json.getString("s")),
            exchangePublicKey = unb64(json.getString("x")),
            ephemeralPublicKey = unb64(json.getString("e")),
            nonce = unb64(json.getString("n")),
            issuedAt = json.getLong("t"),
            signature = unb64(json.getString("g"))
        )
    }

    fun encodeMessage(message: EncryptedMessage): ByteArray = JSONObject()
        .put("v", "m1b")
        .put("i", message.messageId)
        .put("s", message.senderId)
        .put("r", message.recipientId)
        .put("t", message.createdAt)
        .put("n", b64(message.nonce))
        .put("c", b64(message.ciphertext))
        .toString().toByteArray(StandardCharsets.UTF_8)

    fun decodeMessage(bytes: ByteArray): EncryptedMessage {
        val json = JSONObject(String(bytes, StandardCharsets.UTF_8))
        require(json.getString("v") == "m1b")
        return EncryptedMessage(
            messageId = json.getString("i"),
            senderId = json.getString("s"),
            recipientId = json.getString("r"),
            createdAt = json.getLong("t"),
            nonce = unb64(json.getString("n")),
            ciphertext = unb64(json.getString("c"))
        )
    }

    fun encodeReceipt(receipt: DeliveryReceipt): ByteArray = JSONObject()
        .put("v", "m1b")
        .put("i", receipt.messageId)
        .put("s", receipt.senderId)
        .put("r", receipt.recipientId)
        .put("t", receipt.receivedAt)
        .put("q", receipt.status)
        .put("g", b64(receipt.signature))
        .toString().toByteArray(StandardCharsets.UTF_8)

    fun decodeReceipt(bytes: ByteArray): DeliveryReceipt {
        val json = JSONObject(String(bytes, StandardCharsets.UTF_8))
        require(json.getString("v") == "m1b")
        return DeliveryReceipt(
            messageId = json.getString("i"),
            senderId = json.getString("s"),
            recipientId = json.getString("r"),
            receivedAt = json.getLong("t"),
            status = json.getString("q"),
            signature = unb64(json.getString("g"))
        )
    }

    private fun b64(bytes: ByteArray): String = encoder.encodeToString(bytes)
    private fun unb64(text: String): ByteArray = decoder.decode(text)
}
