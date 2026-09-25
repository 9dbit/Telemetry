package com.telemetry.app.media

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class PersistedOutgoingMediaTransfer(
    val assetId: String,
    val peerId: String,
    val manifestControlWire: ByteArray,
    val chunkCount: Int,
    val snapshot: NativeMediaTransferSnapshot,
    val expiresAtEpochMs: Long
)

data class PersistedIncomingMediaControl(
    val messageId: String,
    val controlWire: ByteArray,
    val expiresAtEpochMs: Long
)

class AndroidMediaTransferStateStore(context: Context) {
    private val prefs = context.getSharedPreferences("telemetry_media_state_v1", Context.MODE_PRIVATE)
    private val alias = "telemetry.media.state.wrap.v1"
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    @Synchronized
    fun saveOutgoing(transfer: PersistedOutgoingMediaTransfer) {
        require(transfer.assetId.length in 8..128) { "invalid persisted media assetId" }
        require(transfer.peerId.isNotBlank()) { "invalid persisted media peerId" }
        require(transfer.manifestControlWire.isNotEmpty()) { "missing persisted media manifest wire" }
        require(transfer.chunkCount in 1..8192) { "invalid persisted media chunkCount" }
        require(transfer.snapshot.assetId == transfer.assetId && transfer.snapshot.chunkCount == transfer.chunkCount) {
            "persisted media snapshot mismatch"
        }
        require(transfer.expiresAtEpochMs > 0L) { "invalid persisted media expiry" }
        val json = JSONObject()
            .put("kind", "outgoing")
            .put("assetId", transfer.assetId)
            .put("peerId", transfer.peerId)
            .put("manifestWire", encoder.encodeToString(transfer.manifestControlWire))
            .put("chunkCount", transfer.chunkCount)
            .put("expiresAt", transfer.expiresAtEpochMs)
            .put("snapshot", snapshotToJson(transfer.snapshot))
        prefs.edit().putString(outgoingKey(transfer.assetId), seal(json.toString().toByteArray())).apply()
    }

    @Synchronized
    fun loadOutgoing(nowEpochMs: Long): List<PersistedOutgoingMediaTransfer> {
        val output = mutableListOf<PersistedOutgoingMediaTransfer>()
        val editor = prefs.edit()
        prefs.all.forEach { (key, value) ->
            if (!key.startsWith("out:")) return@forEach
            val encoded = value as? String ?: return@forEach
            val parsed = runCatching { JSONObject(String(open(encoded))) }.getOrNull()
            if (parsed == null || parsed.optString("kind") != "outgoing") {
                editor.remove(key)
                return@forEach
            }
            val expiry = parsed.optLong("expiresAt", 0L)
            if (expiry <= nowEpochMs) {
                editor.remove(key)
                return@forEach
            }
            runCatching {
                val assetId = parsed.getString("assetId")
                val chunkCount = parsed.getInt("chunkCount")
                val snapshot = snapshotFromJson(parsed.getJSONObject("snapshot"))
                PersistedOutgoingMediaTransfer(
                    assetId = assetId,
                    peerId = parsed.getString("peerId"),
                    manifestControlWire = decoder.decode(parsed.getString("manifestWire")),
                    chunkCount = chunkCount,
                    snapshot = snapshot,
                    expiresAtEpochMs = expiry
                )
            }.onSuccess(output::add).onFailure { editor.remove(key) }
        }
        editor.apply()
        return output
    }

    @Synchronized
    fun removeOutgoing(assetId: String) {
        prefs.edit().remove(outgoingKey(assetId)).apply()
    }

    @Synchronized
    fun saveIncomingControl(control: PersistedIncomingMediaControl) {
        require(control.messageId.isNotBlank()) { "invalid incoming media control messageId" }
        require(control.controlWire.isNotEmpty()) { "missing incoming media control wire" }
        require(control.expiresAtEpochMs > 0L) { "invalid incoming media control expiry" }
        val json = JSONObject()
            .put("kind", "incoming-control")
            .put("messageId", control.messageId)
            .put("wire", encoder.encodeToString(control.controlWire))
            .put("expiresAt", control.expiresAtEpochMs)
        prefs.edit().putString(incomingKey(control.messageId), seal(json.toString().toByteArray())).apply()
    }

    @Synchronized
    fun loadIncomingControls(nowEpochMs: Long): List<PersistedIncomingMediaControl> {
        val output = mutableListOf<PersistedIncomingMediaControl>()
        val editor = prefs.edit()
        prefs.all.forEach { (key, value) ->
            if (!key.startsWith("in:")) return@forEach
            val encoded = value as? String ?: return@forEach
            val parsed = runCatching { JSONObject(String(open(encoded))) }.getOrNull()
            if (parsed == null || parsed.optString("kind") != "incoming-control") {
                editor.remove(key)
                return@forEach
            }
            val expiry = parsed.optLong("expiresAt", 0L)
            if (expiry <= nowEpochMs) {
                editor.remove(key)
                return@forEach
            }
            runCatching {
                PersistedIncomingMediaControl(
                    messageId = parsed.getString("messageId"),
                    controlWire = decoder.decode(parsed.getString("wire")),
                    expiresAtEpochMs = expiry
                )
            }.onSuccess(output::add).onFailure { editor.remove(key) }
        }
        editor.apply()
        return output
    }

    @Synchronized
    fun removeIncomingControl(messageId: String) {
        prefs.edit().remove(incomingKey(messageId)).apply()
    }

    private fun snapshotToJson(snapshot: NativeMediaTransferSnapshot): JSONObject {
        val acked = JSONArray()
        snapshot.acknowledgedIndices.forEach(acked::put)
        val sent = JSONArray()
        snapshot.sentState.toSortedMap().forEach { (index, state) ->
            sent.put(
                JSONObject()
                    .put("index", index)
                    .put("attempts", state.attempts)
                    .put("lastSentAt", state.lastSentAtEpochMs)
            )
        }
        return JSONObject()
            .put("assetId", snapshot.assetId)
            .put("chunkCount", snapshot.chunkCount)
            .put("acked", acked)
            .put("sent", sent)
    }

    private fun snapshotFromJson(json: JSONObject): NativeMediaTransferSnapshot {
        val assetId = json.getString("assetId")
        val chunkCount = json.getInt("chunkCount")
        require(chunkCount in 1..8192) { "invalid persisted snapshot chunkCount" }
        val ackedJson = json.getJSONArray("acked")
        val acked = buildList {
            for (index in 0 until ackedJson.length()) add(ackedJson.getInt(index))
        }
        val sentJson = json.getJSONArray("sent")
        val sent = linkedMapOf<Int, NativeMediaSendState>()
        for (index in 0 until sentJson.length()) {
            val item = sentJson.getJSONObject(index)
            sent[item.getInt("index")] = NativeMediaSendState(
                attempts = item.getInt("attempts"),
                lastSentAtEpochMs = item.getLong("lastSentAt")
            )
        }
        return NativeMediaTransferSnapshot(assetId, chunkCount, acked, sent)
    }

    private fun outgoingKey(assetId: String): String = "out:${hashId(assetId)}"
    private fun incomingKey(messageId: String): String = "in:${hashId(messageId)}"

    private fun hashId(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }

    private fun seal(plain: ByteArray): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, wrappingKey())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plain)
        val framed = ByteBuffer.allocate(1 + iv.size + ciphertext.size)
            .put(iv.size.toByte())
            .put(iv)
            .put(ciphertext)
            .array()
        return encoder.encodeToString(framed)
    }

    private fun open(encoded: String): ByteArray {
        val sealed = decoder.decode(encoded)
        val buffer = ByteBuffer.wrap(sealed)
        val ivSize = buffer.get().toInt() and 0xff
        require(ivSize in 12..16 && buffer.remaining() > ivSize) { "invalid media-state sealed blob" }
        val iv = ByteArray(ivSize).also(buffer::get)
        val ciphertext = ByteArray(buffer.remaining()).also(buffer::get)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, wrappingKey(), GCMParameterSpec(128, iv))
        return cipher.doFinal(ciphertext)
    }

    private fun wrappingKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }
}
