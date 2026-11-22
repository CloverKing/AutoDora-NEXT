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
}
