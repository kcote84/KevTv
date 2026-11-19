package ca.kevtv

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class Store(context: Context) {
    val prefs = context.getSharedPreferences("kevtv", Context.MODE_PRIVATE)
    private val keyAlias = "ca.kevtv.account"
    private fun keys() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private fun key(): SecretKey {
        (keys().getKey(keyAlias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    @Synchronized fun save(account: Account) {
        val old = account()
        val raw = JSONObject().put("server", account.server).put("username", account.username)
            .put("password", account.password).toString().toByteArray(Charsets.UTF_8)
        val enc = try {
            val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
            Base64.encodeToString(c.iv, Base64.NO_WRAP) + ":" +
                Base64.encodeToString(c.doFinal(raw), Base64.NO_WRAP)
        } finally { raw.fill(0) }
        val edit = prefs.edit()
        if (old == null || old.server != account.server || old.username != account.username) removeAccountContent(edit)
        check(edit.putString("account", enc).commit()) { "Impossible d’enregistrer le compte sur cet appareil." }
    }

    @Synchronized fun account(): Account? = runCatching {
        val parts = (prefs.getString("account", null) ?: return null).split(':', limit = 2)
        val existing = keys().getKey(keyAlias, null) as? SecretKey ?: return null
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, existing, GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
        }
        val raw = c.doFinal(Base64.decode(parts[1], Base64.NO_WRAP))
        try {
            val o = JSONObject(String(raw, Charsets.UTF_8))
            Account.fromInput(o.getString("server"), o.getString("username"), o.getString("password"))
        } finally { raw.fill(0) }
    }.getOrNull()

    private fun removeAccountContent(edit: android.content.SharedPreferences.Editor) {
        edit.remove("favourites").remove("favourite_details").remove("watch_history")
        prefs.all.keys.filter { it.startsWith("audio:") }.forEach { edit.remove(it) }
    }

    @Synchronized fun clearAccount() {
        val edit = prefs.edit().remove("account")
        removeAccountContent(edit)
        check(edit.commit()) { "Impossible d’oublier le compte sur cet appareil." }
        runCatching { keys().deleteEntry(keyAlias) }
    }

    fun favourites(): Set<String> = prefs.getStringSet("favourites", emptySet())!!.toSet()
    fun clearFavourites() { prefs.edit().remove("favourites").remove("favourite_details").apply() }
    fun favouriteEntries(): List<Entry> = runCatching {
        val all = JSONObject(prefs.getString("favourite_details", "{}")!!)
        favourites().mapNotNull { id -> all.optJSONObject(id)?.let { EntryJson.read(it) } }.sortedBy { it.name }
    }.getOrDefault(emptyList())

    fun toggle(entry: Entry): Boolean = setFavourite(entry, entry.key !in favourites())
    fun setFavourite(entry: Entry, wanted: Boolean): Boolean {
        val a = account() ?: return false
        val values = favourites().toMutableSet()
        val details = JSONObject(prefs.getString("favourite_details", "{}")!!)
        val added = if (!wanted) { values.remove(entry.key); details.remove(entry.key); false } else {
            values.add(entry.key)
            details.put(entry.key, EntryJson.write(entry.withSafeArtwork(a)))
            true
        }
        prefs.edit().putStringSet("favourites", values).putString("favourite_details", details.toString()).apply()
        return added
    }
    fun history(): List<WatchProgress> = WatchHistory.decode(prefs.getString("watch_history", "[]")!!)
    fun remember(progress: WatchProgress) {
        val a = account() ?: return
        val safe = progress.copy(entry = progress.entry.withSafeArtwork(a), next = progress.next?.withSafeArtwork(a))
        prefs.edit().putString("watch_history", WatchHistory.encode(WatchHistory.update(history(), safe))).apply()
    }
    fun setContinueHidden(entry: Entry, hidden: Boolean) {
        if (account() == null) return
        prefs.edit().putString("watch_history", WatchHistory.encode(history().map {
            if (it.entry.key == entry.key || entry.kind == "series" && it.entry.seriesId == entry.id) it.copy(hidden = hidden) else it
        })).apply()
    }
}

internal fun Entry.withSafeArtwork(account: Account) = copy(
    poster = ProviderEndpoint.artwork(poster, account),
    seriesPoster = ProviderEndpoint.artwork(seriesPoster, account),
)
