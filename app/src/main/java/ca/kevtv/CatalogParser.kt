package ca.kevtv

import org.json.JSONObject
import java.net.URI

object ArtworkRules {
    private val streamPath = Regex("""(?:^|/)(?:live|movie|series|timeshift)/[^/]+/[^/]+/(?:[^/]+/)*\d+\.[a-z0-9]+(?:$|/)""")
    fun url(value: String, base: String = ""): String = runCatching {
        val raw = value.trim()
        if(raw.isEmpty() || raw.length > 4096 || raw.equals("null", true)) return ""
        val encoded = raw.replace(" ", "%20")
        val candidate = URI(if(encoded.startsWith("//")) "https:$encoded" else encoded)
        val uri = if(candidate.isAbsolute) candidate else URI(base.trimEnd('/') + "/").resolve(candidate)
        // Cover art is public metadata. Do not persist auth queries, stream URLs, or signed URLs in library entries.
        val route = uri.path.orEmpty().lowercase(java.util.Locale.ROOT)
        val secureOrigin = base.isNotBlank() && base.startsWith("https://", true)
        if(uri.scheme !in listOf("http", "https") || uri.host.isNullOrBlank() ||
            uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null ||
            (secureOrigin && uri.scheme == "http") || streamPath.containsMatchIn(route) || route.split('/').any { it in setOf("get.php", "player_api.php") } ||
            route.contains('\\')) "" else uri.toASCIIString()
    }.getOrDefault("")
}

object CatalogParser {
    fun entry(o: JSONObject, kind: String, base: String): Entry = Entry(
        o.optString(if(kind == "series") "series_id" else "stream_id"), o.optString("name", "Sans titre"),
        o.optString("category_id"), kind,
        CatalogRules.safeExtension(o.optString("container_extension"), if(kind == "live") "ts" else "mp4"),
        o.optInt("num"), o.optInt("is_adult") == 1 || o.optBoolean("is_adult"),
        ArtworkRules.url(o.optString(if(kind == "series") "cover" else "stream_icon"), base))
}
