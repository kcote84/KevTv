package ca.kevtv

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.min

internal class ProviderHttpException(val status: Int) : IOException("Réponse du fournisseur : HTTP $status.")
class XtreamAuthenticationException : IOException("Connexion refusée ou abonnement expiré.")

internal object ProviderTransport {
    fun open(url: String, connectMs: Int, readMs: Int): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            useCaches = false
            connectTimeout = connectMs
            readTimeout = readMs
            setRequestProperty("User-Agent", "KevTV/${BuildConfig.VERSION_NAME}")
            setRequestProperty("Cache-Control", "no-store")
            setRequestProperty("Accept-Encoding", "identity")
        }

    data class Response(val connection: HttpURLConnection, val status: Int, val finalUri: String)

    fun get(url: String, connectMs: Int, readMs: Int, range: String? = null): Response {
        var target = url
        repeat(4) { hop ->
            if (Thread.currentThread().isInterrupted) throw InterruptedIOException()
            if (!ProviderEndpoint.sameOrigin(url, target)) throw IOException("Adresse du fournisseur refusée.")
            val c = open(target, connectMs, readMs)
            var accepted = false
            try {
                c.requestMethod = "GET"
                if (range != null) c.setRequestProperty("Range", range)
                val status = c.responseCode
                if (status in 200..299) {
                    accepted = true
                    return Response(c, status, target)
                }
                if (hop == 3 || status !in setOf(301, 302, 303, 307, 308)) throw ProviderHttpException(status)
                target = ProviderEndpoint.redirect(url, target, c.getHeaderField("Location")) ?: throw ProviderHttpException(status)
            } finally {
                if (!accepted) c.disconnect()
            }
        }
        throw IOException("Trop de redirections du fournisseur.")
    }

    // HTTP/JSON exceptions in platform libraries can echo request URLs or response bodies.
    fun safeFailure(e: Exception): IOException = when {
        e is ProviderHttpException -> e
        e is XtreamAuthenticationException -> e
        Thread.currentThread().isInterrupted -> InterruptedIOException("Connexion interrompue.")
        e is java.net.SocketTimeoutException -> java.net.SocketTimeoutException("Le fournisseur met trop de temps à répondre.")
        e is java.net.UnknownHostException -> java.net.UnknownHostException("Adresse du fournisseur introuvable.")
        e is javax.net.ssl.SSLException -> javax.net.ssl.SSLException("La connexion HTTPS au fournisseur a échoué.")
        else -> IOException("Impossible de communiquer avec le fournisseur.")
    }
}

/** Constrains media and HLS subrequests to the entered provider's scheme, host and port. */
@androidx.annotation.OptIn(UnstableApi::class)
class ProviderDataSource private constructor(private val account: Account) : BaseDataSource(true) {
    private var connection: HttpURLConnection? = null
    private var input: InputStream? = null
    private var uri: Uri? = null
    private var remaining = C.LENGTH_UNSET.toLong()
    private var started = false

    override fun open(spec: DataSpec): Long {
        transferInitializing(spec)
        try {
            if (!account.allows(spec.uri.toString()) || spec.httpMethod != DataSpec.HTTP_METHOD_GET) {
                throw IOException("Adresse du flux refusée.")
            }
            val range = if (spec.position != 0L || spec.length != C.LENGTH_UNSET.toLong()) {
                val end = if (spec.length == C.LENGTH_UNSET.toLong()) "" else
                    Math.addExact(spec.position, spec.length - 1).toString()
                "bytes=${spec.position}-$end"
            } else null
            val response = ProviderTransport.get(spec.uri.toString(), 15000, 20000, range)
            val c = response.connection
            connection = c
            val status = response.status
            val stream = c.inputStream
            input = stream
            val skip = if (status == 200) spec.position else 0L
            remaining = if (spec.length != C.LENGTH_UNSET.toLong()) {
                spec.length
            } else {
                c.contentLengthLong.takeIf { it >= 0 }?.let { (it - skip).coerceAtLeast(0L) } ?: C.LENGTH_UNSET.toLong()
            }
            uri = Uri.parse(response.finalUri)
            started = true
            transferStarted(spec)
            skipFully(stream, skip)
            return remaining
        } catch (e: Exception) {
            close()
            throw ProviderTransport.safeFailure(e)
        }
    }

    private fun skipFully(stream: InputStream, amount: Long) {
        var unread = amount
        val scratch = ByteArray(8192)
        while (unread > 0) {
            if (Thread.currentThread().isInterrupted) throw InterruptedIOException()
            val n = stream.read(scratch, 0, min(unread, scratch.size.toLong()).toInt())
            if (n == -1) throw EOFException()
            unread -= n
            bytesTransferred(n)
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        return try {
            val cap = if (remaining == C.LENGTH_UNSET.toLong()) length else min(remaining, length.toLong()).toInt()
            val n = (input ?: throw IOException()).read(buffer, offset, cap)
            if (n == -1) {
                if (remaining != C.LENGTH_UNSET.toLong()) throw EOFException()
                return C.RESULT_END_OF_INPUT
            }
            if (remaining != C.LENGTH_UNSET.toLong()) {
                remaining -= n
            }
            bytesTransferred(n)
            n
        } catch (e: Exception) { throw ProviderTransport.safeFailure(e) }
    }

    override fun getUri(): Uri? = uri
    override fun close() {
        uri = null
        runCatching { input?.close() }
        input = null
        runCatching { connection?.disconnect() }
        connection = null
        if (started) {
            started = false
            transferEnded()
        }
    }

    companion object {
        fun factory(account: Account): DataSource.Factory = DataSource.Factory { ProviderDataSource(account) }
    }
}
