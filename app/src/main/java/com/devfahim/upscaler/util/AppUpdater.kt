package com.devfahim.upscaler.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Checks for Upscaler app updates against this repository's GitHub Releases.
 *
 * Used in two places:
 *  - a silent check on every app launch (UpscalerRoot) that pops a dialog when a
 *    newer version is published, and
 *  - a manual "Check for updates" action on the About screen.
 *
 * This is the ONLY networking in the whole app: upscaling itself is 100%
 * on-device and photos never leave the phone. No analytics, no tracking -
 * just one version lookup per launch.
 *
 * Version discovery deliberately avoids the rate-limited REST API as its
 * primary strategy: GitHub allows only 60 anonymous API requests/hour *per
 * source IP*, and on carrier mobile networks (heavy CGNAT) that quota is
 * shared by huge numbers of subscribers and dies almost instantly. So:
 *
 *  1. **Primary**: read the plain CDN redirect of
 *     `https://github.com/<repo>/releases/latest` -> `/releases/tag/vX.Y.Z`
 *     straight out of the Location header. No API quota involved at all.
 *  2. **Fallback**: the classic `api.github.com/.../releases/latest` JSON
 *     call, which also hands us the exact APK asset URL when it works.
 *
 * The download URL of the APK is predictable either way: CI names the asset
 * `Upscaler-v<version>-release.apk` (see .github/workflows/android.yml), so
 * even when only the redirect trick succeeds we can construct a direct
 * download link.
 */
object AppUpdater {

    private const val OWNER = "devfahim00"
    private const val REPO = "Upscaler"

    /** Telegram of the owner - surfaced in About. */
    const val TELEGRAM_URL = "https://t.me/droxilen"
    const val TELEGRAM_HANDLE = "@droxilen"

    private const val LATEST_RELEASE_PAGE =
        "https://github.com/$OWNER/$REPO/releases/latest"
    private const val LATEST_RELEASE_API =
        "https://api.github.com/repos/$OWNER/$REPO/releases/latest"

    /**
     * Direct APK download URL for a given release tag. The asset basename is
     * produced by CI and MUST stay in sync with .github/workflows/android.yml.
     */
    private fun apkUrlFor(tag: String): String =
        "https://github.com/$OWNER/$REPO/releases/download/$tag/Upscaler-$tag-release.apk"

    /** A published GitHub release, reduced to what the update UI needs. */
    data class Release(
        val tag: String,
        val apkUrl: String,
        val htmlUrl: String = LATEST_RELEASE_PAGE
    ) {
        /** "v1.2.0" -> "1.2.0" */
        val version: String get() = tag.removePrefix("v").trim()
    }

    sealed class Result {
        data class UpToDate(val currentVersion: String) : Result()
        data class Available(val release: Release, val currentVersion: String) : Result()
        data class Error(val message: String) : Result()
    }

    /** Installed versionName, e.g. "1.0.0". */
    fun currentVersion(context: Context): String = runCatching {
        val info = if (Build.VERSION.SDK_INT >= 28) {
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.PackageInfoFlags.of(0)
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0)
        }
        info.versionName
    }.getOrNull() ?: "unknown"

    /**
     * Fetches the latest release tag and decides whether it is newer than the
     * installed build. Call from Dispatchers.IO.
     */
    fun check(context: Context): Result {
        val current = currentVersion(context)
        return try {
            val viaApi = latestViaApi()
            val tag = latestTagViaRedirect() ?: viaApi?.first
            if (tag.isNullOrBlank()) {
                Result.Error("could not reach GitHub - try again later")
            } else {
                val release = Release(
                    tag = tag,
                    apkUrl = viaApi?.second ?: apkUrlFor(tag)
                )
                if (isNewer(release.version, current)) {
                    Result.Available(release, current)
                } else {
                    Result.UpToDate(current)
                }
            }
        } catch (e: Exception) {
            Result.Error(e.message?.take(120) ?: "unknown error")
        }
    }

    /**
     * Follows the `releases/latest` redirect with manual redirects disabled
     * and pulls the tag out of the Location header. Works even when
     * api.github.com is rate-limited because it never touches the API.
     */
    private fun latestTagViaRedirect(): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(LATEST_RELEASE_PAGE).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.requestMethod = "HEAD"
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("User-Agent", "Upscaler-Android-App")
            conn.connect()
            val location = conn.getHeaderField("Location")
            if (conn.responseCode in 300..399 && !location.isNullOrBlank()) {
                Regex("/tag/(v[\\w.+-]+)").find(location)?.groupValues?.get(1)
            } else {
                null
            }
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * Fallback: the REST API call. Returns the tag plus the APK asset URL
     * when the release actually has one. Never throws - returns null on any
     * failure (404, rate limit, malformed JSON, timeout).
     */
    private fun latestViaApi(): Pair<String, String?>? {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(LATEST_RELEASE_API).openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 12000
            conn.setRequestProperty("User-Agent", "Upscaler-Android-App")
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.connect()
            if (conn.responseCode !in 200..299) return null
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            val tag = json.optString("tag_name").trim()
            if (tag.isEmpty()) return null
            val apkUrl = json.optJSONArray("assets")?.let { assets ->
                (0 until assets.length()).asSequence()
                    .mapNotNull { assets.optJSONObject(it) }
                    .firstOrNull { it.optString("name").endsWith(".apk") }
                    ?.optString("browser_download_url")
                    ?.takeIf { it.isNotBlank() }
            }
            tag to apkUrl
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    /** True when [remote] is strictly newer than [installed] ("1.2.0" style). */
    fun isNewer(remote: String, installed: String): Boolean {
        val r = remote.split('.').map { it.toIntOrNull() ?: 0 }
        val i = installed.split('.').map { it.toIntOrNull() ?: 0 }
        for (k in 0 until maxOf(r.size, i.size)) {
            val rv = r.getOrNull(k) ?: 0
            val iv = i.getOrNull(k) ?: 0
            if (rv != iv) return rv > iv
        }
        return false
    }

    /** Opens any external link (release APK, Telegram, ...) in the browser. */
    fun openLink(context: Context, url: String) {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
