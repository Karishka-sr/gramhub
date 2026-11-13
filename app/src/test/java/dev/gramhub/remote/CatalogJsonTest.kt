package dev.gramhub.remote

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Parsing tests.
 *
 * The payloads are trimmed copies of what the server actually returns, including
 * the awkward cases: a null package name, an asset with no digest, and a scan
 * whose verdict is not one the app knows.
 *
 * Runs on Robolectric so `org.json` is the real Android implementation; the stub
 * on the plain unit-test classpath throws "not mocked" for every method.
 */
@RunWith(AndroidJUnit4::class)
class CatalogJsonTest {

    @Test
    fun `parses a full client list`() {
        val body = """
            {"count": 2, "clients": [
              {"id": "nekogram-nekogram", "package_name": "tw.nekomimi.nekogram",
               "label": "Nekogram", "repo": "Nekogram/Nekogram",
               "repo_url": "https://github.com/Nekogram/Nekogram",
               "description": "Third-party client", "stars": 4015,
               "latest_version": "v13.1.1",
               "latest_release_url": "https://github.com/Nekogram/Nekogram/releases/tag/v13.1.1",
               "archived": false,
               "releases": [{"tag": "v13.1.1", "assets": [
                   {"name": "app-universal.apk", "url": "https://github.com/a/b.apk",
                    "size": 74576190, "sha256": "abc123", "abi": "universal", "split": false}]}],
               "scan": {"verdict": "trusted", "digest_verified": true, "debug_signed": false,
                        "signers": ["aa"], "unexpected_permissions": [],
                        "findings": [{"check": "digest", "severity": "info",
                                      "summary": "ok", "detail": "d"}]}},
              {"id": "other-client", "package_name": null, "label": "Other",
               "repo": "x/other", "repo_url": "https://github.com/x/other",
               "description": "", "stars": 0, "archived": false, "releases": []}
            ]}
        """.trimIndent()

        val clients = CatalogJson.parseClients(body)
        assertEquals(2, clients.size)

        val first = clients[0]
        assertEquals("Nekogram", first.label)
        assertEquals("tw.nekomimi.nekogram", first.packageName)
        assertEquals("v13.1.1", first.latestVersion)
        assertEquals(74576190L, first.apk?.size)
        assertEquals("abc123", first.apk?.sha256)
        assertEquals(Verdict.TRUSTED, first.scan?.verdict)
        assertEquals(true, first.scan?.digestVerified)

        // A null package name must stay null, not become the string "null".
        assertNull(clients[1].packageName)
        assertNull(clients[1].apk)
    }

    @Test
    fun `missing digest is reported as unknown rather than false`() {
        val body = """
            {"clients": [{"id": "a-b", "label": "AB", "repo": "a/b", "releases": [],
              "scan": {"verdict": "caution", "digest_verified": null, "debug_signed": false,
                       "signers": [], "unexpected_permissions": [], "findings": []}}]}
        """.trimIndent()

        val scan = CatalogJson.parseClients(body).single().scan
        assertNull(scan?.digestVerified)
        assertEquals(Verdict.CAUTION, scan?.verdict)
    }

    @Test
    fun `an unknown verdict degrades to unknown`() {
        val scan = CatalogJson.parseScan(JSONObject("""{"verdict": "maybe"}"""))
        assertEquals(Verdict.UNKNOWN, scan?.verdict)
    }

    @Test
    fun `prefers a universal apk over an abi-specific one`() {
        val body = """
            {"clients": [{"id": "a-b", "label": "AB", "repo": "a/b", "releases": [
              {"tag": "v1", "assets": [
                {"name": "arm64.apk", "url": "https://x/arm64.apk", "size": 1, "abi": "arm64-v8a"},
                {"name": "universal.apk", "url": "https://x/universal.apk", "size": 2, "abi": "universal"}]}]}]}
        """.trimIndent()

        assertEquals("universal.apk", CatalogJson.parseClients(body).single().apk?.name)
    }

    @Test
    fun `falls back to any asset when no universal build exists`() {
        val body = """
            {"clients": [{"id": "a-b", "label": "AB", "repo": "a/b", "releases": [
              {"tag": "v1", "assets": [
                {"name": "arm64.apk", "url": "https://x/arm64.apk", "size": 1, "abi": "arm64-v8a"}]}]}]}
        """.trimIndent()

        assertEquals("arm64.apk", CatalogJson.parseClients(body).single().apk?.name)
    }

    @Test
    fun `malformed json yields an empty list instead of throwing`() {
        assertTrue(CatalogJson.parseClients("not json at all").isEmpty())
        assertTrue(CatalogJson.parseClients("").isEmpty())
        assertTrue(CatalogJson.parseClients("{}").isEmpty())
        assertNull(CatalogJson.parseClient("{]"))
    }

    @Test
    fun `an entry without an id is dropped`() {
        val body = """{"clients": [{"label": "No id", "repo": "a/b"}]}"""
        assertTrue(CatalogJson.parseClients(body).isEmpty())
    }

    @Test
    fun `formats sizes for display`() {
        assertEquals("71.1 MB", RemoteApk("a", "u", 74_576_190, null, null).formattedSize())
        assertEquals("512 KB", RemoteApk("a", "u", 524_288, null, null).formattedSize())
        assertEquals("", RemoteApk("a", "u", 0, null, null).formattedSize())
    }

    @Test
    fun `parses a payload captured from the running server`() {
        // app/src/test/resources/catalog_payload.json is the verbatim response of
        // GET /clients against the real server, so this catches the client and the
        // server drifting apart on field names or null handling.
        val body = javaClass.classLoader!!.getResourceAsStream("catalog_payload.json")!!
            .bufferedReader()
            .use { it.readText() }

        val clients = CatalogJson.parseClients(body)
        assertTrue(clients.size >= 10)

        // Every entry must survive parsing with something to show.
        clients.forEach { client ->
            assertTrue("empty id", client.id.isNotBlank())
            assertTrue("empty label for ${client.id}", client.label.isNotBlank())
            assertTrue("empty repo url for ${client.id}", client.repoUrl.startsWith("https://"))
        }

        // No field may have been parsed as the literal string "null".
        clients.forEach { client ->
            assertNotEquals("null", client.packageName)
            assertNotEquals("null", client.latestVersion)
            assertNotEquals("null", client.description)
            assertNotEquals("null", client.repoUrl)
        }

        // Every entry with a release has an installable asset with a URL.
        val withReleases = clients.filter { it.apk != null }
        assertTrue(withReleases.isNotEmpty())
        withReleases.forEach { assertTrue(it.apk!!.url.startsWith("https://")) }
    }
}
