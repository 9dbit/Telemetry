package com.telemetry.app.media

import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.util.Base64

const val MEDIA_MANIFEST_CONTENT_TYPE = "application/telemetry+media-manifest"
const val MEDIA_CONTROL_CONTENT_TYPE = "application/telemetry+media-control"

object AndroidMediaManifestJson {
    fun encodePayload(manifest: NativeMediaManifest): ByteArray {
        validate(manifest)
        val manifestJson = JSONObject()
            .put("version", manifest.version)
            .put("assetId", manifest.assetId)
            .put("kind", manifest.kind)
            .put("mimeType", manifest.mimeType)
            .put("fileName", manifest.fileName)
            .put("byteLength", manifest.byteLength)
            .put("chunkBytes", manifest.chunkBytes)
            .put("chunkCount", manifest.chunkCount)
            .put("sha256", manifest.sha256)
            .put("contentKey", manifest.contentKey)
            .put("createdAt", manifest.createdAt)
        return JSONObject()
            .put("kind", "media-manifest")
            .put("manifest", manifestJson)
            .toString()
            .toByteArray(StandardCharsets.UTF_8)
    }

    fun decodePayload(bytes: ByteArray): NativeMediaManifest {
        val root = JSONObject(String(bytes, StandardCharsets.UTF_8))
        require(root.getString("kind") == "media-manifest") { "invalid media manifest payload kind" }
        val json = root.getJSONObject("manifest")
        return NativeMediaManifest(
            version = json.getString("version"),
            assetId = json.getString("assetId"),
            kind = json.getString("kind"),
            mimeType = json.getString("mimeType"),
            fileName = json.getString("fileName"),
            byteLength = json.getLong("byteLength"),
            chunkBytes = json.getInt("chunkBytes"),
            chunkCount = json.getInt("chunkCount"),
            sha256 = json.getString("sha256"),
            contentKey = json.getString("contentKey"),
            createdAt = json.getString("createdAt")
        ).also(::validate)
    }

    fun validate(manifest: NativeMediaManifest) {
        require(manifest.version == "telemetry/media-manifest/0.1") { "unsupported media manifest version" }
        require(manifest.assetId.length in 8..128) { "invalid media assetId" }
        require(manifest.kind in setOf("photo", "video", "file")) { "unsupported media kind" }
        require(manifest.mimeType.isNotEmpty() && manifest.mimeType.length <= 127) { "invalid media mimeType" }
        require(manifest.fileName.isNotEmpty() && manifest.fileName.length <= 255) { "invalid media fileName" }
        require(manifest.byteLength in 1..(512L * 1024 * 1024)) { "invalid media byteLength" }
        require(manifest.chunkBytes in (16 * 1024)..(1024 * 1024)) { "invalid media chunkBytes" }
        require(manifest.chunkCount in 1..8192) { "invalid media chunkCount" }
        require(manifest.chunkCount == ((manifest.byteLength + manifest.chunkBytes - 1) / manifest.chunkBytes).toInt()) {
            "media chunkCount does not match byteLength"
        }
        require(manifest.sha256.matches(Regex("[0-9a-f]{64}"))) { "invalid media sha256" }
        require(runCatching { Base64.getUrlDecoder().decode(manifest.contentKey) }.getOrNull()?.size == 32) {
            "invalid media content key"
        }
        require(runCatching { java.time.Instant.parse(manifest.createdAt) }.isSuccess) { "invalid media createdAt" }
    }
}
