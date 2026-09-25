package com.telemetry.app.media

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.security.MessageDigest

data class NativeMediaReceiveProgress(
    val assetId: String,
    val receivedChunks: Int,
    val totalChunks: Int,
    val complete: Boolean,
    val receivedIndices: List<Int>
)

class AndroidMediaReceiveAssembler(
    private val store: AndroidOpaqueMediaChunkStore,
    private val builder: AndroidMediaPackageBuilder = AndroidMediaPackageBuilder()
) {
    fun progress(manifest: NativeMediaManifest, nowEpochMs: Long): NativeMediaReceiveProgress {
        AndroidMediaManifestJson.validate(manifest)
        val records = store.records(nowEpochMs)
            .filter { it.assetId == manifest.assetId && it.count == manifest.chunkCount }
            .sortedBy { it.index }
        val indices = records.map { it.index }.distinct()
        return NativeMediaReceiveProgress(
            assetId = manifest.assetId,
            receivedChunks = indices.size,
            totalChunks = manifest.chunkCount,
            complete = indices.size == manifest.chunkCount,
            receivedIndices = indices
        )
    }

    fun verifyComplete(manifest: NativeMediaManifest, nowEpochMs: Long): Boolean = runCatching {
        val tempRoot = Files.createTempDirectory("telemetry-media-verify-").toFile()
        try {
            materializeToCache(manifest, nowEpochMs, tempRoot).delete()
            true
        } finally {
            tempRoot.deleteRecursively()
        }
    }.getOrDefault(false)

    fun materializeToCache(
        manifest: NativeMediaManifest,
        nowEpochMs: Long,
        cacheRoot: File
    ): File {
        AndroidMediaManifestJson.validate(manifest)
        require(cacheRoot.exists() || cacheRoot.mkdirs()) { "unable to create media cache root" }
        require(cacheRoot.isDirectory) { "media cache root must be directory" }

        val records = store.records(nowEpochMs)
            .filter { it.assetId == manifest.assetId && it.count == manifest.chunkCount }
            .associateBy { it.index }
        require(records.size == manifest.chunkCount) { "media chunks incomplete" }

        val safeName = manifest.fileName
            .replace(Regex("[\\u0000-\\u001f\\u007f/\\\\]"), "_")
            .trim()
            .take(120)
            .ifBlank { "telemetry-media" }
        val digestPrefix = MessageDigest.getInstance("SHA-256")
            .digest(manifest.assetId.toByteArray())
            .take(8)
            .joinToString("") { "%02x".format(it) }
        val target = File(cacheRoot, "$digestPrefix-$safeName")
        val temp = File(cacheRoot, ".${target.name}.${System.nanoTime()}.tmp")
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L

        try {
            FileOutputStream(temp).use { output ->
                for (index in 0 until manifest.chunkCount) {
                    val record = records[index] ?: error("missing media chunk $index")
                    val encrypted = AndroidMediaChunkWireCodec.decode(record.wire)
                    val plain = builder.decryptChunk(manifest, encrypted)
                    output.write(plain)
                    digest.update(plain)
                    total += plain.size
                }
                output.fd.sync()
            }
            require(total == manifest.byteLength) { "assembled media size mismatch" }
            val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
            require(actualHash == manifest.sha256) { "assembled media hash mismatch" }
            if (target.exists()) target.delete()
            require(temp.renameTo(target)) { "unable to commit verified media cache file" }
            return target
        } finally {
            if (temp.exists()) temp.delete()
        }
    }
}
