package com.autodora.next.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

sealed class UpdateCheckState {
    data object Idle : UpdateCheckState()
    data object Checking : UpdateCheckState()
    data class UpToDate(val currentVersion: String) : UpdateCheckState()
    data class UpdateAvailable(
        val latestVersion: String,
        val releaseUrl: String,
        val apkDownloadUrl: String?,
        val releaseNotes: String,
        val isPrerelease: Boolean
    ) : UpdateCheckState()
    data class Downloading(
        val latestVersion: String,
        val downloadedBytes: Long,
        val totalBytes: Long,
        val releaseUrl: String
    ) : UpdateCheckState()
    data class ReadyToInstall(
        val latestVersion: String,
        val apkPath: String,
        val releaseUrl: String
    ) : UpdateCheckState()
    data class Error(val detail: String) : UpdateCheckState()
}

/**
 * Lightweight GitHub Releases update checker & in-app APK downloader (zero third-party HTTP dependencies).
 * Queries `/releases?per_page=8` so both pre-1.0 prereleases (v0.x.y) and stable releases (v1.x.y)
 * are discovered reliably, and streams the APK directly into app cache for automatic system installation.
 */
object UpdateChecker {
    const val GITHUB_REPO_URL = "https://github.com/CloverKing/AutoDora-NEXT"
    private const val GITHUB_RELEASES_API = "https://api.github.com/repos/CloverKing/AutoDora-NEXT/releases?per_page=8"
    private const val MAX_REDIRECTS = 6

    suspend fun checkLatestRelease(currentVersion: String): UpdateCheckState = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(GITHUB_RELEASES_API).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8_000
                readTimeout = 8_000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "AutoDora-NEXT/$currentVersion")
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                return@withContext UpdateCheckState.Error("HTTP $code")
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val arr = JSONArray(body)

            var bestTag: String? = null
            var bestUrl = "$GITHUB_REPO_URL/releases"
            var bestApkUrl: String? = null
            var bestNotes = ""
            var bestPre = false

            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                if (obj.optBoolean("draft", false)) continue
                val tag = obj.optString("tag_name", "").trim()
                if (tag.isEmpty()) continue
                if (bestTag == null || compareSemVer(tag, bestTag) > 0) {
                    bestTag = tag
                    bestUrl = obj.optString("html_url", "$GITHUB_REPO_URL/releases").ifBlank { "$GITHUB_REPO_URL/releases" }
                    bestNotes = obj.optString("body", "").trim()
                    bestPre = obj.optBoolean("prerelease", false)
                    bestApkUrl = null
                    val assets = obj.optJSONArray("assets")
                    if (assets != null) {
                        for (a in 0 until assets.length()) {
                            val asset = assets.optJSONObject(a) ?: continue
                            val dl = asset.optString("browser_download_url", "")
                            if (dl.endsWith(".apk", ignoreCase = true)) {
                                bestApkUrl = dl
                                break
                            }
                        }
                    }
                }
            }

            if (bestTag == null || compareSemVer(bestTag, currentVersion) <= 0) {
                UpdateCheckState.UpToDate(normalizeVersion(currentVersion))
            } else {
                UpdateCheckState.UpdateAvailable(
                    latestVersion = normalizeVersion(bestTag),
                    releaseUrl = bestUrl,
                    apkDownloadUrl = bestApkUrl,
                    releaseNotes = bestNotes,
                    isPrerelease = bestPre
                )
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            UpdateCheckState.Error(e.localizedMessage ?: e.javaClass.simpleName)
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * Streams a release APK directly into [updatesDir] (atomically via `.part` -> `.apk`),
     * explicitly handling cross-host CDN redirects (e.g. GitHub -> `objects.githubusercontent.com`),
     * reporting throttled progress callbacks, cleaning stale cached APKs, and verifying the ZIP/APK header.
     */
    suspend fun downloadApk(
        downloadUrl: String,
        version: String,
        updatesDir: File,
        userAgentVersion: String,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit
    ): File = withContext(Dispatchers.IO) {
        if (!updatesDir.exists() && !updatesDir.mkdirs()) {
            throw IOException("Failed to create update cache directory")
        }
        // Clean up any older cached APKs / abandoned partial downloads before fetching the new one
        updatesDir.listFiles()?.forEach { old ->
            if (old.isFile) old.delete()
        }

        val safeVer = normalizeVersion(version).replace(Regex("[^A-Za-z0-9._-]"), "_")
        val targetApk = File(updatesDir, "AutoDora-NEXT-v$safeVer.apk")
        val tempFile = File(updatesDir, "AutoDora-NEXT-v$safeVer.apk.part")

        var conn: HttpURLConnection? = null
        try {
            conn = openFollowingRedirects(downloadUrl, userAgentVersion)
            val totalBytes = conn.contentLengthLong
            onProgress(0L, totalBytes)

            val buf = ByteArray(16 * 1024)
            var downloaded = 0L
            var lastEmitMs = 0L

            conn.inputStream.use { input ->
                FileOutputStream(tempFile).use { output ->
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buf)
                        if (read < 0) break
                        if (read == 0) continue
                        output.write(buf, 0, read)
                        downloaded += read
                        val now = System.currentTimeMillis()
                        if (now - lastEmitMs >= 100L || (totalBytes > 0L && downloaded >= totalBytes)) {
                            lastEmitMs = now
                            onProgress(downloaded, totalBytes)
                        }
                    }
                    output.flush()
                }
            }

            if (totalBytes > 0L && downloaded < totalBytes) {
                throw IOException("Incomplete download ($downloaded / $totalBytes bytes)")
            }
            if (!hasZipApkHeader(tempFile)) {
                throw IOException("Downloaded file is not a valid APK package")
            }
            if (targetApk.exists()) targetApk.delete()
            if (!tempFile.renameTo(targetApk)) {
                tempFile.copyTo(targetApk, overwrite = true)
                tempFile.delete()
            }
            onProgress(downloaded, if (totalBytes > 0L) totalBytes else downloaded)
            targetApk
        } catch (e: Throwable) {
            if (tempFile.exists()) tempFile.delete()
            if (targetApk.exists()) targetApk.delete()
            throw e
        } finally {
            conn?.disconnect()
        }
    }

    private fun openFollowingRedirects(initialUrl: String, userAgentVersion: String): HttpURLConnection {
        var currentUrl = initialUrl
        for (hop in 0..MAX_REDIRECTS) {
            val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                instanceFollowRedirects = false
                connectTimeout = 15_000
                readTimeout = 30_000
                setRequestProperty("Accept", "application/octet-stream, */*")
                setRequestProperty("User-Agent", "AutoDora-NEXT/$userAgentVersion")
            }
            val code = conn.responseCode
            if (code in setOf(301, 302, 303, 307, 308)) {
                val location = conn.getHeaderField("Location")
                conn.disconnect()
                if (location.isNullOrBlank()) {
                    throw IOException("Redirect HTTP $code missing Location header")
                }
                currentUrl = URL(URL(currentUrl), location).toString()
                continue
            }
            if (code !in 200..299) {
                conn.disconnect()
                throw IOException("HTTP $code")
            }
            return conn
        }
        throw IOException("Too many redirects while downloading APK")
    }

    /**
     * Checks that [file] starts with standard ZIP/APK magic bytes (`PK\x03\x04`).
     */
    fun hasZipApkHeader(file: File): Boolean {
        if (!file.isFile || file.length() < 4L) return false
        val header = ByteArray(4)
        file.inputStream().use { input ->
            if (input.read(header) < 4) return false
        }
        return header[0] == 0x50.toByte() &&
            header[1] == 0x4B.toByte() &&
            header[2] == 0x03.toByte() &&
            header[3] == 0x04.toByte()
    }

    fun formatBytesMb(bytes: Long): String {
        val mb = bytes.coerceAtLeast(0L) / (1024.0 * 1024.0)
        return String.format(java.util.Locale.US, "%.1f MB", mb)
    }

    fun normalizeVersion(raw: String): String =
        raw.trim().removePrefix("v").removePrefix("V")

    /**
     * Compares two semantic version strings (`v0.3.4` vs `0.3.3`).
     * Returns > 0 if [a] > [b], 0 if equal, < 0 if [a] < [b].
     */
    fun compareSemVer(a: String, b: String): Int {
        val pa = parseParts(a)
        val pb = parseParts(b)
        val n = maxOf(pa.size, pb.size)
        for (i in 0 until n) {
            val va = pa.getOrElse(i) { 0 }
            val vb = pb.getOrElse(i) { 0 }
            if (va != vb) return va.compareTo(vb)
        }
        return 0
    }

    private fun parseParts(raw: String): List<Int> {
        val core = normalizeVersion(raw).substringBefore('-').substringBefore('+')
        return core.split('.').map { part ->
            part.filter { it.isDigit() }.toIntOrNull() ?: 0
        }
    }
}
