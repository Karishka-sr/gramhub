package dev.gramhub.remote

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Guards the parser the "Проверить" button depends on.
 *
 * The default catalog is a static snapshot, so the app learns a verdict by
 * re-reading that file. If the snapshot's `scan` object stops parsing, the button
 * silently returns "nothing to show" for every client, which is exactly the bug
 * this covers.
 */
@RunWith(AndroidJUnit4::class)
class ScanOutcomeParsingTest {

    @Test
    fun `a snapshot verdict parses into a trusted scan`() {
        val body = """
            {"clients": [{
              "id": "nekogram-nekogram",
              "label": "Nekogram",
              "repo": "Nekogram/Nekogram",
              "scan": {
                "verdict": "trusted",
                "digest_verified": true,
                "debug_signed": false,
                "signers": ["aabb"],
                "unexpected_permissions": [],
                "findings": [
                  {"check": "signature", "severity": "low", "summary": "Signed", "detail": "ok"}
                ]
              }
            }]}
        """.trimIndent()

        val client = CatalogJson.parseClients(body).single()

        assertEquals(Verdict.TRUSTED, client.scan?.verdict)
        assertEquals(true, client.scan?.digestVerified)
        assertEquals(1, client.scan?.findings?.size)
    }

    @Test
    fun `an entry without a scan has no verdict`() {
        val body = """{"clients": [{"id": "x", "label": "X", "repo": "a/b"}]}"""

        val client = CatalogJson.parseClients(body).single()

        assertEquals(null, client.scan)
    }

    @Test
    fun `the committed snapshot carries verdicts for its installable clients`() {
        val snapshot = findSnapshot()
        assertTrue("docs/clients.json is missing", snapshot != null)

        val clients = CatalogJson.parseClients(snapshot!!.readText())
        val withApk = clients.filter { it.apk != null }
        val scanned = withApk.filter { it.scan != null }

        assertTrue("no client in the snapshot has an installable APK", withApk.isNotEmpty())
        assertTrue(
            "the snapshot has installable clients but no verdicts, so Check has nothing to show",
            scanned.isNotEmpty(),
        )
        assertTrue(
            "every verdict in the snapshot must map to a known enum value",
            scanned.none { it.scan?.verdict == Verdict.UNKNOWN },
        )
    }

    /** Walks up from the test working directory to the repository root. */
    private fun findSnapshot(): java.io.File? {
        var dir: java.io.File? = java.io.File("").absoluteFile
        while (dir != null) {
            val candidate = java.io.File(dir, "docs/clients.json")
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        return null
    }
}
