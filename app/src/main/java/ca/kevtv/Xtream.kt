package ca.kevtv

import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.text.Normalizer
import java.util.Locale

object CatalogRules {
    private val accents = Regex("\\p{M}+")
    private val foreignLiveCategory = Regex("\\b(france|belgique|suisse|afrique)\\b")
    private val franceLiveChannel = Regex("\\b(france\\s*(2|3|4|5|24)|tf1|tfx|tmc|m6|w9|6ter|bfm(?:tv)?|lci|arte)\\b")
    private val quebecCategory = Regex("\\b(qc|quebec|quebecois|quebecoise|quebecoises)\\b")
    private val canadaCategory = Regex("\\bcanad(?:a|ian)\\b")
    fun normalize(s: String): String = Normalizer.normalize(s.lowercase(Locale.ROOT), Normalizer.Form.NFD).replace(accents, "")
    private val adultCategory = Regex("(^|[^a-z0-9])adults?(?:e|es)?([^a-z0-9]|$)")
    private val adultMarker = Regex("(^|[^a-z0-9])(?:xxx|porn(?:o|os|ography|ographique|ographiques)?|erotic(?:a)?|erotiques?|18\\s*(?:\\+|plus)|playboy|penthouse|hustler|brazzers)(?=$|[^a-z0-9])")
    private val quebecBrands = Regex("^(?:radio[ -]?canada|tele[ -]?quebec|ici[ -]?(?:tele(?:vision)?|rdi|artv|explora)|tva|noovo|rds|lcn|zeste|historia|evasion|series[ -]?plus|cinepop|addik(?:[ -]?tv)?|casa|canal[ -]?(?:d|vie|savoir)|savoir[ -]?media|prise[ -]?2|unis[ -]?tv|ami[ -]?tele|artv|assemblee[ -]?nationale|cpac|elle[ -]?fictions|frissons[ -]?tv|investigation|matv|max|meteo[ -]?media|natyf[ -]?tv|planete\\s*\\+?\\s*canada|saisons|stingray[ -]?(?:franco|palmares)|super[ -]?ecran|telemag|teletoon(?![ -]?(?:plus|\\+))|temoin|tfo|tv5(?![ -]?monde)|vie|z[ -]?tele|a[ -]?la[ -]?carte)\\b")
    private val frenchVodTag = Regex("\\b(fr|fra|fre|vf|vff|vfq|truefrench|francais)\\b")
    private val subtitled = Regex("\\bvost(?:fr|f)?\\b")
    private val foreignVodTag = Regex("(?:^|\\[|\\(|\\|)\\s*(en|eng|english|vo)\\s*(?:\\]|\\)|\\||$)")
    fun isAdult(category: String, title: String, marked: Boolean = false): Boolean {
        if (marked) return true
        val group = normalize(category).replace(Regex("\\badult\\s+swim\\b"), "")
        return adultCategory.containsMatchIn(group) || adultMarker.containsMatchIn("$group ${normalize(title)}")
    }
    fun matches(section: String, category: String, title: String, markedAdult: Boolean = false): Boolean {
        val text = normalize("$category $title")
        return when (section) {
            "Adultes" -> isAdult(category, title, markedAdult)
            "Québec (direct)" -> isQuebecLive(category, title)
            "Sports" -> Regex("sport|rds|tsn|espn|bein|dazn|nhl|nfl|nba|mlb|hockey|football").containsMatchIn(text)
            "Français" -> Regex("(^|[^a-z])(fr|fra|fre|vf|vff|vfq|vostfr|qc|quebec|france|francais|french)([^a-z]|$)|radio.?canada|tele.?quebec|tva|noovo|rds").containsMatchIn(text)
            "Québec" -> Regex("(^|[^a-z])(qc|quebec|quebecois|quebecoise|quebecoises|vfq)([^a-z]|$)|radio.?canada|tele.?quebec|tva|noovo").containsMatchIn(text)
            else -> true
        }
    }
    fun isQuebecLive(category: String, title: String): Boolean {
        val group = normalize(category); val name = normalize(title)
        if (foreignLiveCategory.containsMatchIn(group)) return false
        if (franceLiveChannel.containsMatchIn(name)) return false
        if (quebecCategory.containsMatchIn(group)) return true
        if (canadaCategory.containsMatchIn(group) && matches("Français", group, "")) return true
        return quebecBrands.containsMatchIn(name)
    }
    fun isFrenchVod(category: String, title: String): Boolean {
        val name = normalize(title); val group = normalize(category)
        if (subtitled.containsMatchIn("$name $group")) return false
        val explicitFrench = frenchVodTag.containsMatchIn(name)
        val foreignTitle = foreignVodTag.containsMatchIn(name)
        if(!explicitFrench && foreignTitle) return false
        return explicitFrench || matches("Français", category, "") || Regex("\\b(quebecois|quebecoise|quebecoises|truefrench)\\b").containsMatchIn(group)
    }
    fun safeExtension(s: String, fallback: String) = s.takeIf { it.matches(Regex("[A-Za-z0-9]{1,8}")) } ?: fallback
}

data class Category(val id: String, val name: String)
data class Entry(val id: String, val name: String, val category: String, val kind: String, val extension: String = "ts", val number: Int = 0, val adult: Boolean = false, val poster: String = "",
    val seriesId: String = "", val seriesName: String = "", val season: Int = 0, val episode: Int = 0, val seriesPoster: String = "") {
    val key get() = "$kind:$id"
}
class Xtream(private val account: Account, private val fetch: (String, Int, Int, Int) -> String = Network::text) {
    private val root = account.server
    private fun api(action: String? = null, extra: Pair<String, String>? = null, epg: Boolean = false): String {
        val fields = mutableListOf("username" to account.username, "password" to account.password)
        action?.let { fields.add("action" to it) }
        extra?.let { fields.add(it) }
        if (action == "get_short_epg") fields.add("limit" to "64")
        val query = fields.joinToString("&") { (key, value) ->
            "${ProviderEndpoint.component(key)}=${ProviderEndpoint.component(value)}"
        }
        return fetch("$root/player_api.php?$query", if (epg) 5000 else 15000, if (epg) 8000 else 25000,
            if (epg) 2 * 1024 * 1024 else 32 * 1024 * 1024)
    }

    fun authenticate() = safe {
        val raw = try { api() } catch (e: ProviderHttpException) {
            if (e.status in listOf(401, 403)) throw XtreamAuthenticationException() else throw e
        }
        val o = JSONObject(raw).optJSONObject("user_info") ?: throw IOException()
        if (o.optInt("auth") != 1 || o.optString("status").lowercase(Locale.ROOT) in
            setOf("disabled", "expired", "banned", "inactive")) throw XtreamAuthenticationException()
    }
    fun categories(kind: String): List<Category> = safe {
        val a = JSONArray(api("get_${kind}_categories"))
        (0 until a.length()).map { a.getJSONObject(it) }.map { Category(it.optString("category_id"), it.optString("category_name", "Autres")) }
    }
    fun entries(kind: String, category: String? = null): List<Entry> = safe {
        val action = if (kind == "series") "get_series" else "get_${kind}_streams"
        val a = JSONArray(api(action, category?.let { "category_id" to it }))
        (0 until a.length()).map { CatalogParser.entry(a.getJSONObject(it), kind, root).withSafeArtwork(account) }
            .filter { it.id.matches(ID) }
    }
    fun episodes(series: Entry): List<Entry> = details(series).episodes.map { it.entry }
    fun details(entry: Entry): MediaDetails = safe {
        val details = MediaDetailsParser.parse(JSONObject(api(
            if (entry.kind == "series") "get_series_info" else "get_vod_info",
            (if (entry.kind == "series") "series_id" else "vod_id") to entry.id)), entry, root)
        details.copy(entry = details.entry.withSafeArtwork(account), seasons = details.seasons.map { season ->
            season.copy(episodes = season.episodes.map { it.copy(entry = it.entry.withSafeArtwork(account)) })
        })
    }
    @Volatile private var zoneLoaded = false
    private var serverZone: java.time.ZoneId? = null
    @Synchronized private fun zone(): java.time.ZoneId? {
        if (!zoneLoaded) {
            serverZone = runCatching { java.time.ZoneId.of(JSONObject(api(epg = true)).getJSONObject("server_info").getString("timezone")) }.getOrNull()
            zoneLoaded = true
        }
        return serverZone
    }
    fun guide(channel: Entry): List<Programme> = safe {
        fun parse(raw: String): List<Programme> {
            val parsed = Guide.parse(raw)
            return if (parsed.isEmpty() && (JSONObject(raw).optJSONArray("epg_listings")?.length() ?: 0) > 0)
                Guide.parse(raw, zone()) else parsed
        }
        val full = runCatching { parse(api("get_simple_data_table", "stream_id" to channel.id, true)) }
        if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException()
        if (full.getOrNull()?.any { it.end > System.currentTimeMillis() } == true) full.getOrThrow()
        else parse(api("get_short_epg", "stream_id" to channel.id, true))
    }
    fun stream(e: Entry, hls: Boolean): String {
        val path = when (e.kind) {
            "live" -> "live"; "vod" -> "movie"; "episode" -> "series"
            else -> throw IllegalArgumentException("Ce contenu ne peut pas être lu.")
        }
        require(e.id.matches(ID)) { "Identifiant de contenu invalide." }
        val ext = if (e.kind == "live" && hls) "m3u8" else
            CatalogRules.safeExtension(e.extension, if (e.kind == "live") "ts" else "mp4")
        return "$root/$path/${ProviderEndpoint.component(account.username)}/${ProviderEndpoint.component(account.password)}/${e.id}.$ext"
    }

    private inline fun <T> safe(block: () -> T): T = try { block() }
        catch (e: Exception) { throw ProviderTransport.safeFailure(e) }
    private companion object { val ID = Regex("[0-9]{1,32}") }
}

object Network {
    fun text(url: String, connectMs: Int = 15000, readMs: Int = 25000, maxChars: Int = 32 * 1024 * 1024): String {
        val c = try { ProviderTransport.get(url, connectMs, readMs).connection }
            catch (e: Exception) { throw ProviderTransport.safeFailure(e) }
        return try {
            c.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                val result = StringBuilder()
                val buffer = CharArray(8192)
                while (true) {
                    if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException()
                    val n = reader.read(buffer)
                    if (n < 0) break
                    if (result.length + n > maxChars) throw IOException()
                    result.append(buffer, 0, n)
                }
                result.toString()
            }
        } catch (e: Exception) { throw ProviderTransport.safeFailure(e) } finally { c.disconnect() }
    }
}