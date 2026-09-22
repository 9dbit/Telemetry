package com.telemetry.app.crypto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.nio.ByteBuffer
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class AndroidIdentityStore(context: Context) {
    private val prefs = context.getSharedPreferences("telemetry_identity_v1", Context.MODE_PRIVATE)
    private val alias = "telemetry.identity.wrap.v1"

    fun getOrCreate(): DeviceIdentity {
        val sealed = prefs.getString("sealed_identity", null)
        if (sealed != null) {
            runCatching { return decodeIdentity(decrypt(Base64.getUrlDecoder().decode(sealed))) }
        }

        val identity = TelemetryCrypto.newIdentity()
        val raw = identity.ed25519Seed + identity.x25519Seed
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(encrypt(raw))
        prefs.edit().putString("sealed_identity", encoded).apply()
        return identity
    }

    private fun encodeIdentity(identity: DeviceIdentity): ByteArray = identity.ed25519Seed + identity.x25519Seed

    private fun decodeIdentity(raw: ByteArray): DeviceIdentity {
        require(raw.size == 64) { "invalid sealed identity" }
        return DeviceIdentity(raw.copyOfRange(0, 32), raw.copyOfRange(32, 64))
    }

    private fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, wrappingKey())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plain)
        return ByteBuffer.allocate(1 + iv.size + ciphertext.size)
            .put(iv.size.toByte())
            .put(iv)
            .put(ciphertext)
            .array()
    }

    private fun decrypt(sealed: ByteArray): ByteArray {
        val buffer = ByteBuffer.wrap(sealed)
        val ivSize = buffer.get().toInt() and 0xff
        require(ivSize in 12..16 && buffer.remaining() > ivSize)
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
