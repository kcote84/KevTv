package ca.kevtv


import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket

import kotlin.concurrent.thread


class ProviderRulesTest {
    @Test fun httpsIsDefaultAndHttpMustBeExplicit() {
        assertEquals("https://tv.example.test:8443/iptv", Account.fromInput("TV.Example.test:8443/iptv/", "viewer", "key").server)
        assertEquals("https://[::1]:8443", Account.fromInput("[::1]:8443", "viewer", "key").server)
        assertTrue(Account.fromInput("HTTP://tv.example.test", "viewer", "key").isCleartext)
        assertFalse(Account.fromInput("tv.example.test:80", "viewer", "key").isCleartext)
    }

    @Test fun serverFieldDoesNotAcceptCredentialsListsOrMalformedPorts() {
        val rejected = listOf("https://viewer:key@tv.example.test", "https://tv.example.test:0",
            "https://tv.example.test:65536", "https://tv.example.test:", "file:///etc",
            "https://tv.example.test/get.php?username=demo", "https://tv.example.test/list.m3u8",
            "https://tv.example.test/path/../option", "https://tv.example.test/%2e%2E/option",
            "https://tv.example.test/path#section", "https://tv.example.test?password=demo")
        rejected.forEach { server ->
            val error = assertThrows(IllegalArgumentException::class.java) {
                Account.fromInput(server, "viewer", "key")
            }
            assertFalse(error.message.orEmpty().contains(server))
        }
        listOf("", ".", "..", "key\n").forEach { password ->
            assertThrows(IllegalArgumentException::class.java) { Account.fromInput("tv.example.test", "viewer", password) }
        }
    }

    @Test fun encodedCredentialsNeverEnterExceptionOrAccountDescription() {
        val a = Account.fromInput("tv.example.test", "viewer + /", "pa?%&# /")
        var request = ""
        val client = Xtream(a) { uri, _, _, _ ->
            request = uri
            throw IOException(uri)
        }
        val e = assertThrows(IOException::class.java) { client.authenticate() }
        assertNull(e.cause)
        assertFalse(e.message.orEmpty().contains("viewer"))
        assertFalse(a.toString().contains(a.password))
        assertTrue(request.contains("username=viewer%20%2B%20%2F&password=pa%3F%25%26%23%20%2F"))
        assertEquals("https://tv.example.test/live/viewer%20%2B%20%2F/pa%3F%25%26%23%20%2F/9.m3u8",
            client.stream(Entry("9", "Test", "", "live"), true))
        assertThrows(IllegalArgumentException::class.java) {
            client.stream(Entry("9/../../evil", "Bad", "", "live"), false)
        }
        assertThrows(XtreamAuthenticationException::class.java) {
            Xtream(a) { _, _, _, _ -> """{"user_info":{"auth":1,"status":"Expired"}}""" }.authenticate()
        }
    }

    @Test fun playbackNeverChangesHostPortOrProtocol() {
        val a = Account.fromInput("tv.example.test", "viewer", "key")
        assertTrue(a.allows("https://TV.example.test:443/channel/fixture.ts"))
        assertFalse(a.allows("https://tv.example.test:444/channel/fixture.ts"))
        assertFalse(a.allows("http://tv.example.test/channel/fixture.ts"))
        assertFalse(a.allows("https://tv.example.test.invalid/channel/fixture.ts"))
        assertFalse(a.allows("https://viewer@tv.example.test/channel/fixture.ts"))
        assertFalse(a.allows("https://tv.example.test/channel/fixture.ts#f"))
    }

    @Test fun catalogArtworkNeverUsesUrlsWithCredentialsOrTlsDowngrade() {
        val a = Account.fromInput("tv.example.test", "viewer", "secret")
        for (unsafe in listOf("https://u:p@cdn.example.test/x.jpg", "https://cdn.example.test/viewer/s%65cret/x.jpg",
            "https://cdn.example.test/logo.jpg?password=secret", "http://cdn.example.test/logo.jpg")) {
            assertEquals("", ProviderEndpoint.artwork(unsafe, a))
        }
        assertEquals("https://cdn.example.test/img/x.jpg", ProviderEndpoint.artwork("https://cdn.example.test/img/x.jpg", a))
    }

    @Test fun apiFollowsSafeSameOriginRedirectAndContinuesAuthentication() {
        Fixture { index, path ->
            if (index == 1) Reply("302 Found", "/account")
            else Reply("200 OK", body = """{"user_info":{"auth":1,"status":"Active"}}""")
        }.use { server ->
            Xtream(Account.fromInput(server.root, "test-viewer", "fixture-pass")).authenticate()
            val paths = server.requests.toList()
            assertEquals(2, paths.size)
            assertTrue(paths[0].startsWith("/player_api.php?"))
            assertEquals("/account", paths[1])
        }
    }

    @Test fun crossOriginRedirectCannotContactTarget() {
        Fixture { _, _ -> Reply("200 OK") }.use { offOrigin ->
            Fixture { _, _ -> Reply("302 Found", "${offOrigin.root}/not-allowed") }.use { server ->
                val a = Account.fromInput(server.root, "test-viewer", "fixture-pass")
                val err = assertThrows(ProviderHttpException::class.java) { Xtream(a).authenticate() }
                assertEquals(302, err.status)
                assertEquals(1, server.requests.size)
                assertTrue(offOrigin.requests.isEmpty())
                assertFalse(err.message.orEmpty().contains(a.password))
            }
        }
    }

    @Test fun redirectsResendRangeAndUseBoundedHopCount() {
        Fixture { index, _ -> if (index == 1) Reply("307 Temporary Redirect", "/next") else Reply("206 Partial Content", body = "abc") }
            .use { server ->
                val response = ProviderTransport.get("${server.root}/stream", 1000, 1000, "bytes=7-9")
                try {
                    assertEquals("${server.root}/next", response.finalUri)
                    assertEquals(206, response.status)
                    assertEquals(listOf("bytes=7-9", "bytes=7-9"), server.ranges.toList())
                } finally { response.connection.disconnect() }
            }
        Fixture { _, _ -> Reply("302 Found", "/again") }.use { server ->
            assertThrows(ProviderHttpException::class.java) {
                ProviderTransport.get("${server.root}/start", 1000, 1000)
            }
            assertEquals(4, server.requests.size) // initial GET plus three redirects
        }
    }

    @Test fun redirectReferenceRejectsDowngradeUserInfoAndFragments() {
        val source = "https://tv.example.test/base/player_api.php?username=fake"
        assertEquals("https://tv.example.test/base/player_api.php?new=1", ProviderEndpoint.redirect(source, source, "?new=1"))
        assertEquals("https://tv.example.test/base/ok", ProviderEndpoint.redirect(source, source, "ok"))
        assertNull(ProviderEndpoint.redirect(source, source, "http://tv.example.test/unsafe"))
        assertNull(ProviderEndpoint.redirect(source, source, "//username@tv.example.test/unsafe"))
        assertNull(ProviderEndpoint.redirect(source, source, "/unsafe#fragment"))
    }

    private data class Reply(val status: String, val location: String? = null, val body: String = "")

    private class Fixture(private val respond: (Int, String) -> Reply) : java.io.Closeable {
        private val server = ServerSocket(0, 6, InetAddress.getByName("127.0.0.1"))
        val root = "http://127.0.0.1:${server.localPort}"
        val requests = java.util.concurrent.CopyOnWriteArrayList<String>()
        val ranges = java.util.concurrent.CopyOnWriteArrayList<String>()
        private val serving = thread(isDaemon = true) {
            while (!server.isClosed) {
                try {
                    server.accept().use { socket ->
                        socket.soTimeout = 2000
                        val reader = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
                        val path = reader.readLine().orEmpty().split(' ').getOrElse(1) { "" }
                        while (true) {
                            val header = reader.readLine() ?: break
                            if (header.isEmpty()) break
                            if (header.startsWith("Range:", true)) ranges.add(header.substringAfter(':').trim())
                        }
                        requests.add(path)
                        val answer = respond(requests.size, path)
                        val bytes = answer.body.toByteArray(Charsets.UTF_8)
                        val location = answer.location?.let { "Location: $it\r\n" }.orEmpty()
                        socket.getOutputStream().apply {
                            write(("HTTP/1.1 ${answer.status}\r\n${location}Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
                            write(bytes)
                            flush()
                        }
                    }
                } catch (_: IOException) { if (server.isClosed) return@thread }
            }
        }

        override fun close() {
            server.close()
            serving.join(2000)
        }
    }
}