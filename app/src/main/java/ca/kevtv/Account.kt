package ca.kevtv

import java.net.URI
import java.net.URLEncoder
import java.util.Locale

class Account private constructor(val server: String, val username: String, val password: String) {
    val isCleartext get() = server.startsWith("http://")
    override fun toString() = "Account([masqué])"
    internal fun allows(uri: String): Boolean = ProviderEndpoint.sameOrigin(server, uri)

    companion object {
        fun fromInput(server: String, username: String, password: String): Account {
            val user = username.trim()
            require(validCredential(user) && validCredential(password)) {
                "Entre un nom d’utilisateur et un mot de passe Xtream valides."
            }
            return Account(ProviderEndpoint.normalize(server), user, password)
        }
        private fun validCredential(s: String) = s.isNotBlank() && s.length <= 512 &&
            s !in setOf(".", "..") && s.none { it.isISOControl() }
    }
}

internal object ProviderEndpoint {
    private val schemePrefix = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://")
    private val serverPath = Regex("(?:/[a-zA-Z0-9_~.-]+)*/?")
    private val serverHint = "Entre seulement l’adresse du serveur Xtream, sans identifiants ni lien de liste."

    fun normalize(input: String): String {
        val entered = input.trim()
        require(entered.isNotEmpty() && entered.length <= 2048) { serverHint }
        val candidate = if (schemePrefix.containsMatchIn(entered)) entered else "https://$entered"
        val u = parse(candidate) ?: throw IllegalArgumentException(serverHint)
        val scheme = u.scheme.lowercase(Locale.ROOT)
        val path = u.rawPath
        require(u.rawQuery == null && serverPath.matches(path) && path.split('/').none {
            it in setOf(".", "..") || it.endsWith(".php", true) || it.endsWith(".m3u", true) || it.endsWith(".m3u8", true)
        }) { serverHint }
        val port = u.port.takeUnless { scheme == "https" && it == 443 || scheme == "http" && it == 80 } ?: -1
        return URI(scheme, null, u.host.lowercase(Locale.ROOT), port, path.trimEnd('/'), null, null).toASCIIString()
    }

    private fun parse(url: String): URI? = runCatching { URI(url) }.getOrNull()?.takeIf {
        !it.isOpaque && it.scheme?.lowercase(Locale.ROOT) in setOf("https", "http") &&
            !it.host.isNullOrBlank() && it.rawUserInfo == null && it.rawFragment == null &&
            (it.port == -1 || it.port in 1..65535) && it.rawAuthority?.endsWith(":") == false
    }

    fun sameOrigin(server: String, target: String): Boolean {
        val root = parse(server) ?: return false
        val uri = parse(target) ?: return false
        return root.scheme.equals(uri.scheme, true) && root.host.equals(uri.host, true) &&
            effectivePort(root) == effectivePort(uri)
    }

    fun redirect(original: String, current: String, location: String?): String? {
        if (location.isNullOrBlank() || location.length > 8192 || !sameOrigin(original, current)) return null
        return runCatching {
            val reference = URI(location.trim())
            if (reference.isOpaque || reference.rawUserInfo != null || reference.rawFragment != null) return null
            val base = URI(current)
            // java.net.URI.resolve drops the last path segment for a query-only reference.
            val resolved = if (reference.scheme == null && reference.rawAuthority == null && reference.rawPath.isEmpty()) {
                if (reference.rawQuery == null) return null
                "${current.substringBefore('?')}?${reference.rawQuery}"
            } else base.resolve(reference).toASCIIString()
            resolved.takeIf { sameOrigin(original, it) }
        }.getOrNull()
    }

    private fun effectivePort(uri: URI) = if (uri.port != -1) uri.port else if (uri.scheme.equals("https", true)) 443 else 80

    fun component(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    // Artwork can name a CDN, but must never carry login parameters or embedded stream credentials.
    fun artwork(url: String, account: Account): String {
        val uri = parse(url) ?: return ""
        if (!account.isCleartext && !uri.scheme.equals("https", true)) return ""
        if (uri.rawQuery != null) return "" // Signed or credential-bearing URLs are intentionally not cached.
        val decoded = uri.path
        val segments = decoded.split('/')
        if (segments.any { it == account.username || it == account.password } ||
            decoded.contains("/${account.username}/${account.password}/")) return ""
        return uri.toASCIIString()
    }
}
