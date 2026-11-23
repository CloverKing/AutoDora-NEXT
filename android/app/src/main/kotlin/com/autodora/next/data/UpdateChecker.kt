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
 *
 * Dual-channel check flow (immune to shared-IP / VPN `api.github.com` 60 req/hr HTTP 403 rate limits):
 *  1. Primary: `https://api.github.com/repos/CloverKing/AutoDora-NEXT/releases?per_page=8` (rich JSON).
 *  2. Automatic Fallback on HTTP 403 / 429 or API error:
 *     - `https://github.com/CloverKing/AutoDora-NEXT/releases.atom` (served by github.com, zero API rate limit)
 *     - `https://github.com/CloverKing/AutoDora-NEXT/releases/expanded_assets/<tag>` to resolve the exact `.apk` asset URL.
 */
object UpdateChecker {
    const val GITHUB_REPO_URL = "https://github.com/CloverKing/AutoDora-NEXT"
    private const val GITHUB_ORIGIN = "https://github.com"
    private const val GITHUB_RELEASES_API = "https://api.github.com/repos/CloverKing/AutoDora-NEXT/releases?per_page=8"
    private const val GITHUB_RELEASES_ATOM = "https://github.com/CloverKing/AutoDora-NEXT/releases.atom"
    private const val GITHUB_EXPANDED_ASSETS_FMT = "https://github.com/CloverKing/AutoDora-NEXT/releases/expanded_assets/%s"
    private const val MAX_REDIRECTS = 6

    internal data class AtomReleaseEntry(
        val tagName: String,
        val releaseUrl: String,
        val releaseNotes: String,
        val isPrerelease: Boolean
    )

    suspend fun checkLatestRelease(currentVersion: String): UpdateCheckState =
        checkLatestReleaseInternal(
            currentVersion = currentVersion,
            apiUrl = GITHUB_RELEASES_API,
            atomUrl = GITHUB_RELEASES_ATOM,
            expandedAssetsUrlForTag = { tag -> String.format(java.util.Locale.US, GITHUB_EXPANDED_ASSETS_FMT, tag) },
            baseOrigin = GITHUB_ORIGIN
        )

    internal suspend fun checkLatestReleaseInternal(
        currentVersion: String,
        apiUrl: String,
        atomUrl: String,
        expandedAssetsUrlForTag: (String) -> String,
        baseOrigin: String
    ): UpdateCheckState = withContext(Dispatchers.IO) {
        val apiResult = fetchFromReleasesApi(currentVersion, apiUrl)
        if (apiResult !is UpdateCheckState.Error) {
            return@withContext apiResult
        }
        // Seamless zero-rate-limit fallback via github.com Atom feed + expanded_assets fragment
        val atomResult = fetchFromAtomFeed(
            currentVersion = currentVersion,
            atomUrl = atomUrl,
            expandedAssetsUrlForTag = expandedAssetsUrlForTag,
            baseOrigin = baseOrigin
        )
        if (atomResult !is UpdateCheckState.Error) {
            return@withContext atomResult
        }
        apiResult
    }

    private fun fetchFromReleasesApi(currentVersion: String, apiUrl: String): UpdateCheckState {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8_000
                readTimeout = 8_000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "AutoDora-NEXT/$currentVersion")
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                return UpdateCheckState.Error("HTTP $code")
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

    private fun fetchFromAtomFeed(
        currentVersion: String,
        atomUrl: String,
        expandedAssetsUrlForTag: (String) -> String,
        baseOrigin: String
    ): UpdateCheckState {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(atomUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8_000
                readTimeout = 8_000
                setRequestProperty("Accept", "application/atom+xml, application/xml, text/xml")
                setRequestProperty("User-Agent", "AutoDora-NEXT/$currentVersion")
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                return UpdateCheckState.Error("Atom HTTP $code")
            }
            val xml = conn.inputStream.bufferedReader().use { it.readText() }
            val latest = parseLatestFromAtomFeed(xml)
                ?: return UpdateCheckState.UpToDate(normalizeVersion(currentVersion))

            if (compareSemVer(latest.tagName, currentVersion) <= 0) {
                return UpdateCheckState.UpToDate(normalizeVersion(currentVersion))
            }

            val resolvedApkUrl = fetchApkUrlFromExpandedAssets(
                assetsUrl = expandedAssetsUrlForTag(latest.tagName),
                userAgentVersion = currentVersion,
                baseOrigin = baseOrigin
            ) ?: buildCanonicalApkDownloadUrl(latest.tagName)

            UpdateCheckState.UpdateAvailable(
                latestVersion = normalizeVersion(latest.tagName),
                releaseUrl = latest.releaseUrl,
                apkDownloadUrl = resolvedApkUrl,
                releaseNotes = latest.releaseNotes,
                isPrerelease = latest.isPrerelease
            )
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            UpdateCheckState.Error(e.localizedMessage ?: e.javaClass.simpleName)
        } finally {
            conn?.disconnect()
        }
    }

    private fun fetchApkUrlFromExpandedAssets(
        assetsUrl: String,
        userAgentVersion: String,
        baseOrigin: String
    ): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(assetsUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8_000
                readTimeout = 8_000
                setRequestProperty("Accept", "text/html, */*")
                setRequestProperty("User-Agent", "AutoDora-NEXT/$userAgentVersion")
            }
            if (conn.responseCode !in 200..299) return null
            val html = conn.inputStream.bufferedReader().use { it.readText() }
            parseFirstApkAssetUrl(html, baseOrigin)
        } catch (_: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    internal fun parseLatestFromAtomFeed(xml: String): AtomReleaseEntry? {
        val entryRegex = Regex("<entry\\b[^>]*>(.*?)</entry>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        val linkRegex = Regex("<link\\b[^>]*href=\"([^\"]*/releases/tag/([^\"/?#]+))\"", RegexOption.IGNORE_CASE)
        val idTagRegex = Regex("<id>[^<]*?/([^/<]+)</id>", RegexOption.IGNORE_CASE)
        val contentRegex = Regex("<content\\b[^>]*>(.*?)</content>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))

        var best: AtomReleaseEntry? = null
        for (match in entryRegex.findAll(xml)) {
            val block = match.groupValues[1]
            val linkMatch = linkRegex.find(block)
            val tag = (linkMatch?.groupValues?.getOrNull(2) ?: idTagRegex.find(block)?.groupValues?.getOrNull(1))?.trim()
                ?: continue
            if (tag.isEmpty()) continue
            val url = linkMatch?.groupValues?.getOrNull(1)?.ifBlank { "$GITHUB_REPO_URL/releases/tag/$tag" }
                ?: "$GITHUB_REPO_URL/releases/tag/$tag"
            if (best == null || compareSemVer(tag, best.tagName) > 0) {
                val rawContent = contentRegex.find(block)?.groupValues?.getOrNull(1).orEmpty()
                val cleanNotes = stripHtmlNotes(unescapeXml(rawContent))
                val major = parseParts(tag).firstOrNull() ?: 0
                val isPre = major == 0 || tag.contains('-')
                best = AtomReleaseEntry(
                    tagName = tag,
                    releaseUrl = url,
                    releaseNotes = cleanNotes,
                    isPrerelease = isPre
                )
            }
        }
        return best
    }

    internal fun parseFirstApkAssetUrl(html: String, baseOrigin: String = GITHUB_ORIGIN): String? {
        val hrefRegex = Regex("href=\"([^\"]+\\.apk)\"", RegexOption.IGNORE_CASE)
        val raw = hrefRegex.find(html)?.groupValues?.getOrNull(1)?.trim() ?: return null
        if (raw.startsWith("http://", ignoreCase = true) || raw.startsWith("https://", ignoreCase = true)) {
            return raw
        }
        val origin = baseOrigin.trimEnd('/')
        return if (raw.startsWith("/")) "$origin$raw" else "$origin/$raw"
    }

    internal fun buildCanonicalApkDownloadUrl(tagName: String): String {
        val norm = normalizeVersion(tagName)
        return "$GITHUB_REPO_URL/releases/download/$tagName/AutoDora-NEXT-v$norm-release.apk"
    }

    private fun unescapeXml(raw: String): String = raw
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace("&amp;", "&")

    private fun stripHtmlNotes(html: String): String {
        if (html.isBlank()) return ""
        val withBreaks = html
            .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("</(p|li|h[1-6]|div|ul|ol)>", RegexOption.IGNORE_CASE), "\n")
        val noTags = withBreaks.replace(Regex("<[^>]+>"), "")
        return noTags
            .lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
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
