package dev.gramhub.remote

import android.content.Context
import android.os.Environment
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The installer hands a downloaded APK to the system package installer through a
 * [FileProvider]. DownloadManager can only write to external storage, so the file
 * lives in the app's external files dir; if that directory is not declared in
 * `res/xml/file_paths.xml`, [FileProvider.getUriForFile] throws and the app
 * crashes right after verifying the download.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FileProviderPathsTest {

    @Test
    fun `the external downloads directory is shared`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val external = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)!!
        val file = File(external, "client.apk").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(1, 2, 3))
        }

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

        assertTrue(
            "expected a content:// uri for the external download, got $uri",
            uri.toString().startsWith("content://"),
        )
    }
}
