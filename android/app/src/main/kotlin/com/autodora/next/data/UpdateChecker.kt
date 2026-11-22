package com.autodora.next.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
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
    data class Error(val detail: String) : UpdateCheckState()
}

/**
 * Lightweight GitHub Releases update checker (zero third-party HTTP dependencies).
 * Queries `/releases?per_page=5` so both pre-1.0 prereleases (v0.x.y) and stable releases (v1.x.y)
 * are discovered reliably.
 */
object UpdateChecker {
    const val GITHUB_REPO_URL = "https://github.com/CloverKing/AutoDora-NEXT"
    private const val GITHUB_RELEASES_API = "https://api.github.com/repos/CloverKing/AutoDora-NEXT/releases?per_page=8"

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
            UpdateCheckState.Error(e.localizedMessage ?: e.javaClass.simpleName)
        } finally {
            conn?.disconnect()
        }
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
