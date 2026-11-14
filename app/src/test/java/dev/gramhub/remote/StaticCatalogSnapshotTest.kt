package dev.gramhub.remote

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Guards the static catalog snapshot that ships with the app's default address.
 *
 * The app now points at `docs/clients.json` served by GitHub Pages, so a snapshot
 * that the parser cannot read would leave the "Not installed" tab empty for every
 * user. This reads the real committed file rather than a hand-written fixture, so
 * a change to the exporter that breaks the shape fails here.
 */
@RunWith(AndroidJUnit4::class)
class StaticCatalogSnapshotTest {

    @Test
    fun `the committed snapshot parses into clients`() {
        val snapshot = findSnapshot()
        assertTrue("docs/clients.json is missing", snapshot != null)

        val clients = CatalogJson.parseClients(snapshot!!.readText())

        assertTrue("snapshot parsed to nothing", clients.isNotEmpty())
        assertTrue("the catalog lost clients", clients.size >= 20)
        assertTrue("no client has an installable APK", clients.any { it.apk != null })
        assertTrue("every client needs an id", clients.all { it.id.isNotBlank() })
        assertTrue("every client needs a label", clients.all { it.label.isNotBlank() })
        assertTrue("ids must be unique", clients.map { it.id }.toSet().size == clients.size)
        assertTrue(
            "no junk repository may slip into the snapshot",
            clients.none { client ->
                val repo = client.repo.lowercase()
                listOf("-apk", "-files", "-website", "-docs", "-mirror").any(repo::endsWith)
            },
        )
    }

    /** Walks up from the test working directory to the repository root. */
    private fun findSnapshot(): File? {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "docs/clients.json")
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        return null
    }
}
