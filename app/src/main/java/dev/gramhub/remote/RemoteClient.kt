package dev.gramhub.remote

/**
 * A client the catalog server knows about.
 *
 * These types mirror the JSON the server returns. They are kept separate from
 * [dev.gramhub.data.TelegramClient], which describes an app that is actually
 * installed: a remote entry has no icon and no install time until its APK has
 * been downloaded and installed.
 */
data class RemoteClient(
    val id: String,
    val packageName: String?,
    val label: String,
    val repo: String,
    val repoUrl: String,
    val description: String,
    val stars: Int,
    val latestVersion: String?,
    val latestReleaseUrl: String?,
    val apk: RemoteApk?,
    val scan: RemoteScan?,
    val archived: Boolean,
)

/** The APK the server picked as the best build to install. */
data class RemoteApk(
    val name: String,
    val url: String,
    val size: Long,
    val sha256: String?,
    val abi: String?,
) {
    /** Human-readable size, e.g. "71.2 MB". */
    fun formattedSize(): String {
        if (size <= 0) return ""
        val mb = size / 1024.0 / 1024.0
        return if (mb >= 1) {
            String.format(java.util.Locale.US, "%.1f MB", mb)
        } else {
            String.format(java.util.Locale.US, "%.0f KB", size / 1024.0)
        }
    }
}

/** The server's security verdict for an APK. */
data class RemoteScan(
    val verdict: Verdict,
    val digestVerified: Boolean?,
    val debugSigned: Boolean,
    val signers: List<String>,
    val unexpectedPermissions: List<String>,
    val findings: List<RemoteFinding>,
)

data class RemoteFinding(
    val check: String,
    val severity: Severity,
    val summary: String,
    val detail: String,
)

/** How safe the server judged an APK to be. */
enum class Verdict {
    TRUSTED,
    CAUTION,
    DANGEROUS,
    UNKNOWN;

    companion object {
        fun from(raw: String?): Verdict = when (raw?.lowercase()) {
            "trusted" -> TRUSTED
            "caution" -> CAUTION
            "dangerous" -> DANGEROUS
            else -> UNKNOWN
        }
    }
}

enum class Severity {
    INFO,
    LOW,
    MEDIUM,
    HIGH;

    companion object {
        fun from(raw: String?): Severity = when (raw?.lowercase()) {
            "info" -> INFO
            "low" -> LOW
            "medium" -> MEDIUM
            "high" -> HIGH
            else -> INFO
        }
    }
}
