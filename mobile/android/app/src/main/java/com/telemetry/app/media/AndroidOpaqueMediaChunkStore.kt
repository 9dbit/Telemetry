package com.telemetry.app.media

import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

data class StoredOpaqueMediaChunk(
    val assetId: String,
    val index: Int,
    val count: Int,
    val wire: ByteArray,
    val expiresAtEpochMs: Long
)

data class MediaChunkStoreResult(
    val accepted: Boolean,
    val reason: String,
    val assetId: String? = null,
    val index: Int? = null
)

class AndroidOpaqueMediaChunkStore(
    private val root: File
) {
    init {
        require(root.exists() || root.mkdirs()) { "unable to create media chunk store" }
        require(root.isDirectory) { "media chunk store root must be directory" }
    }

    @Synchronized
    fun put(wire: ByteArray, expiresAtEpochMs: Long): MediaChunkStoreResult {
        require(expiresAtEpochMs > 0L) { "invalid media chunk expiry" }
        val chunk = AndroidMediaChunkWireCodec.decode(wire)
        val dir = assetDirectory(chunk.assetId)
        require(dir.exists() || dir.mkdirs()) { "unable to create media asset directory" }
        val dataFile = File(dir, "${chunk.index}.tmc1")
        val expiryFile = File(dir, "${chunk.index}.expires")
        if (dataFile.exists()) {
            return MediaChunkStoreResult(false, "duplicate", chunk.assetId, chunk.index)
        }

        atomicWrite(dataFile, wire)
        try {
            atomicWrite(expiryFile, expiresAtEpochMs.toString().toByteArray(StandardCharsets.US_ASCII))
        } catch (error: Throwable) {
            dataFile.delete()
            throw error
        }
        return MediaChunkStoreResult(true, "stored", chunk.assetId, chunk.index)
    }

    @Synchronized
    fun records(nowEpochMs: Long): List<StoredOpaqueMediaChunk> {
        prune(nowEpochMs)
        val records = mutableListOf<StoredOpaqueMediaChunk>()
        root.listFiles()?.filter { it.isDirectory }?.forEach { dir ->
            dir.listFiles()?.filter { it.isFile && it.name.endsWith(".tmc1") }?.forEach { dataFile ->
                val stem = dataFile.name.removeSuffix(".tmc1")
                val expiryFile = File(dir, "$stem.expires")
                val expiry = expiryFile.takeIf(File::isFile)?.readText()?.trim()?.toLongOrNull() ?: return@forEach
                val wire = runCatching { dataFile.readBytes() }.getOrNull() ?: return@forEach
                val chunk = runCatching { AndroidMediaChunkWireCodec.decode(wire) }.getOrNull() ?: return@forEach
                records += StoredOpaqueMediaChunk(
                    assetId = chunk.assetId,
                    index = chunk.index,
                    count = chunk.count,
                    wire = wire,
                    expiresAtEpochMs = expiry
                )
            }
        }
        return records.sortedWith(compareBy<StoredOpaqueMediaChunk> { it.assetId }.thenBy { it.index })
    }

    @Synchronized
    fun remove(assetId: String, index: Int): Boolean {
        val dir = assetDirectory(assetId)
        val data = File(dir, "$index.tmc1")
        val expiry = File(dir, "$index.expires")
        val removed = data.delete()
        expiry.delete()
        cleanupDirectory(dir)
        return removed
    }

    @Synchronized
    fun missingIndices(assetId: String, count: Int, nowEpochMs: Long): List<Int> {
        require(count in 1..8192) { "invalid media chunk count" }
        val present = records(nowEpochMs)
            .filter { it.assetId == assetId && it.count == count }
            .mapTo(mutableSetOf()) { it.index }
        return (0 until count).filterNot(present::contains)
    }

    @Synchronized
    fun prune(nowEpochMs: Long): Int {
        require(nowEpochMs >= 0L) { "invalid media chunk store time" }
        var removed = 0
        root.listFiles()?.filter { it.isDirectory }?.forEach { dir ->
            dir.listFiles()?.filter { it.isFile && it.name.endsWith(".tmc1") }?.forEach { dataFile ->
                val stem = dataFile.name.removeSuffix(".tmc1")
                val expiryFile = File(dir, "$stem.expires")
                val expiry = expiryFile.takeIf(File::isFile)?.readText()?.trim()?.toLongOrNull()
                val invalid = expiry == null || expiry <= nowEpochMs || runCatching {
                    AndroidMediaChunkWireCodec.decode(dataFile.readBytes())
                }.isFailure
                if (invalid) {
                    if (dataFile.delete()) removed += 1
                    expiryFile.delete()
                }
            }
            cleanupDirectory(dir)
        }
        return removed
    }

    private fun assetDirectory(assetId: String): File {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(assetId.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return File(root, digest)
    }

    private fun atomicWrite(target: File, bytes: ByteArray) {
        val temp = File(target.parentFile, ".${target.name}.${System.nanoTime()}.tmp")
        try {
            FileOutputStream(temp).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            if (target.exists()) {
                temp.delete()
                return
            }
            require(temp.renameTo(target)) { "unable to commit media chunk file" }
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    private fun cleanupDirectory(dir: File) {
        if (dir.isDirectory && (dir.list()?.isEmpty() == true)) dir.delete()
    }
}
