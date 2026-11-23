package com.amurcanov.tgwsproxy

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

private const val UPDATE_CACHE_DIR = "app-updates"
private const val MAX_ASSET_REDIRECTS = 5
private val sha256Hex = Regex("[0-9a-f]{64}")
private val updateApkFileName = Regex("update-[0-9a-f]{64}\\.apk")
private val githubAssetHosts = setOf(
    "release-assets.githubusercontent.com",
    "objects.githubusercontent.com",
    "github-releases.githubusercontent.com"
)

enum class AppUpdateFailure {
    DOWNLOAD, INTEGRITY, PACKAGE, VERSION, SIGNATURE, INSTALLER
}

class AppUpdateException(
    val reason: AppUpdateFailure,
    cause: Throwable? = null
) : IOException(reason.name, cause)

class AppUpdateFileProvider : FileProvider()

suspend fun prepareAppUpdate(context: Context, release: AppReleaseInfo): File =
    withContext(Dispatchers.IO) {
        val expectedUrl = expectedUpdateDownloadUrl(release.rawTag)
        if (expectedUrl == null || release.downloadUrl != expectedUrl) {
            throw AppUpdateException(AppUpdateFailure.DOWNLOAD)
        }
        if (release.apkSize !in 1..MAX_UPDATE_APK_BYTES || !sha256Hex.matches(release.apkSha256)) {
            throw AppUpdateException(AppUpdateFailure.INTEGRITY)
        }

        val directory = File(context.cacheDir, UPDATE_CACHE_DIR)
        var temporaryFile: File? = null
        try {
            if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
                throw IOException("Unable to create update cache")
            }
            val temporary = File.createTempFile("pending-", ".apk", directory)
            temporaryFile = temporary
            downloadUpdateApk(URL(expectedUrl), release, temporary)
            verifyUpdatePackage(context, temporary, release.rawTag)
            ensureActive()

            val apk = File(directory, "update-${release.apkSha256}.apk")
            if (!temporary.renameTo(apk)) throw IOException("Unable to finish staging update")
            apk
        } catch (error: CancellationException) {
            throw error
        } catch (error: AppUpdateException) {
            throw error
        } catch (error: Exception) {
            throw AppUpdateException(AppUpdateFailure.DOWNLOAD, error)
        } finally {
            temporaryFile?.delete()
        }
    }

fun canInstallAppUpdates(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

fun appUpdateInstallSettings(context: Context): Intent =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.fromParts("package", context.packageName, null)
        )
    } else {
        Intent(Settings.ACTION_SECURITY_SETTINGS)
    }

fun launchAppUpdateInstaller(context: Context, apk: File) {
    try {
        val updateDirectory = File(context.cacheDir, UPDATE_CACHE_DIR).canonicalFile
        val file = apk.canonicalFile
        if (!canInstallAppUpdates(context) || !file.isFile ||
            file.parentFile != updateDirectory || !updateApkFileName.matches(file.name)
        ) throw AppUpdateException(AppUpdateFailure.INSTALLER)

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.appupdates", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            clipData = ClipData.newRawUri(null, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (error: AppUpdateException) {
        throw error
    } catch (error: Exception) {
        throw AppUpdateException(AppUpdateFailure.INSTALLER, error)
    }
}

private suspend fun downloadUpdateApk(initialUrl: URL, release: AppReleaseInfo, destination: File) {
    val coroutine = currentCoroutineContext()
    var url = initialUrl
    repeat(MAX_ASSET_REDIRECTS + 1) {
        coroutine.ensureActive()
        val allowedHost = url.toExternalForm() == initialUrl.toExternalForm() ||
            url.host.lowercase() in githubAssetHosts
        if (!allowedHost || url.protocol != "https" || url.userInfo != null || url.ref != null ||
            (url.port != -1 && url.port != 443)
        ) throw AppUpdateException(AppUpdateFailure.DOWNLOAD)

        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("Accept-Encoding", "identity")
            connection.setRequestProperty("User-Agent", "TGWSProxyAndroid/${BuildConfig.VERSION_NAME}")
            when (connection.responseCode) {
                HttpURLConnection.HTTP_OK -> {
                    val contentLength = connection.getHeaderField("Content-Length")?.toLongOrNull()
                    if (contentLength != null && contentLength != release.apkSize) {
                        throw AppUpdateException(AppUpdateFailure.INTEGRITY)
                    }

                    val digest = MessageDigest.getInstance("SHA-256")
                    val buffer = ByteArray(32 * 1024)
                    var bytes = 0L
                    connection.inputStream.use { input ->
                        destination.outputStream().buffered().use { output ->
                            while (true) {
                                coroutine.ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                bytes += count
                                if (bytes > release.apkSize) {
                                    throw AppUpdateException(AppUpdateFailure.INTEGRITY)
                                }
                                digest.update(buffer, 0, count)
                                output.write(buffer, 0, count)
                            }
                        }
                    }
                    if (bytes != release.apkSize || digest.digest().toHex() != release.apkSha256) {
                        throw AppUpdateException(AppUpdateFailure.INTEGRITY)
                    }
                    return
                }

                301, 302, 303, 307, 308 -> {
                    val location = connection.getHeaderField("Location")
                        ?.takeIf(String::isNotBlank)
                        ?: throw AppUpdateException(AppUpdateFailure.DOWNLOAD)
                    url = URL(url, location)
                }

                else -> throw AppUpdateException(AppUpdateFailure.DOWNLOAD)
            }
        } finally {
            connection.disconnect()
        }
    }
    throw AppUpdateException(AppUpdateFailure.DOWNLOAD)
}

@Suppress("DEPRECATION")
private fun verifyUpdatePackage(context: Context, apk: File, releaseTag: String) {
    val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        PackageManager.GET_SIGNING_CERTIFICATES
    } else {
        PackageManager.GET_SIGNATURES
    }
    val packageManager = context.packageManager
    val archive = packageManager.getPackageArchiveInfo(apk.absolutePath, flags)
        ?: throw AppUpdateException(AppUpdateFailure.PACKAGE)
    if (archive.packageName != context.packageName) {
        throw AppUpdateException(AppUpdateFailure.PACKAGE)
    }

    val installed = packageManager.getPackageInfo(context.packageName, flags)
    if (updateVersionNumber(releaseTag) != archive.versionName?.let(::updateVersionNumber) ||
        PackageInfoCompat.getLongVersionCode(archive) <= PackageInfoCompat.getLongVersionCode(installed)
    ) {
        throw AppUpdateException(AppUpdateFailure.VERSION)
    }

    val installedSigners = installed.currentSignerDigests()
    if (installedSigners.isEmpty() || archive.currentSignerDigests() != installedSigners) {
        throw AppUpdateException(AppUpdateFailure.SIGNATURE)
    }
}

@Suppress("DEPRECATION")
private fun PackageInfo.currentSignerDigests(): Set<String> {
    val signers = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        signingInfo?.apkContentsSigners
    } else {
        signatures
    }
    val digest = MessageDigest.getInstance("SHA-256")
    return signers.orEmpty().map { digest.digest(it.toByteArray()).toHex() }.toSet()
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
