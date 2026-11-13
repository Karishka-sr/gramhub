package dev.gramhub.remote

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Where the catalog came from, so the UI can say when it is showing stale data. */
enum class CatalogSource { NETWORK, CACHE, NONE }

data class CatalogResult(
    val clients: List<RemoteClient>,
    val source: CatalogSource,
    val error: String? = null,
)

/**
 * Talks to the GramHub catalog server.
 *
 * Uses [HttpURLConnection] rather than a networking library: the API is three
 * GET/POST calls returning small JSON documents, and avoiding a dependency keeps
 * the APK small. Every successful response is cached on disk, so the
 * "Not installed" tab still shows something when the server is unreachable.
 */
class CatalogApi(
    private val context: Context,
    private val baseUrlProvider: () -> String,
) {

    private val cacheFile: File get() = File(context.cacheDir, "catalog.json")

    private val baseUrl: String
        get() = baseUrlProvider().trim().trimEnd('/')

    suspend fun fetchClients(forceRefresh: Boolean = false): CatalogResult =
        withContext(Dispatchers.IO) {
            if (baseUrl.isBlank()) {
                return@withContext CatalogResult(
                    clients = emptyList(),
                    source = CatalogSource.NONE,
                    error = "no server configured",
                )
            }

            runCatching { get("$baseUrl/clients") }
                .map { body ->
                    cacheFile.writeText(body)
                    CatalogResult(CatalogJson.parseClients(body), CatalogSource.NETWORK)
                }
                .getOrElse { failure ->
                    val cached = readCache()
                    CatalogResult(
                        clients = cached,
                        source = if (cached.isEmpty()) CatalogSource.NONE else CatalogSource.CACHE,
                        error = failure.message ?: failure::class.simpleName ?: "network error",
                    )
                }
                .also { if (forceRefresh) Unit }
        }

    /** Asks the server to download and scan the entry's APK. */
    suspend fun scan(clientId: String): RemoteScan? = withContext(Dispatchers.IO) {
        if (baseUrl.isBlank()) return@withContext null
        runCatching {
            val body = post("$baseUrl/clients/$clientId/scan")
            CatalogJson.parseScan(
                org.json.JSONObject(body).optJSONObject("scan"),
            )
        }.getOrNull()
    }

    suspend fun health(): Boolean = withContext(Dispatchers.IO) {
        if (baseUrl.isBlank()) return@withContext false
        runCatching { get("$baseUrl/health"); true }.getOrDefault(false)
    }

    private fun readCache(): List<RemoteClient> =
        runCatching { cacheFile.readText() }
            .map { CatalogJson.parseClients(it) }
            .getOrDefault(emptyList())

    private fun get(url: String): String = request(url, "GET")

    private fun post(url: String): String = request(url, "POST")

    private fun request(url: String, method: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Accept", "application/json")
            if (method == "POST") {
                doOutput = true
                setFixedLengthStreamingMode(0)
            }
        }
        return try {
            val code = connection.responseCode
            if (code !in 200..299) {
                throw java.io.IOException("server returned HTTP $code")
            }
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 120_000
    }
}
