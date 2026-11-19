package ca.kevtv

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import java.util.Locale

class Update internal constructor(val version: String, val notes: String, internal val tag: String,
    internal val url: String, internal val size: Long, internal val sha256: String?)

internal object ReleaseRules {
    private const val REPO = "kcote84/KevTv"
    const val LATEST = "https://api.github.com/repos/$REPO/releases/latest"
    const val ASSET = "KevTV.apk"
    const val PACKAGE = "ca.kevtv"
    const val MAX_BYTES = 128L * 1024 * 1024
    private val digest = Regex("sha256:[0-9a-f]{64}")
    private val semver = Regex("v?(0|[1-9][0-9]{0,7})\\.(0|[1-9][0-9]{0,7})\\.(0|[1-9][0-9]{0,7})")

    private fun version(value: String): List<Int>? = semver.matchEntire(value)?.groupValues?.drop(1)?.map { it.toInt() }

    fun parse(json: String, installedVersion: String): Update? {
        val release = JSONObject(json)
        if (release.optBoolean("draft", true) || release.optBoolean("prerelease", true)) return null
        val tag = release.optString("tag_name")
        val candidate = version(tag) ?: throw IOException("Étiquette de mise à jour non reconnue.")
        val installed = version(installedVersion) ?: throw IOException("Version installée non reconnue.")
        if (candidate.indices.firstOrNull { candidate[it] != installed[it] }?.let { candidate[it] <= installed[it] } != false)
            return null
        requireRelease(release.optString("html_url"), tag)
        val assets = release.optJSONArray("assets") ?: return null
        val matches = (0 until assets.length()).mapNotNull { assets.optJSONObject(it) }.filter { it.optString("name") == ASSET }
        if (matches.isEmpty()) return null
        require(matches.size == 1) { "Plus d’un APK de mise à jour trouvé." }
        val asset = matches.single()
        require(asset.optString("state") == "uploaded") { "APK GitHub indisponible." }
        val url = asset.optString("browser_download_url")
        require(initialUrl(url, tag)) { "Adresse APK GitHub non reconnue." }
        val size = asset.optLong("size", -1)
        require(size in 1..MAX_BYTES) { "Taille d’APK non reconnue." }
        val sum = asset.optString("digest").lowercase(Locale.ROOT).takeUnless { asset.isNull("digest") || it.isBlank() }
        require(sum == null || digest.matches(sum)) { "Empreinte de téléchargement GitHub invalide." }
        return Update(tag.removePrefix("v"), release.optString("body").trim().take(2000)
            .ifBlank { "Améliorations et corrections." }, tag, url, size, sum?.substringAfter(':'))
    }

    private fun requireRelease(url: String, tag: String) {
        val actual = secure(url)
        require(actual?.host.equals("github.com", true) && actual?.rawQuery == null &&
            repoPath(actual?.rawPath, "/releases/tag/$tag")) { "Version GitHub non reconnue." }
    }

    fun initialUrl(url: String, tag: String): Boolean {
        val uri = secure(url) ?: return false
        return version(tag) != null && uri.host.equals("github.com", true) && uri.rawQuery == null &&
            repoPath(uri.rawPath, "/releases/download/$tag/$ASSET")
    }

    private fun repoPath(path: String?, suffix: String): Boolean {
        val prefix = "/$REPO"
        return path?.startsWith(prefix, ignoreCase = true) == true && path.substring(prefix.length) == suffix
    }

    private fun secure(url: String): URI? = runCatching { URI(url) }.getOrNull()?.takeIf {
        !it.isOpaque && it.scheme.equals("https", true) && !it.host.isNullOrBlank() &&
            it.rawUserInfo == null && it.rawFragment == null && (it.port == -1 || it.port == 443) &&
            it.rawAuthority?.endsWith(":") == false
    }

    // github.com may forward the public APK to an official release-asset host and signed URL.
    fun officialRedirect(location: String?): String? {
        val destination = location?.trim()?.takeIf { it.length in 1..8192 } ?: return null
        val uri = secure(destination) ?: return null
        val path = uri.rawPath.orEmpty()
        val route = when {
            uri.host.equals("release-assets.githubusercontent.com", true) ->
                path.startsWith("/github-production-release-asset/") || path.startsWith("/github-production-release-asset-2e65be/")
            uri.host.equals("objects.githubusercontent.com", true) ->
                path.startsWith("/github-production-release-asset-2e65be/")
            else -> false
        }
        return destination.takeIf { route && !uri.rawQuery.isNullOrBlank() }
    }

    fun apkMatches(expectedVersion: String?, installedVersionCode: Long, packageName: String, versionName: String?,
        versionCode: Long, installedSigners: Set<String>, archiveSigners: Set<String>): Boolean =
        packageName == PACKAGE && (expectedVersion == null || versionName == expectedVersion) &&
            versionCode > installedVersionCode && installedSigners.isNotEmpty() && archiveSigners == installedSigners
}

class Updater(private val activity: Activity) {
    private val updates get() = File(activity.filesDir, "updates")
    private val verifiedFileName = "kevtv-update.apk"
    private data class Staged(val file: File, val length: Long, val modified: Long)
    @Volatile private var staged: Staged? = null

    fun check(): Update? = safe("Impossible de vérifier les mises à jour GitHub.") {
        val c = get(ReleaseRules.LATEST).apply {
            setRequestProperty("Accept", "application/vnd.github+json")
        }
        try {
            val status = c.responseCode
            if (status == 404) return@safe null // no published stable release yet
            if (status != 200) throw IOException()
            c.inputStream.use { input ->
                val metadata = readMetadata(input)
                ReleaseRules.parse(metadata, installed().versionName.orEmpty())
            }
        } finally { c.disconnect() }
    }

    @Synchronized fun download(update: Update): File = safe("La mise à jour n’a pas pu être téléchargée ou vérifiée.") {
        require(ReleaseRules.initialUrl(update.url, update.tag) && update.size in 1..ReleaseRules.MAX_BYTES)
        if (!updates.isDirectory && !updates.mkdirs()) throw IOException()
        val part = File.createTempFile("kevtv-", ".apk", updates)
        try {
            val c = assetConnection(update)
            try {
                if (c.contentLengthLong >= 0 && c.contentLengthLong != update.size) throw IOException()
                val digest = MessageDigest.getInstance("SHA-256")
                c.inputStream.use { input -> part.outputStream().use { output ->
                    val buffer = ByteArray(32768)
                    var total = 0L
                    while (true) {
                        checkInterrupted()
                        val n = input.read(buffer)
                        if (n < 0) break
                        total += n
                        if (total > update.size) throw IOException()
                        digest.update(buffer, 0, n)
                        output.write(buffer, 0, n)
                    }
                    if (total != update.size) throw IOException()
                } }
                if (update.sha256 != null && digest.digest().toHex() != update.sha256) throw IOException()
            } finally { c.disconnect() }
            checkInterrupted()
            verifyArchive(part, update.version)
            val target = File(updates, verifiedFileName)
            staged = null
            if (!part.renameTo(target)) throw IOException()
            staged = Staged(target, target.length(), target.lastModified())
            target
        } finally { part.delete() }
    }

    fun install(file: File) = safe("Le fichier de mise à jour doit être téléchargé de nouveau.") {
        val verified = staged ?: throw IOException()
        require(file.canonicalFile == verified.file.canonicalFile && file.isFile &&
            file.length() == verified.length && file.lastModified() == verified.modified && canInstall())
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.files", file)
        activity.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }

    fun canInstall(): Boolean = activity.packageManager.canRequestPackageInstalls()
    fun requestPermission() = safe("Les réglages d’installation Android ne sont pas accessibles.") {
        activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")))
    }

    private fun assetConnection(update: Update): HttpURLConnection {
        var url = update.url
        repeat(4) { hop ->
            checkInterrupted()
            val c = get(url)
            var accepted = false
            try {
                val status = c.responseCode
                if (status == 200) {
                    accepted = true
                    return c
                }
                if (hop == 3 || status !in setOf(301, 302, 303, 307, 308)) throw IOException()
                url = ReleaseRules.officialRedirect(c.getHeaderField("Location")) ?: throw IOException()
            } finally { if (!accepted) c.disconnect() }
        }
        throw IOException()
    }

    private fun get(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        instanceFollowRedirects = false; useCaches = false
        connectTimeout = 10_000; readTimeout = 20_000
        setRequestProperty("User-Agent", "KevTV/${BuildConfig.VERSION_NAME}")
        setRequestProperty("Accept-Encoding", "identity")
        setRequestProperty("Cache-Control", "no-store")
    }

    private fun readMetadata(input: InputStream): String {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            checkInterrupted()
            val n = input.read(buffer)
            if (n < 0) break
            if (output.size() + n > 1024 * 1024) throw IOException()
            output.write(buffer, 0, n)
        }
        return output.toString("UTF-8")
    }

    @Suppress("DEPRECATION")
    private fun installed() = activity.packageManager.getPackageInfo(activity.packageName, flags())

    @Suppress("DEPRECATION")
    private fun flags() = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES

    @Suppress("DEPRECATION")
    private fun versionCode(info: PackageInfo): Long = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()

    @Suppress("DEPRECATION")
    @SuppressLint("PackageManagerGetSignatures")
    private fun signers(info: PackageInfo): Set<String> =
        (if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures)
            ?.map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).toHex() }?.toSet().orEmpty()

    @Suppress("DEPRECATION")
    private fun verifyArchive(file: File, expectedVersion: String) {
        val currentlyInstalled = installed()
        val candidate = activity.packageManager.getPackageArchiveInfo(file.absolutePath, flags()) ?: throw IOException()
        if (!ReleaseRules.apkMatches(expectedVersion, versionCode(currentlyInstalled), candidate.packageName, candidate.versionName,
            versionCode(candidate), signers(currentlyInstalled), signers(candidate))) throw IOException()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(Locale.ROOT, it) }

    private fun checkInterrupted() {
        if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Mise à jour interrompue.")
    }

    private inline fun <T> safe(message: String, block: () -> T): T = try { block() } catch (_: Exception) {
        checkInterrupted()
        throw IOException(message)
    }
}
