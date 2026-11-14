package dev.gramhub.remote

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/** Severity of a single observation from the full check. */
enum class CheckLevel { INFO, WARNING, DANGER }

/** One thing the full check noticed about the file. */
data class CheckNote(
    val level: CheckLevel,
    val title: String,
    val detail: String = "",
)

/**
 * The result of a full on-device verification of a downloaded APK.
 *
 * The point of this check is that it is done here, on the bytes that are actually
 * going to be installed, rather than trusting a verdict the server produced for
 * possibly different bytes. It never claims the file is safe - that is not
 * decidable from the outside - but it does answer three concrete questions: is
 * the archive intact, is it signed, and does the code carry anything a chat app
 * has no business carrying.
 */
data class FullCheckReport(
    val digest: String,
    val fileSize: Long,
    val entriesTotal: Int,
    val entriesVerified: Int,
    val damagedEntries: Int,
    val dexFiles: Int,
    val dexClasses: Int,
    val nativeLibs: Int,
    val assets: Int,
    val signed: Boolean,
    val signers: List<String>,
    val packageName: String?,
    val versionName: String?,
    val notes: List<CheckNote>,
) {
    /** The overall level, which the UI maps to a verdict colour. */
    val level: CheckLevel
        get() = when {
            notes.any { it.level == CheckLevel.DANGER } -> CheckLevel.DANGER
            !signed -> CheckLevel.DANGER
            damagedEntries > 0 -> CheckLevel.DANGER
            notes.any { it.level == CheckLevel.WARNING } -> CheckLevel.WARNING
            else -> CheckLevel.INFO
        }
}

/** Progress of a full check: a fraction in 0..1 and a human-readable step. */
data class FullCheckProgress(val fraction: Float, val step: String)

sealed interface FullCheckOutcome {
    data class Completed(val report: FullCheckReport) : FullCheckOutcome
    data class Failed(val reason: String) : FullCheckOutcome
}

/**
 * Reads every entry of a downloaded APK and reports what it finds.
 *
 * ZipFile already validates each entry's CRC while the stream is drained, so
 * reading the whole archive is the integrity check; a truncated or edited file
 * throws a ZipException instead of returning bytes.
 */
class ApkFullCheck(private val context: Context) {

    suspend fun verify(
        file: File,
        expectedSha256: String?,
        onProgress: (FullCheckProgress) -> Unit = {},
    ): FullCheckOutcome = withContext(Dispatchers.IO) {
        if (!file.exists() || file.length() == 0L) {
            return@withContext FullCheckOutcome.Failed("file_missing")
        }

        val notes = mutableListOf<CheckNote>()
        var entriesTotal = 0
        var entriesVerified = 0
        var damaged = 0
        var dexFiles = 0
        var dexClasses = 0
        var nativeLibs = 0
        var assets = 0
        val suspicious = linkedSetOf<String>()

        val digest = try {
            sha256(file)
        } catch (error: Exception) {
            return@withContext FullCheckOutcome.Failed(error.message ?: "unreadable")
        }

        if (expectedSha256 != null) {
            if (digest.equals(expectedSha256, ignoreCase = true)) {
                notes += CheckNote(CheckLevel.INFO, "digest", "SHA-256 matches the release")
            } else {
                notes += CheckNote(
                    CheckLevel.DANGER,
                    "digest",
                    "SHA-256 does not match the release: expected $expectedSha256, got $digest",
                )
            }
        }

        try {
            ZipFile(file).use { zip ->
                val entries = zip.entries().toList()
                entriesTotal = entries.size

                entries.forEachIndexed { index, entry ->
                    if (!entry.isDirectory) {
                        val intact = drainEntry(zip, entry)
                        if (intact) entriesVerified++ else damaged++

                        when {
                            entry.name.endsWith(".dex", ignoreCase = true) -> {
                                dexFiles++
                                dexClasses += readDexClassCount(zip, entry)
                                suspicious += scanForSuspiciousStrings(zip, entry)
                            }

                            entry.name.startsWith("lib/") && entry.name.endsWith(".so") -> nativeLibs++

                            entry.name.startsWith("assets/") -> assets++

                            entry.name.startsWith("META-INF/") &&
                                entry.name.endsWith(".SF", ignoreCase = true) -> {
                                notes += CheckNote(CheckLevel.INFO, "manifest", "signature manifest present")
                            }
                        }
                    }

                    if (index % 32 == 0) {
                        onProgress(
                            FullCheckProgress(
                                fraction = (index + 1).toFloat() / entries.size.coerceAtLeast(1),
                                step = entry.name,
                            ),
                        )
                    }
                }
            }
        } catch (error: Exception) {
            return@withContext FullCheckOutcome.Failed(error.message ?: "not_a_zip")
        }

        onProgress(FullCheckProgress(1f, "signature"))

        val archive = readArchiveInfo(file)
        val signers = archive?.signers.orEmpty()

        if (signers.isEmpty()) {
            notes += CheckNote(CheckLevel.DANGER, "signature", "the APK is not signed")
        } else {
            notes += CheckNote(
                CheckLevel.INFO,
                "signature",
                signers.joinToString(", ") { "SHA-256 ${it.take(16)}…" },
            )
        }

        if (damaged > 0) {
            notes += CheckNote(CheckLevel.DANGER, "integrity", "$damaged damaged entries")
        } else {
            notes += CheckNote(CheckLevel.INFO, "integrity", "all $entriesVerified entries are intact")
        }

        if (dexClasses == 0 && dexFiles == 0) {
            notes += CheckNote(CheckLevel.WARNING, "code", "the APK contains no code at all")
        }

        suspicious.forEach { hit ->
            val level = if (hit in CRITICAL_INDICATORS) CheckLevel.DANGER else CheckLevel.WARNING
            notes += CheckNote(level, "code", hit)
        }

        FullCheckOutcome.Completed(
            FullCheckReport(
                digest = digest,
                fileSize = file.length(),
                entriesTotal = entriesTotal,
                entriesVerified = entriesVerified,
                damagedEntries = damaged,
                dexFiles = dexFiles,
                dexClasses = dexClasses,
                nativeLibs = nativeLibs,
                assets = assets,
                signed = signers.isNotEmpty(),
                signers = signers,
                packageName = archive?.packageName,
                versionName = archive?.versionName,
                notes = notes,
            ),
        )
    }

    /**
     * Reads an entry to the end and checks it against its stored CRC-32.
     *
     * The CRC is computed here rather than relied on implicitly: the JVM's
     * `ZipFile` streams do not verify it, and neither can a caller assume every
     * Android release does. Reading the whole entry is also what makes a truncated
     * file fail loudly instead of returning a short buffer.
     */
    private fun drainEntry(zip: ZipFile, entry: ZipEntry): Boolean = try {
        val crc = CRC32()
        zip.getInputStream(entry).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                crc.update(buffer, 0, read)
            }
        }
        val expected = entry.crc
        expected < 0 || crc.value == expected
    } catch (_: Exception) {
        false
    }

    private fun readDexClassCount(zip: ZipFile, entry: ZipEntry): Int = try {
        zip.getInputStream(entry).use { input ->
            val header = ByteArray(DEX_CLASS_DEFS_OFFSET + 4)
            var read = 0
            while (read < header.size) {
                val chunk = input.read(header, read, header.size - read)
                if (chunk <= 0) break
                read += chunk
            }
            if (read < header.size) 0 else leInt(header, DEX_CLASS_DEFS_OFFSET)
        }
    } catch (_: Exception) {
        0
    }

    /** Scans a dex file's raw bytes for strings that have no place in a chat app. */
    private fun scanForSuspiciousStrings(zip: ZipFile, entry: ZipEntry): Set<String> {
        val hits = linkedSetOf<String>()
        return try {
            zip.getInputStream(entry).use { input ->
                val buffer = ByteArray(512 * 1024)
                val carry = StringBuilder()
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    val text = String(buffer, 0, read, Charsets.ISO_8859_1)
                    val window = carry.toString() + text
                    INDICATORS.forEach { indicator ->
                        if (window.contains(indicator)) hits += indicator
                    }
                    // Keep a tail so an indicator split across two reads is still
                    // found.
                    carry.clear()
                    carry.append(window.takeLast(MAX_INDICATOR_LENGTH))
                }
            }
            hits
        } catch (_: Exception) {
            hits
        }
    }

    private data class ArchiveInfo(
        val signers: List<String>,
        val packageName: String?,
        val versionName: String?,
    )

    /**
     * Reads the package identity and signing certificates straight out of the
     * archive. [PackageManager.getPackageArchiveInfo] does not need the app to be
     * installed, which is exactly the case here.
     */
    @Suppress("DEPRECATION")
    private fun readArchiveInfo(file: File): ArchiveInfo? = runCatching {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        val info: PackageInfo = context.packageManager
            .getPackageArchiveInfo(file.absolutePath, flags)
            ?: return@runCatching null

        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners
        } else {
            info.signatures
        }.orEmpty()

        ArchiveInfo(
            signers = signatures.map { sha256(it.toByteArray()) },
            packageName = info.packageName,
            versionName = info.versionName,
        )
    }.getOrNull()

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().toHex()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    /** Little-endian int at [offset], or 0 when the buffer is too short. */
    private fun leInt(bytes: ByteArray, offset: Int): Int {
        if (offset + 4 > bytes.size) return 0
        return (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)
    }

    private companion object {
        /** `class_defs_size` sits at this offset in the DEX header. */
        const val DEX_CLASS_DEFS_OFFSET = 96

        const val MAX_INDICATOR_LENGTH = 40

        /** Strong signs that the code is doing something it should not. */
        val CRITICAL_INDICATORS = setOf(
            "/system/bin/su",
            "superuser",
            "Runtime;->exec",
            "ProcessBuilder",
            "DexClassLoader",
            "PathClassLoader",
        )

        /** Worth reporting, but not damning on its own. */
        val SOFT_INDICATORS = setOf(
            "Landroid/telephony/SmsManager;",
            "sendTextMessage",
            "BIND_ACCESSIBILITY_SERVICE",
            "AccessibilityService",
            "frida",
            "xposed",
            "LSPosed",
            "Magisk",
            "setJavaScriptEnabled",
            "addJavascriptInterface",
        )

        val INDICATORS: Set<String> = CRITICAL_INDICATORS + SOFT_INDICATORS
    }
}
