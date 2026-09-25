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
    val minimumVersionCode: Long,
    val mandatory: Boolean,
    val apkUrl: String,
    val sha256: String,
    val sizeBytes: Long,
    val packageName: String,
    val signingCertificateSha256: String,
    val notes: String,
    val publishedAt: String
)

internal object UpdateClient {
    private const val ENDPOINT = "https://telemetry-update-controller-production.up.railway.app/api/mobile/android/latest"
    private const val CHANNEL = "preview"

    fun check(): UpdateInfo {
        val url = URL("$ENDPOINT?channel=$CHANNEL")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 1800
            readTimeout = 2500
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "Telemetry-Android-Preview/${BuildConfig.VERSION_NAME}")
        }
        try {
            val code = connection.responseCode
            if (code == 404) {
                return currentInfo()
            }
            require(code in 200..299) { "Update service unavailable ($code)" }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            val remoteVersion = json.getLong("versionCode")
            val minimum = json.optLong("minimumVersionCode", 0L)
            val expectedPackage = json.getString("packageName")
            require(expectedPackage == BuildConfig.APPLICATION_ID) { "Update manifest package mismatch" }
            val certificateFingerprint = json.getString("signingCertificateSha256").uppercase()
            require(certificateFingerprint.matches(Regex("[A-F0-9]{64}"))) { "Update signing fingerprint is invalid" }
            val sha = json.getString("sha256").lowercase()
            require(sha.matches(Regex("[a-f0-9]{64}"))) { "Update checksum is invalid" }
            val apkUrl = json.getString("apkUrl")
            require(apkUrl.startsWith("https://github.com/9dbit/Telemetry/releases/download/android-preview-")) {
                "Update URL is not an approved Telemetry release"
            }
            return UpdateInfo(
                updateAvailable = remoteVersion > BuildConfig.VERSION_CODE.toLong(),
                versionCode = remoteVersion,
                versionName = json.getString("versionName"),
                minimumVersionCode = minimum,
                mandatory = json.optBoolean("mandatory", false),
                apkUrl = apkUrl,
                sha256 = sha,
                sizeBytes = json.getLong("sizeBytes"),
                packageName = expectedPackage,
                signingCertificateSha256 = certificateFingerprint,
                notes = json.optString("releaseNotes", "Telemetry Android Preview update"),
                publishedAt = json.optString("publishedAt", "")
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun currentInfo() = UpdateInfo(
        updateAvailable = false,
        versionCode = BuildConfig.VERSION_CODE.toLong(),
        versionName = BuildConfig.VERSION_NAME,
        minimumVersionCode = 0L,
        mandatory = false,
        apkUrl = "",
        sha256 = "",
        sizeBytes = 0L,
        packageName = BuildConfig.APPLICATION_ID,
        signingCertificateSha256 = "",
        notes = "No promoted preview release is available yet.",
        publishedAt = ""
    )

    fun download(context: Context, info: UpdateInfo, onProgress: (Int) -> Unit): File {
        require(info.updateAvailable) { "No newer update is available" }
        require(info.apkUrl.startsWith("https://")) { "Update URL must use HTTPS" }
        require(info.sha256.matches(Regex("[a-f0-9]{64}"))) { "Update checksum is missing" }
        require(info.packageName == context.packageName) { "Update package does not match this app" }

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
            val declaredLength = connection.contentLengthLong
            if (declaredLength > 0 && info.sizeBytes > 0) {
                require(declaredLength == info.sizeBytes) { "APK size does not match release manifest" }
            }
            val total = if (info.sizeBytes > 0) info.sizeBytes else declaredLength.coerceAtLeast(1L)
            connection.inputStream.use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var received = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        received += count
                        onProgress(((received * 100L) / total.coerceAtLeast(1L)).toInt().coerceIn(0, 100))
                    }
                    if (info.sizeBytes > 0) require(received == info.sizeBytes) { "APK download size mismatch" }
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
        val installedDigest = signingDigest(installed)
        val archiveDigest = signingDigest(archive)
        require(installedDigest == archiveDigest) { "Update signing certificate does not match installed app" }
        require(archiveDigest.equals(info.signingCertificateSha256, ignoreCase = true)) {
            "Update signing certificate does not match promoted release manifest"
        }
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
            .joinToString("") { "%02X".format(it) }
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
