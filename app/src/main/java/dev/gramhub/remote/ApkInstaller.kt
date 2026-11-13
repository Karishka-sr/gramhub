package dev.gramhub.remote

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/** Outcome of preparing an APK for installation. */
sealed interface InstallOutcome {
    data class Ready(val file: File) : InstallOutcome

    /** The bytes did not match the digest the server reported. */
    data class DigestMismatch(val expected: String, val actual: String) : InstallOutcome

    data class Failed(val reason: String) : InstallOutcome

    /** The user must allow this app to install unknown apps before we can go on. */
    data object NeedsPermission : InstallOutcome
}

/**
 * Downloads a catalog APK and verifies it before handing it to the installer.
 *
 * The digest check is the important part: the server's security verdict is only
 * meaningful for the exact bytes it scanned, so the file is re-hashed here and
 * refused if it differs. The APK is stored in app-private storage and shared
 * through a [FileProvider], never written to public storage.
 */
class ApkInstaller(private val context: Context) {

    private val downloadsDir: File
        get() = File(context.filesDir, "downloads").apply { mkdirs() }

    fun fileFor(client: RemoteClient): File {
        val safe = client.id.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
        return File(downloadsDir, "${safe.ifBlank { "client" }}.apk")
    }

    /**
     * True when a previous download for this client is already on disk. It has to
     * look where [download] actually writes, which is the external files dir, not
     * [fileFor]'s private directory.
     */
    fun hasDownload(client: RemoteClient): Boolean =
        resolveDownloaded(fileFor(client).name)?.let { it.length() > 0 } == true

    /** True when this app is allowed to launch the package installer. */
    fun canInstallPackages(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }

    /** Opens the system screen where the user grants "install unknown apps". */
    fun requestInstallPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val intent = Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
            .setData(Uri.fromParts("package", context.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    /**
     * Downloads [apk] into private storage and verifies its digest.
     *
     * Uses [DownloadManager] so the transfer survives the app being backgrounded
     * and is handled by the system's downloader.
     *
     * The completion broadcast is only a hint: it can fire before the receiver is
     * registered, and the system drops it entirely for some transfers, which used
     * to leave the UI stuck on "Downloading" forever. The download is therefore
     * also polled, and whichever signal arrives first ends the flow.
     */
    fun download(apk: RemoteApk, target: File): Flow<DownloadProgress> = callbackFlow {
        val request = DownloadManager.Request(Uri.parse(apk.url))
            .setTitle(apk.name)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, target.name)
            .setMimeType("application/vnd.android.package-archive")

        val manager = context.getSystemService(DownloadManager::class.java)
        val id = runCatching { manager.enqueue(request) }.getOrElse { failure ->
            trySend(DownloadProgress.Failed(failure.message ?: "could not start the download"))
            close()
            return@callbackFlow
        }

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                val finishedId = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
                if (finishedId != id) return
                trySend(DownloadProgress.Finished(id))
                close()
            }
        }

        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        // Fallback for a missed broadcast: ask the manager for the terminal status.
        val poller = launch {
            while (isActive) {
                delay(1000)
                when (runCatching { manager.query(DownloadManager.Query().setFilterById(id)) }
                    .getOrNull()?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                        } else {
                            null
                        }
                    }) {
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        trySend(DownloadProgress.Finished(id))
                        close()
                        return@launch
                    }

                    DownloadManager.STATUS_FAILED -> {
                        trySend(DownloadProgress.Failed("the download failed"))
                        close()
                        return@launch
                    }

                    else -> Unit
                }
            }
        }

        awaitClose {
            runCatching { context.unregisterReceiver(receiver) }
            poller.cancel()
        }
    }.flowOn(Dispatchers.IO)

    /** Locates a file a [DownloadManager] job produced, under either storage root. */
    fun resolveDownloaded(name: String): File? {
        val candidates = listOf(
            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
            File(context.filesDir, "downloads"),
        )
        return candidates.filterNotNull()
            .map { File(it, name) }
            .firstOrNull { it.exists() && it.length() > 0 }
    }

    suspend fun verify(file: File, expectedSha256: String?): InstallOutcome =
        withContext(Dispatchers.IO) {
            if (!file.exists() || file.length() == 0L) {
                return@withContext InstallOutcome.Failed("the downloaded file is empty")
            }
            if (expectedSha256.isNullOrBlank()) {
                // No digest to check against: accept, but the caller has already
                // shown the user that the server did not publish one.
                return@withContext InstallOutcome.Ready(file)
            }

            val actual = sha256(file)
            if (actual.equals(expectedSha256, ignoreCase = true)) {
                InstallOutcome.Ready(file)
            } else {
                file.delete()
                InstallOutcome.DigestMismatch(expected = expectedSha256, actual = actual)
            }
        }

    /** Hands a verified APK to the system package installer. */
    fun promptInstall(file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

/** Progress of an APK download. */
sealed interface DownloadProgress {
    data class Finished(val downloadId: Long) : DownloadProgress
    data class Failed(val reason: String) : DownloadProgress
}
