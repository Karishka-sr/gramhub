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
    private class TinyServer(private val payload: String) {
        private val socket = ServerSocket(0)
        val paths: MutableList<String> = Collections.synchronizedList(mutableListOf())

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
            viewModel.catalogState.first { it.entries.isNotEmpty() }
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
}
