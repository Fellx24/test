package com.amurcanov.tgwsproxy

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.Serializable
import java.net.HttpURLConnection
import java.net.URL

const val UPDATE_CHECK_NEVER = -1
const val DEFAULT_UPDATE_CHECK_INTERVAL_HOURS = 12
const val UPDATE_DIALOG_ACTION_POSTPONED = "postponed"
const val UPDATE_DIALOG_ACTION_UPDATE = "update"

private const val UPDATE_LOG_TAG = "TgWsProxy"
private const val MAX_RELEASE_BYTES = 1024 * 1024
internal const val UPDATE_APK_NAME = "tg-ws-proxy-android.apk"
internal const val MAX_UPDATE_APK_BYTES = 128L * 1024 * 1024

private val repositoryName = Regex("[A-Za-z0-9][A-Za-z0-9-]{0,38}/[A-Za-z0-9._-]{1,100}")
private val releaseVersion = Regex("v?([0-9]+(?:\\.[0-9]+)*)", RegexOption.IGNORE_CASE)
private val assetDigest = Regex("sha256:([0-9a-fA-F]{64})")

fun updateIntervalHoursToMillis(hours: Int): Long? = when {
    hours <= 0 -> null
    else -> hours * 60L * 60L * 1000L
}

fun updateIntervalLabel(context: Context, hours: Int): String = when (hours) {
    7 -> context.getString(R.string.interval_7h)
    24 -> context.getString(R.string.interval_24h)
    48 -> context.getString(R.string.interval_48h)
    UPDATE_CHECK_NEVER -> context.getString(R.string.interval_never)
    else -> context.getString(R.string.interval_hours, hours)
}

fun updateIntervalLabel(hours: Int): String = when (hours) {
    7 -> "7 h"
    24 -> "24 h"
    48 -> "48 h"
    UPDATE_CHECK_NEVER -> "Never"
    else -> "$hours h"
}

data class AppReleaseInfo(
    val versionTag: String,
    val releaseUrl: String,
    internal val downloadUrl: String,
    internal val apkSha256: String,
    internal val apkSize: Long,
    internal val rawTag: String
) : Serializable

suspend fun fetchLatestReleaseInfo(): AppReleaseInfo? = withContext(Dispatchers.IO) {
    val repo = configuredUpdateRepository() ?: return@withContext null
    try {
        val connection = URL("https://api.github.com/repos/$repo/releases/latest")
            .openConnection() as HttpURLConnection
        val release = try {
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.connectTimeout = 8_000
            connection.readTimeout = 8_000
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            connection.setRequestProperty("User-Agent", "TGWSProxyAndroid/${BuildConfig.VERSION_NAME}")

            val status = connection.responseCode
            if (status != HttpURLConnection.HTTP_OK) {
                Log.w(UPDATE_LOG_TAG, "Update check: GitHub returned HTTP $status")
                return@withContext null
            }

            connection.inputStream.buffered().use { input ->
                val buffer = ByteArray(8192)
                val bytes = ByteArrayOutputStream()
                while (true) {
                    ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (bytes.size() + count > MAX_RELEASE_BYTES) {
                        throw IOException("GitHub release response is too large")
                    }
                    bytes.write(buffer, 0, count)
                }
                JSONObject(bytes.toString(Charsets.UTF_8.name()))
            }
        } finally {
            connection.disconnect()
        }

        ensureActive()
        parseRelease(release, repo)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        Log.w(UPDATE_LOG_TAG, "Update check failed", error)
        null
    }
}

internal fun updateVersionNumber(tag: String): String? =
    releaseVersion.matchEntire(tag)?.groupValues?.get(1)

fun isNewerVersion(local: String, remote: String): Boolean {
    val localParts = versionParts(local) ?: return false
    val remoteParts = versionParts(remote) ?: return false
    for (i in 0 until maxOf(localParts.size, remoteParts.size)) {
        val l = localParts.getOrElse(i) { 0 }
        val r = remoteParts.getOrElse(i) { 0 }
        if (r != l) return r > l
    }
    return false
}

internal fun expectedUpdateDownloadUrl(tag: String): String? {
    val repo = configuredUpdateRepository() ?: return null
    if (updateVersionNumber(tag) == null) return null
    return "https://github.com/$repo/releases/download/$tag/$UPDATE_APK_NAME"
}

private fun parseRelease(release: JSONObject, repo: String): AppReleaseInfo? {
    if (release.optBoolean("draft") || release.optBoolean("prerelease")) return null
    val tag = release.optString("tag_name")
    val version = updateVersionNumber(tag) ?: return null
    val releaseUrl = "https://github.com/$repo/releases/tag/$tag"
    if (release.optString("html_url") != releaseUrl) return null

    val expectedDownload = expectedUpdateDownloadUrl(tag) ?: return null
    val assets = release.optJSONArray("assets") ?: return null
    val asset = (0 until assets.length())
        .mapNotNull(assets::optJSONObject)
        .singleOrNull { it.optString("name") == UPDATE_APK_NAME }
        ?: return null
    if (asset.optString("state") != "uploaded" ||
        asset.optString("browser_download_url") != expectedDownload
    ) return null

    val size = asset.optLong("size")
    if (size !in 1..MAX_UPDATE_APK_BYTES) return null
    val digest = assetDigest.matchEntire(asset.optString("digest"))?.groupValues?.get(1)
        ?: return null

    return AppReleaseInfo("v$version", releaseUrl, expectedDownload, digest.lowercase(), size, tag)
}

private fun configuredUpdateRepository(): String? =
    BuildConfig.UPDATE_REPOSITORY.takeIf(repositoryName::matches)

private fun versionParts(tag: String): List<Int>? {
    val version = updateVersionNumber(tag) ?: return null
    return version.split('.').map { it.toIntOrNull() ?: return null }
}
