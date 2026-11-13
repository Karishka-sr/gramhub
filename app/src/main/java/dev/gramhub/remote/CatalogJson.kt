package dev.gramhub.remote

import org.json.JSONArray
import org.json.JSONObject

/**
 * JSON parsing for the catalog API.
 *
 * Written against [org.json], which ships with Android, so the app needs no
 * extra dependency for the few shapes the server returns. Parsing is total: a
 * malformed or unexpected payload yields null or an empty list rather than an
 * exception, because a catalog outage must never crash the launcher.
 */
object CatalogJson {

    fun parseClients(body: String): List<RemoteClient> {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return emptyList()
        val array = root.optJSONArray("clients") ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            parseClient(array.optJSONObject(index))
        }
    }

    fun parseClient(body: String): RemoteClient? =
        runCatching { JSONObject(body) }.getOrNull()?.let { parseClient(it) }

    fun parseClient(json: JSONObject?): RemoteClient? {
        if (json == null) return null
        val id = json.text("id") ?: return null
        val label = json.text("label") ?: id

        return RemoteClient(
            id = id,
            packageName = json.text("package_name"),
            label = label,
            repo = json.text("repo").orEmpty(),
            repoUrl = json.text("repo_url").orEmpty(),
            description = json.text("description").orEmpty(),
            stars = json.optInt("stars"),
            latestVersion = json.text("latest_version"),
            latestReleaseUrl = json.text("latest_release_url"),
            apk = parseApk(json.optJSONArray("releases")),
            scan = parseScan(json.optJSONObject("scan")),
            archived = json.optBoolean("archived"),
        )
    }

    /**
     * A string field, or null when it is absent, JSON null, or the literal text
     * "null".
     *
     * That last case is the reason this helper exists: [JSONObject.optString]
     * returns the four characters `null` for a JSON null, so a plain
     * `optString("package_name").takeIf { it.isNotBlank() }` would yield the
     * package name "null" and compare it against real package names.
     */
    private fun JSONObject.text(key: String): String? {
        if (!has(key) || isNull(key)) return null
        return optString(key).trim().takeIf { it.isNotEmpty() && it != "null" }
    }

    /**
     * Picks the APK to offer from the release list: the newest release's first
     * asset that targets a broad set of devices.
     */
    private fun parseApk(releases: JSONArray?): RemoteApk? {
        if (releases == null || releases.length() == 0) return null

        val assets = mutableListOf<RemoteApk>()
        for (r in 0 until releases.length()) {
            val release = releases.optJSONObject(r) ?: continue
            val list = release.optJSONArray("assets") ?: continue
            for (a in 0 until list.length()) {
                val asset = list.optJSONObject(a) ?: continue
                val url = asset.text("url") ?: continue
                val abi = asset.text("abi")
                if (asset.optBoolean("split")) continue
                assets += RemoteApk(
                    name = asset.text("name").orEmpty(),
                    url = url,
                    size = asset.optLong("size"),
                    sha256 = asset.text("sha256"),
                    abi = abi,
                )
            }
            if (assets.isNotEmpty()) break
        }

        return assets.firstOrNull { it.abi == null || it.abi == "universal" } ?: assets.firstOrNull()
    }

    fun parseScan(json: JSONObject?): RemoteScan? {
        if (json == null) return null
        val findings = json.optJSONArray("findings")
        val parsed = mutableListOf<RemoteFinding>()
        if (findings != null) {
            for (i in 0 until findings.length()) {
                val item = findings.optJSONObject(i) ?: continue
                parsed += RemoteFinding(
                    check = item.text("check").orEmpty(),
                    severity = Severity.from(item.text("severity")),
                    summary = item.text("summary").orEmpty(),
                    detail = item.text("detail").orEmpty(),
                )
            }
        }

        val digestVerified = if (json.has("digest_verified") && !json.isNull("digest_verified")) {
            json.optBoolean("digest_verified")
        } else {
            null
        }

        return RemoteScan(
            verdict = Verdict.from(json.text("verdict")),
            digestVerified = digestVerified,
            debugSigned = json.optBoolean("debug_signed"),
            signers = json.optJSONArray("signers").toStringList(),
            unexpectedPermissions = json.optJSONArray("unexpected_permissions").toStringList(),
            findings = parsed,
        )
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { optString(it).takeIf { value -> value.isNotBlank() } }
    }
}
