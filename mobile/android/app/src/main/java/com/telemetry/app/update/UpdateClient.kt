package com.telemetry.app.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import com.telemetry.app.BuildConfig
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

internal data class UpdateInfo(
    val updateAvailable: Boolean,
    val versionCode: Long,
    val versionName: String,
    val apkUrl: String,
    val sha256: String,
    val notes: String
)

internal object UpdateClient {
    private const val ENDPOINT = "https://telemetry-web-production.up.railway.app/api/v1/android/update"

    fun check(): UpdateInfo {
        val url = URL("$ENDPOINT?channel=preview&versionCode=${BuildConfig.VERSION_CODE}")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 1800
            readTimeout = 2500
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "Telemetry-Android-Preview/${BuildConfig.VERSION_NAME}")
        }
        try {
            val code = connection.responseCode
            require(code in 200..299) { "Update service unavailable ($code)" }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            return UpdateInfo(
                updateAvailable = json.optBoolean("updateAvailable", false),
                versionCode = json.optLong("versionCode", BuildConfig.VERSION_CODE.toLong()),
                versionName = json.optString("versionName", BuildConfig.VERSION_NAME),
                apkUrl = json.optString("apkUrl"),
                sha256 = json.optString("sha256").lowercase(),
                notes = json.optString("notes", "Telemetry Android Preview update")
            )
        } finally {
            connection.disconnect()
        }
    }

    fun download(context: Context, info: UpdateInfo, onProgress: (Int) -> Unit): File {
        require(info.apkUrl.startsWith("https://")) { "Update URL must use HTTPS" }
        require(info.sha256.matches(Regex("[a-f0-9]{64}"))) { "Update checksum is missing" }

        val targetDir = File(context.cacheDir, "updates").apply { mkdirs() }
        val partial = File(targetDir, "telemetry-preview-${info.versionCode}.apk.part")
        val target = File(targetDir, "telemetry-preview-${info.versionCode}.apk")
        partial.delete()
        target.delete()

        val connection = (URL(info.apkUrl).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 5000
            readTimeout = 15000
            setRequestProperty("User-Agent", "Telemetry-Android-Preview/${BuildConfig.VERSION_NAME}")
        }
        try {
            require(connection.responseCode in 200..299) { "APK download failed (${connection.responseCode})" }
            val total = connection.contentLengthLong.coerceAtLeast(1L)
            connection.inputStream.use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var received = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        received += count
                        onProgress(((received * 100L) / total).toInt().coerceIn(0, 100))
                    }
                }
            }
        } finally {
            connection.disconnect()
        }

        verifyDownloadedApk(context, partial, info)
        require(partial.renameTo(target)) { "Could not finalize downloaded update" }
        return target
    }

    private fun verifyDownloadedApk(context: Context, file: File, info: UpdateInfo) {
        val actualSha = sha256(file)
        require(actualSha.equals(info.sha256, ignoreCase = true)) { "APK checksum verification failed" }

        val pm = context.packageManager
        val archive = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pm.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNATURES)
        } ?: error("Downloaded file is not a valid APK")

        require(archive.packageName == context.packageName) { "Update package does not match this app" }
        val archiveVersion = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            archive.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            archive.versionCode.toLong()
        }
        require(archiveVersion == info.versionCode) { "Update version does not match manifest" }
        require(archiveVersion > BuildConfig.VERSION_CODE.toLong()) { "Downloaded APK is not newer" }

        val installed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
        }
        require(signingDigest(installed) == signingDigest(archive)) { "Update signing certificate does not match" }
    }

    private fun signingDigest(info: PackageInfo): String {
        val certificate = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()
        } else {
            @Suppress("DEPRECATION")
            info.signatures?.firstOrNull()?.toByteArray()
        } ?: error("APK signing certificate unavailable")
        return MessageDigest.getInstance("SHA-256")
            .digest(certificate)
            .joinToString("") { "%02x".format(it) }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun installerIntent(context: Context, apk: File): Intent {
        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.updates",
            apk
        )
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}
