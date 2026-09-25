package com.telemetry.app.media

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import java.io.InputStream

private const val MAX_MEDIA_SOURCE_BYTES = 512L * 1024 * 1024

data class AndroidResolvedMediaSource(
    val uri: Uri,
    val kind: String,
    val mimeType: String,
    val fileName: String,
    val byteLength: Long,
    val openInput: () -> InputStream
)

class AndroidMediaSourceAdapter(
    private val context: Context
) {
    private val resolver: ContentResolver = context.contentResolver

    fun persistReadPermission(uri: Uri) {
        runCatching {
            resolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun resolve(uri: Uri, requestedKind: String? = null): AndroidResolvedMediaSource {
        require(uri.scheme == ContentResolver.SCHEME_CONTENT || uri.scheme == ContentResolver.SCHEME_FILE) {
            "unsupported media URI scheme"
        }
        val metadata = queryMetadata(uri)
        val mime = resolver.getType(uri)?.takeIf { it.isNotBlank() }
            ?: guessMimeFromName(metadata.first)
            ?: "application/octet-stream"
        val kind = requestedKind?.also {
            require(it in setOf("photo", "video", "file")) { "unsupported requested media kind" }
        } ?: when {
            mime.startsWith("image/") -> "photo"
            mime.startsWith("video/") -> "video"
            else -> "file"
        }
        val name = sanitizeFileName(metadata.first ?: fallbackName(uri, mime))
        val size = metadata.second
            ?: resolveLength(uri)
            ?: measureLength(uri)
        require(size in 1..MAX_MEDIA_SOURCE_BYTES) { "media source size invalid or exceeds 512 MiB" }

        return AndroidResolvedMediaSource(
            uri = uri,
            kind = kind,
            mimeType = mime.take(127),
            fileName = name,
            byteLength = size,
            openInput = {
                resolver.openInputStream(uri) ?: error("unable to open media source")
            }
        )
    }

    private fun queryMetadata(uri: Uri): Pair<String?, Long?> {
        val projection = arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val cursor: Cursor = runCatching {
            resolver.query(uri, projection, null, null, null)
        }.getOrNull() ?: return null to null
        cursor.use {
            if (!it.moveToFirst()) return null to null
            val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = it.getColumnIndex(OpenableColumns.SIZE)
            val name = if (nameIndex >= 0 && !it.isNull(nameIndex)) it.getString(nameIndex) else null
            val size = if (sizeIndex >= 0 && !it.isNull(sizeIndex)) {
                it.getLong(sizeIndex).takeIf { value -> value > 0 }
            } else {
                null
            }
            return name to size
        }
    }

    private fun resolveLength(uri: Uri): Long? = runCatching {
        resolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
            descriptor.length.takeIf { it > 0 }
        }
    }.getOrNull()

    private fun measureLength(uri: Uri): Long {
        val input = resolver.openInputStream(uri) ?: error("unable to open media source")
        input.use {
            val buffer = ByteArray(128 * 1024)
            var total = 0L
            while (true) {
                val read = it.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                total += read
                require(total <= MAX_MEDIA_SOURCE_BYTES) { "media source exceeds 512 MiB" }
            }
            return total
        }
    }

    private fun sanitizeFileName(value: String): String {
        val cleaned = value
            .replace(Regex("[\\u0000-\\u001f\\u007f]"), "_")
            .replace('/', '_')
            .replace('\\', '_')
            .trim()
            .take(255)
        return cleaned.ifBlank { "telemetry-media" }
    }

    private fun fallbackName(uri: Uri, mime: String): String {
        val last = uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
        if (last != null) return last
        val extension = when {
            mime == "image/jpeg" -> ".jpg"
            mime == "image/png" -> ".png"
            mime == "image/webp" -> ".webp"
            mime == "video/mp4" -> ".mp4"
            else -> ".bin"
        }
        return "telemetry-media$extension"
    }

    private fun guessMimeFromName(name: String?): String? = when (name?.substringAfterLast('.', "")?.lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "mp4" -> "video/mp4"
        "mov" -> "video/quicktime"
        "pdf" -> "application/pdf"
        "txt" -> "text/plain"
        else -> null
    }
}
