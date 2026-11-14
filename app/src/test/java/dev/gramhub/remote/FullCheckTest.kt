package dev.gramhub.remote

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Tests the on-device full check against real ZIP archives.
 *
 * The check exists to inspect the exact bytes that would be installed, so it is
 * exercised on files it has to handle: an intact archive, an entry whose bytes no
 * longer match its CRC, and code carrying a known-bad indicator. The signature
 * path needs a genuinely signed APK, which is why it is covered by the server
 * scanner tests instead.
 */
@RunWith(AndroidJUnit4::class)
class FullCheckTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val check = ApkFullCheck(ApplicationProvider.getApplicationContext())

    @Test
    fun `reports dex entries and their class counts`() = runBlocking {
        val file = folder.newFile("good.apk")
        zip(file) {
            entry("classes.dex", dex(classes = 7))
            entry("resources.arsc", byteArrayOf(1, 2, 3, 4))
            entry("lib/arm64-v8a/libfoo.so", byteArrayOf(9, 9))
            entry("assets/emoji.dat", byteArrayOf(0))
        }

        val report = (check.verify(file, null) as FullCheckOutcome.Completed).report

        assertEquals(4, report.entriesTotal)
        assertEquals(4, report.entriesVerified)
        assertEquals(0, report.damagedEntries)
        assertEquals(1, report.dexFiles)
        assertEquals(7, report.dexClasses)
        assertEquals(1, report.nativeLibs)
        assertEquals(1, report.assets)
        // No real signing block on a hand-built ZIP, so the check must say so
        // rather than pretend the file is signed.
        assertEquals(false, report.signed)
    }

    @Test
    fun `flags a digest that does not match the release`() = runBlocking {
        val file = folder.newFile("digest.apk")
        zip(file) { entry("classes.dex", dex(classes = 1)) }

        val report = (check.verify(file, "deadbeef") as FullCheckOutcome.Completed).report

        val digestNote = report.notes.first { it.title == "digest" }
        assertEquals(CheckLevel.DANGER, digestNote.level)
    }

    @Test
    fun `flags code carrying a known bad indicator`() = runBlocking {
        val file = folder.newFile("bad.apk")
        zip(file) {
            entry("classes.dex", dex(classes = 2, extra = "/system/bin/su".toByteArray()))
        }

        val report = (check.verify(file, null) as FullCheckOutcome.Completed).report

        assertTrue(
            "the indicator must surface as a danger",
            report.notes.any { it.level == CheckLevel.DANGER && it.detail.contains("su") },
        )
    }

    @Test
    fun `detects a corrupted entry`() = runBlocking {
        val file = folder.newFile("corrupt.apk")
        // Random, incompressible bytes so the payload survives deflate almost
        // verbatim and can be located in the finished file.
        val payload = ByteArray(64 * 1024).also { java.util.Random(7).nextBytes(it) }

        zip(file) {
            entry("classes.dex", dex(classes = 1))
            entry("payload.bin", payload)
        }

        // Flip a byte inside the payload so its CRC no longer matches.
        corruptInside(file, payload.copyOfRange(0, 16))

        val outcome = check.verify(file, null)
        val report = (outcome as FullCheckOutcome.Completed).report

        assertTrue("a damaged entry must be counted", report.damagedEntries > 0)
        assertEquals(CheckLevel.DANGER, report.level)
    }

    @Test
    fun `a missing file fails cleanly`() = runBlocking {
        val outcome = check.verify(File(folder.root, "nope.apk"), null)
        assertEquals(FullCheckOutcome.Failed("file_missing"), outcome)
    }

    // ------------------------------------------------------------------ helpers

    /** Builds a minimal DEX header with [classes] declared class definitions. */
    private fun dex(classes: Int, extra: ByteArray = ByteArray(0)): ByteArray {
        val header = ByteArray(112)
        "dex\n035\u0000".toByteArray(Charsets.ISO_8859_1).copyInto(header)
        // class_defs_size lives at offset 96, little-endian.
        header[96] = (classes and 0xFF).toByte()
        header[97] = ((classes shr 8) and 0xFF).toByte()
        return header + extra
    }

    private fun zip(target: File, block: ZipBuilder.() -> Unit) {
        ZipOutputStream(target.outputStream()).use { out ->
            ZipBuilder(out).block()
        }
    }

    /** Flips a byte at the first occurrence of [marker] in the file. */
    private fun corruptInside(file: File, marker: ByteArray) {
        val bytes = file.readBytes()
        val index = indexOf(bytes, marker)
        if (index < 0) return
        val target = index + marker.size - 1
        bytes[target] = (bytes[target].toInt() xor 0xFF).toByte()
        file.writeBytes(bytes)
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }

    private class ZipBuilder(private val out: ZipOutputStream) {
        fun entry(name: String, bytes: ByteArray) {
            out.putNextEntry(ZipEntry(name))
            out.write(bytes)
            out.closeEntry()
        }
    }
}
