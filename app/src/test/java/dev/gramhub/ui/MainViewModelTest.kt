package dev.gramhub.ui

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.gramhub.data.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.net.ServerSocket
import java.util.Collections

/**
 * Catalog wiring in [MainViewModel].
 *
 * The address the user configured lives in DataStore, which delivers its value
 * asynchronously, so the view model must react to the address itself rather than
 * firing a single refresh at construction time. The latter queried the built-in
 * default on every cold start, which is why a user who pointed the app at their
 * own server still saw "catalog unreachable" until they pressed Retry by hand.
 *
 * The test drives the real HTTP path against a local socket server, so it fails
 * if the view model stops following the configured address.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class MainViewModelTest {

    /** Minimal HTTP/1.0 server on a real socket; records the paths it is asked for. */
    private class TinyServer(initialPayload: String) {
        private val socket = ServerSocket(0)
        val paths: MutableList<String> = Collections.synchronizedList(mutableListOf())

        @Volatile
        private var payload: String = initialPayload

        fun setPayload(next: String) {
            payload = next
        }

        val url: String get() = "http://127.0.0.1:${socket.localPort}"

        private val worker = Thread {
            while (!socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (_: Exception) {
                    return@Thread
                }
                client.use { connection ->
                    val reader = connection.getInputStream().bufferedReader()
                    val requestLine = reader.readLine() ?: return@use
                    paths += requestLine.split(" ").getOrElse(1) { "/" }
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                    }
                    val body = payload.toByteArray()
                    val head = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                        "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n"
                    connection.getOutputStream().apply {
                        write(head.toByteArray())
                        write(body)
                        flush()
                    }
                }
            }
        }

        fun start() {
            worker.isDaemon = true
            worker.start()
        }

        fun stop() {
            socket.close()
        }
    }

    private lateinit var server: TinyServer

    private val payload = """
        {"count":1,"clients":[{"id":"a/b","package_name":"a.b","label":"Test Client",
        "repo":"a/b","repo_url":"https://github.com/a/b","description":"d",
        "stars":1,"source":"github","archived":false,"releases":[]}]}
    """.trimIndent()

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        server = TinyServer(payload)
        server.start()
        // The catalog response is cached on disk and shared by every view model in
        // the process, so a leftover cache from an earlier test would be served as
        // this test's "network" result.
        ApplicationProvider.getApplicationContext<Application>()
            .cacheDir.resolve("catalog.json")
            .delete()
    }

    @After
    fun tearDown() {
        server.stop()
        Dispatchers.resetMain()
    }

    @Test
    fun `changing the catalog address refetches from the new server`() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val settings = SettingsRepository(app)
        settings.reset()
        // A port nothing listens on: the first refresh fails, which is the same
        // state the user hit on a cold start before the fix.
        settings.setCatalogUrl("http://127.0.0.1:1")

        val viewModel = MainViewModel(app)
        // WhileSubscribed means the derived state only updates while it is watched.
        val watch = launch(Dispatchers.Default) { viewModel.catalogState.collect {} }

        // Point the app at the live server. Only a view model that follows the
        // configured address will make a request here.
        settings.setCatalogUrl(server.url)

        val state = withTimeoutOrNull(20_000) {
            // Wait for a state that is both served by this server and carries its
            // address. `entries.isNotEmpty()` alone can be satisfied by the built-in
            // default catalog, which a networked test machine can reach; and the
            // entries and the address arrive on separate flows, so an emission can
            // briefly pair fresh entries with the previous address.
            viewModel.catalogState.first { state ->
                state.serverUrl == server.url && state.entries.any { it.id == "a/b" }
            }
        }
        watch.cancel()

        assertTrue(
            "the view model did not refetch after the address changed; paths=${
                server.paths.toList()
            }",
            server.paths.contains("/clients"),
        )
        assertEquals(
            "the configured server's entry should be listed",
            listOf("a/b"),
            state?.entries?.map { it.id },
        )
        assertEquals("state should carry the configured address", server.url, state?.serverUrl)
    }

    @Test
    fun `a static snapshot without a verdict reports the check as unsupported`() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val settings = SettingsRepository(app)
        settings.reset()
        // A port nothing listens on, so the first refresh cannot succeed and the
        // default catalog URL cannot race this test's snapshot.
        settings.setCatalogUrl("http://127.0.0.1:1")

        val viewModel = MainViewModel(app)
        val watch = launch(Dispatchers.Default) { viewModel.catalogState.collect {} }
        // A snapshot URL, not a live server: there is no endpoint to scan on demand.
        settings.setCatalogUrl("${server.url}/clients.json")

        val entry = withTimeoutOrNull(20_000) {
            viewModel.catalogState.first { state -> state.entries.any { it.id == "a/b" } }
        }?.entries?.first { it.id == "a/b" }

        viewModel.scan(entry!!)
        val message = withTimeoutOrNull(20_000) { viewModel.scanMessage.first { it != null } }
        watch.cancel()

        assertEquals(
            "a snapshot that carries no verdict must be reported, not silently ignored",
            ScanMessage.UNSUPPORTED,
            message?.message,
        )
        assertEquals(
            "the explanation must name the entry whose button produced it",
            "a/b",
            message?.clientId,
        )
    }

    @Test
    fun `a static snapshot that carries a verdict stores it`() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val settings = SettingsRepository(app)
        settings.reset()
        settings.setCatalogUrl("http://127.0.0.1:1")

        val viewModel = MainViewModel(app)
        val watch = launch(Dispatchers.Default) { viewModel.catalogState.collect {} }
        settings.setCatalogUrl("${server.url}/clients.json")

        // The snapshot the app first reads carries no verdict, which is the state a
        // user is in when the refresh workflow has not run since their last fetch.
        val entry = withTimeoutOrNull(20_000) {
            viewModel.catalogState.first { state -> state.entries.any { it.id == "a/b" } }
        }?.entries?.first { it.id == "a/b" }
        assertTrue("the entry should start without a verdict", entry?.scan == null)

        // The workflow now publishes a verdict; pressing Check must pick it up.
        server.setPayload(
            """
            {"count":1,"clients":[{"id":"a/b","package_name":"a.b","label":"Test Client",
            "repo":"a/b","repo_url":"https://github.com/a/b","description":"d",
            "stars":1,"source":"github","archived":false,"releases":[],
            "scan":{"verdict":"trusted","digest_verified":true,"debug_signed":false,
            "signers":["aa"],"unexpected_permissions":[],"findings":[]}}]}
            """.trimIndent(),
        )
        viewModel.scan(entry!!)
        val verdict = withTimeoutOrNull(20_000) {
            viewModel.catalogState.first { state ->
                state.entries.firstOrNull { it.id == "a/b" }?.scan != null
            }?.entries?.first { it.id == "a/b" }?.scan?.verdict
        }
        watch.cancel()

        assertEquals(
            "the snapshot's verdict must reach the entry the button was pressed on",
            "trusted",
            verdict?.name?.lowercase(),
        )
    }
}
