package com.autodora.next.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import kotlin.concurrent.thread

class UpdateCheckerTest {

    @Test
    fun normalizeVersionStripsLeadingVAndWhitespace() {
        assertEquals("0.3.3", UpdateChecker.normalizeVersion("v0.3.3"))
        assertEquals("1.0.0", UpdateChecker.normalizeVersion("  V1.0.0  "))
        assertEquals("0.4.1", UpdateChecker.normalizeVersion("0.4.1"))
    }

    @Test
    fun compareSemVerOrdersMajorMinorPatchCorrectly() {
        assertEquals(0, UpdateChecker.compareSemVer("v0.3.3", "0.3.3"))
        assertTrue(UpdateChecker.compareSemVer("v0.3.4", "0.3.3") > 0)
        assertTrue(UpdateChecker.compareSemVer("v0.4.0", "0.3.9") > 0)
        assertTrue(UpdateChecker.compareSemVer("v1.0.0", "0.9.9") > 0)
        assertTrue(UpdateChecker.compareSemVer("v0.3.10", "0.3.9") > 0)
        assertTrue(UpdateChecker.compareSemVer("0.3.2", "v0.3.3") < 0)
        assertEquals(0, UpdateChecker.compareSemVer("v0.3.3-beta.1", "0.3.3"))
    }

    @Test
    fun hasZipApkHeaderValidatesMagicBytes() {
        val dir = Files.createTempDirectory("autodora_hdr_test").toFile()
        try {
            val validApk = File(dir, "ok.apk").apply {
                writeBytes(byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x14, 0x00))
            }
            val htmlErr = File(dir, "bad.apk").apply {
                writeText("<!DOCTYPE html><html>404</html>")
            }
            assertTrue(UpdateChecker.hasZipApkHeader(validApk))
            assertFalse(UpdateChecker.hasZipApkHeader(htmlErr))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun downloadApkFollowsRedirectReportsProgressAndCleansOldCache() = runBlocking {
        // Minimal valid ZIP/APK payload (64 KB) starting with PK\x03\x04
        val payload = ByteArray(64 * 1024) { idx ->
            when (idx) {
                0 -> 0x50
                1 -> 0x4B
                2 -> 0x03
                3 -> 0x04
                else -> (idx and 0xFF).toByte()
            }
        }
        val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val serverThread = thread(isDaemon = true) {
            repeat(2) {
                try {
                    server.accept().use { sock ->
                        val reader = BufferedReader(InputStreamReader(sock.getInputStream()))
                        val reqLine = reader.readLine() ?: return@use
                        while (true) {
                            val h = reader.readLine()
                            if (h.isNullOrEmpty()) break
                        }
                        val out = sock.getOutputStream()
                        if (reqLine.contains("/release.apk")) {
                            val resp = "HTTP/1.1 302 Found\r\n" +
                                "Location: /cdn/AutoDora-NEXT-v0.4.2-release.apk\r\n" +
                                "Content-Length: 0\r\n" +
                                "Connection: close\r\n\r\n"
                            out.write(resp.toByteArray(Charsets.US_ASCII))
                            out.flush()
                        } else {
                            val hdr = "HTTP/1.1 200 OK\r\n" +
                                "Content-Type: application/vnd.android.package-archive\r\n" +
                                "Content-Length: ${payload.size}\r\n" +
                                "Connection: close\r\n\r\n"
                            out.write(hdr.toByteArray(Charsets.US_ASCII))
                            out.write(payload)
                            out.flush()
                        }
                    }
                } catch (_: Exception) {
                }
            }
        }

        val cacheDir = Files.createTempDirectory("autodora_dl_test").toFile()
        val staleOldApk = File(cacheDir, "AutoDora-NEXT-v0.3.3.apk").apply { writeText("stale") }
        try {
            val port = server.localPort
            var lastDown = -1L
            var lastTotal = -1L
            val apk = UpdateChecker.downloadApk(
                downloadUrl = "http://127.0.0.1:$port/release.apk",
                version = "v0.4.2",
                updatesDir = cacheDir,
                userAgentVersion = "0.4.1"
            ) { down, total ->
                lastDown = down
                lastTotal = total
            }

            assertFalse("Stale older cached APK must be cleaned up", staleOldApk.exists())
            assertTrue("Downloaded APK file must exist", apk.exists())
            assertEquals("AutoDora-NEXT-v0.4.2.apk", apk.name)
            assertEquals(payload.size.toLong(), apk.length())
            assertEquals(payload.size.toLong(), lastDown)
            assertEquals(payload.size.toLong(), lastTotal)
            assertTrue(UpdateChecker.hasZipApkHeader(apk))
        } finally {
            server.close()
            serverThread.join(1_000)
            cacheDir.deleteRecursively()
        }
    }

    @Test
    fun parseLatestFromAtomFeedPicksHighestSemVerAndCleansHtmlNotes() {
        val atomXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <feed xmlns="http://www.w3.org/2005/Atom">
              <entry>
                <id>tag:github.com,2008:Repository/123/v0.4.4</id>
                <link rel="alternate" type="text/html" href="https://github.com/CloverKing/AutoDora-NEXT/releases/tag/v0.4.4"/>
                <title>AutoDora-NEXT v0.4.4</title>
                <content type="html">&lt;p&gt;Old release&lt;/p&gt;</content>
              </entry>
              <entry>
                <id>tag:github.com,2008:Repository/123/v0.4.6</id>
                <link rel="alternate" type="text/html" href="https://github.com/CloverKing/AutoDora-NEXT/releases/tag/v0.4.6"/>
                <title>AutoDora-NEXT v0.4.6</title>
                <content type="html">&lt;h2&gt;更新说明&lt;/h2&gt;&lt;ul&gt;&lt;li&gt;修复 403 限流&lt;/li&gt;&lt;/ul&gt;</content>
              </entry>
            </feed>
        """.trimIndent()

        val entry = UpdateChecker.parseLatestFromAtomFeed(atomXml)
        assertTrue(entry != null)
        assertEquals("v0.4.6", entry!!.tagName)
        assertEquals("https://github.com/CloverKing/AutoDora-NEXT/releases/tag/v0.4.6", entry.releaseUrl)
        assertTrue(entry.isPrerelease)
        assertTrue(entry.releaseNotes.contains("修复 403 限流"))
        assertFalse(entry.releaseNotes.contains("<li>"))
    }

    @Test
    fun parseFirstApkAssetUrlResolvesRelativeAndAbsoluteLinks() {
        val relHtml = """
            <ul>
              <li><a href="/CloverKing/AutoDora-NEXT/archive/refs/tags/v0.4.5.zip">Source</a></li>
              <li><a href="/CloverKing/AutoDora-NEXT/releases/download/v0.4.5/AutoDora-NEXT-v0.4.5-release.apk">APK</a></li>
            </ul>
        """.trimIndent()
        assertEquals(
            "https://github.com/CloverKing/AutoDora-NEXT/releases/download/v0.4.5/AutoDora-NEXT-v0.4.5-release.apk",
            UpdateChecker.parseFirstApkAssetUrl(relHtml)
        )
    }

    @Test
    fun checkLatestReleaseFallsBackToAtomAndExpandedAssetsWhenApiReturns403() = runBlocking {
        val atomBody = """
            <?xml version="1.0" encoding="UTF-8"?>
            <feed xmlns="http://www.w3.org/2005/Atom">
              <entry>
                <id>tag:github.com,2008:Repository/123/v0.4.6</id>
                <link rel="alternate" type="text/html" href="https://github.com/CloverKing/AutoDora-NEXT/releases/tag/v0.4.6"/>
                <title>AutoDora-NEXT v0.4.6</title>
                <content type="html">&lt;p&gt;免限流 Atom 通道校验成功&lt;/p&gt;</content>
              </entry>
            </feed>
        """.trimIndent().toByteArray(Charsets.UTF_8)
        val assetsBody = """
            <a href="/CloverKing/AutoDora-NEXT/releases/download/v0.4.6/AutoDora-NEXT-v0.4.6-release.apk">APK</a>
        """.trimIndent().toByteArray(Charsets.UTF_8)

        val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val serverThread = thread(isDaemon = true) {
            repeat(3) {
                try {
                    server.accept().use { sock ->
                        val reader = BufferedReader(InputStreamReader(sock.getInputStream()))
                        val reqLine = reader.readLine() ?: return@use
                        while (true) {
                            val h = reader.readLine()
                            if (h.isNullOrEmpty()) break
                        }
                        val out = sock.getOutputStream()
                        when {
                            reqLine.contains("/api/releases") -> {
                                val err = "{\"message\":\"API rate limit exceeded\"}".toByteArray(Charsets.UTF_8)
                                val hdr = "HTTP/1.1 403 Forbidden\r\nX-RateLimit-Remaining: 0\r\nContent-Length: ${err.size}\r\nConnection: close\r\n\r\n"
                                out.write(hdr.toByteArray(Charsets.US_ASCII))
                                out.write(err)
                            }
                            reqLine.contains("/releases.atom") -> {
                                val hdr = "HTTP/1.1 200 OK\r\nContent-Type: application/atom+xml; charset=utf-8\r\nContent-Length: ${atomBody.size}\r\nConnection: close\r\n\r\n"
                                out.write(hdr.toByteArray(Charsets.US_ASCII))
                                out.write(atomBody)
                            }
                            else -> {
                                val hdr = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${assetsBody.size}\r\nConnection: close\r\n\r\n"
                                out.write(hdr.toByteArray(Charsets.US_ASCII))
                                out.write(assetsBody)
                            }
                        }
                        out.flush()
                    }
                } catch (_: Exception) {
                }
            }
        }

        try {
            val port = server.localPort
            val state = UpdateChecker.checkLatestReleaseInternal(
                currentVersion = "0.4.5",
                apiUrl = "http://127.0.0.1:$port/api/releases",
                atomUrl = "http://127.0.0.1:$port/releases.atom",
                expandedAssetsUrlForTag = { tag -> "http://127.0.0.1:$port/expanded_assets/$tag" },
                baseOrigin = "https://github.com"
            )
            assertTrue("Expected UpdateAvailable after 403 fallback, got $state", state is UpdateCheckState.UpdateAvailable)
            val avail = state as UpdateCheckState.UpdateAvailable
            assertEquals("0.4.6", avail.latestVersion)
            assertEquals(
                "https://github.com/CloverKing/AutoDora-NEXT/releases/download/v0.4.6/AutoDora-NEXT-v0.4.6-release.apk",
                avail.apkDownloadUrl
            )
            assertTrue(avail.releaseNotes.contains("免限流 Atom 通道校验成功"))
        } finally {
            server.close()
            serverThread.join(1_000)
        }
    }
}
